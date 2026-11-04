package com.alwayside.android

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface AiGateway {
    fun respond(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?,
        cancellation: RequestCancellation, onDelta: (String) -> Unit): String
    fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String
}

/** Blocking transport: callers must use a worker thread and a fresh cancellation token. */
internal class AiProvider : AiGateway {
    override fun respond(
        config: AppConfig,
        history: List<ChatMessage>,
        question: String,
        imageDataUrl: String?,
        cancellation: RequestCancellation,
        onDelta: (String) -> Unit,
    ): String = sanitized(cancellation) {
        validateConfig(config)
        if (question.isBlank() || question.length > 32_768 || config.instructions.length > 32_768) {
            throw ProviderException("问题或 Instructions 为空或过长，请缩短后重试")
        }
        val input = JSONArray()
        // Keep the newest complete text turns within a fixed local context budget.
        val selected = ArrayList<ChatMessage>()
        var budget = 65_536
        for (message in history.asReversed()) {
            if (message.role !in setOf("user", "assistant") || message.text.isBlank()) continue
            if (selected.size >= 24 || message.text.length > budget) break
            selected.add(message)
            budget -= message.text.length
        }
        selected.asReversed().forEach { input.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val current = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
        if (imageDataUrl != null) {
            validateImage(imageDataUrl)
            current.put(JSONObject().put("type", "input_image").put("image_url", imageDataUrl))
        }
        input.put(JSONObject().put("role", "user").put("content", current))
        val body = JSONObject()
            .put("model", config.model)
            .put("instructions", config.instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
        ProviderProtocol.responseOptions(config).forEach { (name, value) -> body.put(name, JSONObject.wrap(value)) }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        request(config, "responses", "application/json", bytes.size.toLong(), cancellation, {
            it.write(bytes)
        }) { connection, deadline ->
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
            if (!contentType.equals("text/event-stream", ignoreCase = true)) {
                throw ProviderException("服务未返回流式回答，请检查 API 地址与模型兼容性")
            }
            connection.inputStream.use { readResponse(it, cancellation, deadline, onDelta) }
        }
    }

    override fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String = sanitized(cancellation) {
        validateConfig(config)
        WavTranscription.transcribe(file, cancellation, MAX_OUTPUT_CHARS) { source, bytes ->
            transcribePart(config, source, bytes, cancellation)
        }
    }

    private fun transcribePart(config: AppConfig, source: RandomAccessFile, pcmBytes: Int,
                               cancellation: RequestCancellation): String {
        val fileSize = pcmBytes.toLong() + 44
        val boundary = "alwayside-${UUID.randomUUID()}"
        val prompt = if (config.transcriptionPrompt.isBlank()) "" else
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n${config.transcriptionPrompt}"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" +
            config.transcriptionModel + "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson" +
            prompt +
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return request(config, "audio/transcriptions", "multipart/form-data; boundary=$boundary",
            prefix.size + fileSize + suffix.size, cancellation, { output ->
                output.write(prefix)
                output.write(Wav.header(pcmBytes, RecordingLimits.SAMPLE_RATE))
                val buffer = ByteArray(16_384)
                var remaining = pcmBytes
                while (remaining > 0) {
                    cancellation.throwIfCancelled()
                    val count = source.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw ProviderException("音频文件在发送时发生变化，请重新录制")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.write(suffix)
            }) { connection, deadline ->
            val json = connection.inputStream.use { readBounded(it, 1024 * 1024, cancellation, deadline) }
            val text = JSONObject(json).optString("text").trim()
            if (text.length > MAX_OUTPUT_CHARS) throw ProviderException("转写文字过长，请缩短录制")
            text
        }
    }

    /** Explicit metadata request only: no question, screenshot or recording is uploaded. */
    fun listModels(config: AppConfig, cancellation: RequestCancellation = RequestCancellation()): List<String> = sanitized(cancellation) {
        validateConfig(config)
        request(config, "models", "application/json", 0, cancellation, {}, "GET") { connection, deadline ->
            val json = JSONObject(connection.inputStream.use { readBounded(it, 2 * 1024 * 1024, cancellation, deadline) })
            val data = json.optJSONArray("data") ?: throw ProviderException("模型列表格式无效")
            if (data.length() > 10000) throw ProviderException("模型列表过大")
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }.distinct().sorted()
        }
    }

    private fun readResponse(
        input: InputStream,
        cancellation: RequestCancellation,
        deadline: Long,
        onDelta: (String) -> Unit,
    ): String {
        val stream = EventStream(input, cancellation, deadline)
        val answer = StringBuilder()
        val citations = linkedMapOf<String, String>()
        fun append(delta: String) {
            if (answer.length + delta.length > MAX_OUTPUT_CHARS) throw ProviderException("回答过长，请缩小问题范围")
            cancellation.throwIfCancelled()
            answer.append(delta)
            if (delta.isNotEmpty()) onDelta(delta)
        }
        while (true) {
            val event = stream.next() ?: throw ProviderException("回答连接提前中断，结果不完整，请手动重试")
            if (event.data == "[DONE]") throw ProviderException("服务未确认回答完成，请手动重试")
            val json = JSONObject(event.data)
            when (json.optString("type", event.name)) {
                "response.output_text.delta" -> append(json.optString("delta"))
                "response.refusal.delta" -> {
                    val delta = json.optString("delta")
                    append(delta)
                }
                "response.output_text.annotation.added" -> collectCitation(json.optJSONObject("annotation"), citations)
                "response.failed", "error" -> throw ProviderException("服务生成回答失败，请检查模型配置或稍后手动重试")
                "response.incomplete" -> throw ProviderException("回答未完成，可能达到输出限制，请调整后手动重试")
                "response.completed" -> {
                    val response = json.optJSONObject("response") ?: throw ProviderException("服务完成事件无效")
                    if (response.optString("status") != "completed" || !response.isNull("error") ||
                        !response.isNull("incomplete_details")) throw ProviderException("服务未成功完成回答，请手动重试")
                    val finalText = StringBuilder()
                    val output = response.optJSONArray("output") ?: JSONArray()
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") != "message") continue
                        val content = item.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            when (part.optString("type")) {
                                "output_text" -> {
                                    finalText.append(part.optString("text"))
                                    val annotations = part.optJSONArray("annotations") ?: JSONArray()
                                    for (k in 0 until annotations.length()) collectCitation(annotations.optJSONObject(k), citations)
                                }
                                "refusal" -> finalText.append(part.optString("refusal"))
                            }
                            if (finalText.length > MAX_OUTPUT_CHARS) throw ProviderException("回答过长，请缩小问题范围")
                        }
                    }
                    if (answer.isEmpty()) append(finalText.toString())
                    else if (finalText.isNotEmpty() && answer.toString() != finalText.toString()) {
                        throw ProviderException("回答片段与最终结果不一致，请手动重试")
                    }
                    if (answer.isBlank()) throw ProviderException("服务没有返回文字回答")
                    // Citation URLs come from structured annotations, never from model-generated titles.
                    if (citations.isNotEmpty()) {
                        append("\n\n来源：\n" + citations.entries.joinToString("\n") { (url, title) -> "- [$title]($url)" })
                    }
                    cancellation.throwIfCancelled()
                    return answer.toString()
                }
            }
        }
    }

