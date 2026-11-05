package com.alwayside.android

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CancellationException

internal interface AiGateway {
    fun respond(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?,
        cancellation: RequestCancellation, onDelta: (String) -> Unit): String
    fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String
    fun searchSuggestions(): String = ""
}

/** Blocking transport: callers must use a worker thread and a fresh cancellation token. */
internal class AiProvider : AiGateway {
    var lastSearchUsed: Boolean = false; private set
    private var lastSuggestions: String = ""
    override fun searchSuggestions(): String = lastSuggestions
    override fun respond(
        config: AppConfig,
        history: List<ChatMessage>,
        question: String,
        imageDataUrl: String?,
        cancellation: RequestCancellation,
        onDelta: (String) -> Unit,
    ): String = sanitized(cancellation) {
        lastSearchUsed = false; lastSuggestions = ""
        validateConnection(config)
        validateModel(config.model)
        ProviderProtocol.validateOptions(config)
        ProviderCapabilities.validate(config, imageDataUrl != null)
        ChatPrompt.validateAssembled(question)
        if (config.instructions.length > 32_768)
            throw ProviderException("Instructions 过长，请缩短后重试")
        imageDataUrl?.let(::validateImage)
        val adapter: ChatAdapter = when (config.apiProtocol) {
            ApiProtocol.RESPONSES -> ResponsesAdapter
            ApiProtocol.CHAT_COMPLETIONS -> ChatCompletionsAdapter
            ApiProtocol.GEMINI -> GeminiAdapter
            ApiProtocol.CLAUDE -> ClaudeMessagesAdapter()
        }
        val wire = adapter.request(config, history, question, imageDataUrl)
        val decoder = adapter.decoder(config)
        val bytes = WireJson.encode(wire.body).toByteArray(Charsets.UTF_8)
        HttpTransport().request(config, wire.route, "application/json", bytes.size.toLong(), cancellation,
            { it.write(bytes) }, streaming = true, query = wire.query) { connection, deadline ->
            if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", true))
                throw ProviderException("服务未返回流式回答，请检查 API 协议、地址与模型")
            val emit: (String) -> Unit = { cancellation.throwIfCancelled(); onDelta(it) }
            connection.inputStream.use { input ->
                val stream = EventStream(input, cancellation, deadline)
                while (!decoder.complete) {
                    val event = stream.next() ?: break
                    if (event.data == "[DONE]") decoder.accept("__DONE__", emptyMap(), emit)
                    else decoder.accept(event.name, WireJson.decode(event.data), emit)
                }
                cancellation.throwIfCancelled()
                decoder.finish(emit).also { lastSearchUsed = decoder.searchUsed; lastSuggestions = decoder.searchSuggestions }
            }
        }
    }

    override fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String = sanitized(cancellation) {
        val target = ProviderConnections.transcription(config)
        validateConnection(target)
        validateModel(target.transcriptionModel)
        if (target.transcriptionPrompt.length > 32000) throw ProviderException("转写提示过长")
        WavTranscription.transcribe(file, cancellation, ChatPrompt.MAX_TRANSCRIPT_CHARS) { source, bytes ->
            transcribePart(target, source, bytes, cancellation)
        }
    }

