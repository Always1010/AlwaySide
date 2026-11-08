package com.alwayside.android

import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking transport shared by all protocols. Never redirects credentials or retries a request. */
internal class HttpTransport {
    fun <T> request(config: AppConfig, route: String, contentType: String, contentLength: Long,
        cancellation: RequestCancellation, writeBody: (java.io.OutputStream) -> Unit,
        method: String = "POST", streaming: Boolean = false, query: Map<String, String> = emptyMap(),
        accept: String = if (streaming) "text/event-stream" else "application/json",
        timeoutSeconds: Int = 240,
        readBody: (HttpURLConnection, Long) -> T): T {
        cancellation.throwIfCancelled()
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        val connection = ProviderProtocol.endpoint(config.baseUrl, route, query).openConnection() as HttpURLConnection
        val timedOut = AtomicBoolean(false)
        val timeout = timeouts.schedule({ timedOut.set(true); connection.disconnect() }, timeoutSeconds.toLong(), TimeUnit.SECONDS)
        try {
            ActiveServiceRequests.track(config, cancellation)
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = if (timeoutSeconds > 240) 180_000 else 60_000
            connection.useCaches = false
            connection.doOutput = method == "POST"
            authHeaders(config).forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (method == "POST") connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Accept", accept)
            if (method == "POST") connection.setFixedLengthStreamingMode(contentLength)
            cancellation.attach(connection)
            if (method == "POST") connection.outputStream.use { cancellation.throwIfCancelled(); writeBody(it) }
            cancellation.throwIfCancelled()
            val status = connection.responseCode
            if (status !in 200..299) throw failure(status)
            val result = readBody(connection, deadline)
            if (timedOut.get()) throw ProviderException("请求超时，请手动重试", "请求超时，可重试")
            return result
        } catch (error: java.io.IOException) {
            if (timedOut.get()) throw ProviderException("请求超时，请手动重试", "请求超时，可重试")
            throw error
        } finally {
            ActiveServiceRequests.release(cancellation)
            timeout.cancel(false); cancellation.detach(connection); connection.disconnect()
        }
    }

    companion object {
        fun authHeaders(config: AppConfig): Map<String, String> = when (config.apiProtocol) {
            ApiProtocol.GEMINI -> mapOf("x-goog-api-key" to config.apiKey.trim())
            ApiProtocol.CLAUDE -> buildMap {
                put("x-api-key", config.apiKey.trim()); put("anthropic-version", config.apiVersion)
                if (config.workspaceId.isNotBlank()) put("anthropic-workspace-id", config.workspaceId)
            }
            else -> mapOf("Authorization" to "Bearer ${config.apiKey.trim()}")
        }
        fun readBounded(input: InputStream, maxBytes: Int, cancellation: RequestCancellation, deadline: Long): String {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                cancellation.throwIfCancelled()
                if (System.nanoTime() > deadline) throw ProviderException("请求超时，请手动重试", "请求超时，可重试")
                val count = input.read(buffer)
                if (count < 0) return result.toString("UTF-8")
                if (result.size() + count > maxBytes) throw ProviderException("服务返回内容过大")
                result.write(buffer, 0, count)
            }
        }
        internal fun failure(status: Int) = ProviderException(statusMessage(status), when (status) {
            401, 403 -> "API 认证或权限失败"
            404 -> "接口或模型不存在"
            408, 504 -> "请求超时，可重试"
            413 -> "请求内容过大"
            429 -> "请求受限或额度不足"
            in 500..599 -> "服务暂时不可用"
            else -> "请求失败，请查看详情"
        })

        private fun statusMessage(status: Int) = when (status) {
            in 300..399 -> "服务返回重定向（HTTP $status），请直接填写最终 HTTPS API 地址"
            401, 403 -> "API 认证或访问权限失败（HTTP $status），请检查 Key、地域与模型权限"
            404 -> "接口或模型不存在（HTTP 404），请检查基础地址、协议和模型 ID"
            408, 504 -> "服务请求超时（HTTP $status），请手动重试"
            413 -> "请求内容过大（HTTP 413），请缩短音频或缩小图片"
            429 -> "请求限流或额度不足（HTTP 429），请检查用量后手动重试"
            in 500..599 -> "服务暂时不可用（HTTP $status），请稍后手动重试"
            else -> "请求被服务拒绝（HTTP $status），请检查协议、模型和生成参数"
        }
        private val timeouts = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "alwayside-request-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