    private fun collectCitation(annotation: JSONObject?, citations: MutableMap<String, String>) {
        if (annotation?.optString("type") != "url_citation" || citations.size >= 10) return
        val raw = annotation.optString("url")
        if (raw.length > 2048) return
        val uri = try { URI(raw) } catch (_: Exception) { return }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return
        val url = uri.toASCIIString().replace("(", "%28").replace(")", "%29")
        val title = annotation.optString("title").ifBlank { uri.host }.take(160)
            .replace(Regex("[\\[\\]\\r\\n\\\\]"), " ")
        citations[url] = title
    }

    private fun <T> request(
        config: AppConfig,
        route: String,
        contentType: String,
        contentLength: Long,
        cancellation: RequestCancellation,
        writeBody: (java.io.OutputStream) -> Unit,
        method: String = "POST",
        readBody: (HttpURLConnection, Long) -> T,
    ): T {
        cancellation.throwIfCancelled()
        val deadline = System.nanoTime() + 240_000_000_000L
        val connection = ProviderProtocol.endpoint(config.baseUrl, route).openConnection() as HttpURLConnection
        val timedOut = AtomicBoolean(false)
        val timeout = timeouts.schedule({
            timedOut.set(true)
            connection.disconnect()
        }, 240, TimeUnit.SECONDS)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false // Never forward a bearer token to a redirect target.
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.useCaches = false
            connection.doOutput = method == "POST"
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            if (method == "POST") connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Accept", if (route == "responses") "text/event-stream" else "application/json")
            if (method == "POST") connection.setFixedLengthStreamingMode(contentLength)
            cancellation.attach(connection)
            if (method == "POST") connection.outputStream.use { output -> cancellation.throwIfCancelled(); writeBody(output) }
            cancellation.throwIfCancelled()
            val status = connection.responseCode
            if (status !in 200..299) throw ProviderException(statusMessage(status))
            val result = readBody(connection, deadline)
            if (timedOut.get()) throw ProviderException("请求超时，请手动重试")
            return result
        } catch (error: IOException) {
            if (timedOut.get()) throw ProviderException("请求超时，请手动重试")
            throw error
        } finally {
            timeout.cancel(false)
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int, cancellation: RequestCancellation, deadline: Long): String {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            cancellation.throwIfCancelled()
            if (System.nanoTime() > deadline) throw ProviderException("请求超时，请手动重试")
            val count = input.read(buffer)
            if (count < 0) return result.toString("UTF-8")
            if (result.size() + count > maxBytes) throw ProviderException("服务返回内容过大")
            result.write(buffer, 0, count)
        }
    }

    private fun validateConfig(config: AppConfig) {
        if (config.apiKey.isBlank() || config.apiKey.length > 4096 || config.apiKey.any { it.code < 33 || it.code > 126 }) {
            throw ProviderException("请先设置有效的 API Key")
        }
        val modelPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}")
        if (!modelPattern.matches(config.model) || !modelPattern.matches(config.transcriptionModel)) throw ProviderException("模型名称无效")
        try { ProviderProtocol.validateOptions(config) }
        catch (error: IllegalArgumentException) { throw ProviderException(error.message ?: "生成参数无效") }
        if (config.transcriptionPrompt.length > 32000) throw ProviderException("转写提示过长")
    }

    private fun validateImage(data: String) {
        if (data.length > 16 * 1024 * 1024) throw ProviderException("图片过大，请缩小框选区域")
        val prefix = listOf("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,").firstOrNull(data::startsWith)
            ?: throw ProviderException("图片格式无效")
        val base64 = data.substring(prefix.length)
        if (base64.isEmpty() || base64.length % 4 != 0 || !Regex("[A-Za-z0-9+/]+={0,2}").matches(base64)) throw ProviderException("图片数据无效")
    }

    private fun statusMessage(status: Int): String = when (status) {
        in 300..399 -> "服务返回重定向（HTTP $status），请直接填写最终 HTTPS API 地址"
        401, 403 -> "API 认证或访问权限失败（HTTP $status），请检查 Key 与模型权限"
        408, 504 -> "服务请求超时（HTTP $status），请手动重试"
        413 -> "请求内容过大（HTTP 413），请缩短音频或缩小图片"
        429 -> "请求限流或额度不足（HTTP 429），请检查用量后手动重试"
        in 500..599 -> "服务暂时不可用（HTTP $status），请稍后手动重试"
        else -> "请求被服务拒绝（HTTP $status），请检查 API 地址与模型配置"
    }

    private fun <T> sanitized(cancellation: RequestCancellation, block: () -> T): T {
        try { cancellation.throwIfCancelled(); return block() }
        catch (error: CancellationException) { throw error }
        catch (error: ProviderException) { cancellation.throwIfCancelled(); throw error }
        catch (_: SocketTimeoutException) { cancellation.throwIfCancelled(); throw ProviderException("网络请求超时，请手动重试") }
        catch (_: IOException) { cancellation.throwIfCancelled(); throw ProviderException("网络连接失败，请检查网络与 API 地址后手动重试") }
        catch (_: JSONException) { cancellation.throwIfCancelled(); throw ProviderException("服务返回格式无效，请检查 API 兼容性") }
        catch (_: IllegalArgumentException) { cancellation.throwIfCancelled(); throw ProviderException("请求配置无效，请检查设置") }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
        val timeouts = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "alwayside-request-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