    private fun transcribePart(config: AppConfig, source: RandomAccessFile, pcmBytes: Int,
                               cancellation: RequestCancellation): String {
        val fileSize = pcmBytes.toLong() + 44
        val boundary = "alwayside-${UUID.randomUUID()}"
        val prompt = if (config.transcriptionPrompt.isBlank()) "" else
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n${config.transcriptionPrompt}"
        val language = if (config.transcriptionLanguage.isBlank()) "" else
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n${config.transcriptionLanguage}"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" +
            config.transcriptionModel + "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson" +
            prompt + language +
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
            val value = JSONObject(json).opt("text")
            if (value !is String) throw ProviderException("转写接口未返回文字，请检查接口协议与模型")
            val text = value.trim()
            if (text.length > ChatPrompt.MAX_TRANSCRIPT_CHARS) throw ProviderException("转写文字过长，请缩短录制")
            text
        }
    }

    /** Explicit metadata only. Model-list success does not establish image/search compatibility. */
    fun listModels(config: AppConfig, cancellation: RequestCancellation = RequestCancellation()): List<String> =
        listModelCatalog(config, cancellation).map { it.id }

    fun listModelCatalog(config: AppConfig, cancellation: RequestCancellation = RequestCancellation()): List<RemoteModel> = sanitized(cancellation) {
        validateConnection(config)
        val result = linkedMapOf<String, RemoteModel>(); var cursor = ""
        repeat(20) {
            val query = when (config.apiProtocol) {
                ApiProtocol.GEMINI -> buildMap { put("pageSize", "100"); if (cursor.isNotEmpty()) put("pageToken", cursor) }
                ApiProtocol.CLAUDE -> buildMap { put("limit", "100"); if (cursor.isNotEmpty()) put("after_id", cursor) }
                else -> emptyMap()
            }
            val json = HttpTransport().request(config, "models", "application/json", 0, cancellation, {},
                method = "GET", query = query) { connection, deadline ->
                connection.inputStream.use { WireJson.decode(readBounded(it, 2 * 1024 * 1024, cancellation, deadline)) }
            }
            val key = if (config.apiProtocol == ApiProtocol.GEMINI) "models" else "data"
            if (json[key] !is List<*>) throw ProviderException("模型列表格式无效，可手动填写模型 ID")
            json.items(key).forEach { raw ->
                RemoteModel.decode(raw.wireObject(), config.apiProtocol)?.let { model -> result[model.id] = model }
            }
            if (result.size > 10000) throw ProviderException("模型列表过大")
            val next = when (config.apiProtocol) {
                ApiProtocol.GEMINI -> json.text("nextPageToken")
                ApiProtocol.CLAUDE -> if (json["has_more"] == true) json.text("last_id").ifBlank { throw ProviderException("模型列表分页格式无效") } else ""
                else -> ""
            }
            if (next.isEmpty()) return@sanitized result.values.sortedBy { it.id }
            if (next == cursor) throw ProviderException("模型列表分页未推进")
            cursor = next
        }
        throw ProviderException("模型列表分页过多，请手动填写模型 ID")
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
        return HttpTransport().request(config, route, contentType, contentLength, cancellation, writeBody,
            method, streaming = route == "responses", readBody = readBody)
    }

    private fun readBounded(input: InputStream, maxBytes: Int, cancellation: RequestCancellation, deadline: Long) =
        HttpTransport.readBounded(input, maxBytes, cancellation, deadline)

    private fun validateConnection(config: AppConfig) {
        if (config.apiKey.isBlank() || config.apiKey.length > 4096 || config.apiKey.any { it.code < 33 || it.code > 126 })
            throw ProviderException("请先为当前连接设置有效的 API Key")
        ProviderProtocol.endpoint(config.baseUrl, "models")
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(config.apiVersion)) { "API 版本格式无效" }
        require(config.workspaceId.length <= 200 && config.workspaceId.none { it.code < 33 || it.code > 126 }) { "Workspace ID 无效" }
    }

    private fun validateModel(model: String) {
        if (!Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}").matches(model)) throw ProviderException("请先为此用途填写有效的模型 ID")
    }

    private fun validateImage(data: String) {
        if (data.length > 16 * 1024 * 1024) throw ProviderException("图片过大，请缩小框选区域")
        val prefix = listOf("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,").firstOrNull(data::startsWith)
            ?: throw ProviderException("图片格式无效")
        val base64 = data.substring(prefix.length)
        if (base64.isEmpty() || base64.length % 4 != 0 || !Regex("[A-Za-z0-9+/]+={0,2}").matches(base64)) throw ProviderException("图片数据无效")
    }

    private fun <T> sanitized(cancellation: RequestCancellation, block: () -> T): T {
        try { cancellation.throwIfCancelled(); return block() }
        catch (error: CancellationException) { throw error }
        catch (error: ProviderException) { cancellation.throwIfCancelled(); throw error }
        catch (_: SocketTimeoutException) { cancellation.throwIfCancelled(); throw ProviderException("网络请求超时，请手动重试") }
        catch (_: IOException) { cancellation.throwIfCancelled(); throw ProviderException("网络连接失败，请检查网络与 API 地址后手动重试") }
        catch (_: JSONException) { cancellation.throwIfCancelled(); throw ProviderException("服务返回格式无效，请检查 API 兼容性") }
        catch (error: IllegalArgumentException) {
            cancellation.throwIfCancelled()
            val message = error.message.orEmpty()
            throw ProviderException(message.take(180).ifBlank { "请求配置无效，请检查设置" })
        }
    }
}
