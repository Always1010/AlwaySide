package com.alwayside.android

import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException

/** One cancellation token per request; cancellation also interrupts a blocked network read. */
internal class RequestCancellation {
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    private var connection: HttpURLConnection? = null

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            connection.also { connection = null }
        }
        active?.disconnect()
    }

    fun throwIfCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("请求已取消")
    }

    internal fun attach(active: HttpURLConnection) {
        synchronized(this) {
            throwIfCancelled()
            check(connection == null) { "取消令牌不能同时用于多个请求" }
            connection = active
        }
        // Covers cancellation immediately after attaching and before opening the socket.
        throwIfCancelled()
    }

    internal fun detach(active: HttpURLConnection) {
        synchronized(this) { if (connection === active) connection = null }
    }
}

internal class ProviderException(message: String, val summary: String = "请求失败，请查看详情") : Exception(message)

internal object ProviderProtocol {
    fun endpoint(baseUrl: String, route: String, query: Map<String, String> = emptyMap()): URL {
        val uri = try { URI(baseUrl.trim().trimEnd('/')) } catch (_: Exception) {
            throw ProviderException("API 地址无效，请使用 HTTPS 基础地址")
        }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 ||
            uri.port == 0 || uri.rawPath != uri.path || uri.normalize() != uri ||
            uri.path.orEmpty().contains("//") ||
            !(route in setOf("responses", "chat/completions", "messages", "audio/transcriptions", "models") ||
                Regex("models/[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}:streamGenerateContent").matches(route)) ||
            query.any { (key, value) -> key !in setOf("alt", "pageSize", "pageToken", "limit", "after_id") || value.length > 4096 || value.any(Char::isISOControl) } ||
            (query.containsKey("alt") && query["alt"] != "sse")) {
            throw ProviderException("API 地址或接口路径无效，请使用不含账号、查询参数或片段的 HTTPS 基础地址")
        }
        val suffix = if (query.isEmpty()) "" else query.entries.joinToString("&", "?") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        return URL("${uri.toASCIIString()}/$route$suffix")
    }

    fun validateOptions(config: AppConfig) {
        require(config.maxOutputTokens == 0 || config.maxOutputTokens in 16..131072) { "最大输出 tokens 请留空或填写 16 至 131072 的整数" }
        require(config.reasoning in setOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max")) { "推理程度设置无效" }
        require(config.verbosity in setOf("", "low", "medium", "high")) { "回答详细程度设置无效" }
        require(config.temperature == null || (config.temperature.isFinite() && config.temperature in 0.0..2.0)) { "temperature 请填写 0 至 2 的数值" }
        require(config.topP == null || (config.topP.isFinite() && config.topP in 0.0..1.0)) { "top_p 请填写 0 至 1 的数值" }
        require(config.temperature == null || config.topP == null) { "temperature 与 top_p 请选择其中一项" }
        require(config.audioOutput in setOf("transcript", "translate", "interpret")) { "音频输出方式无效" }
    }

    /** Values are omitted unless explicitly configured; never downgrade a rejected request. */
    fun responseOptions(config: AppConfig): Map<String, Any> {
        validateOptions(config)
        return linkedMapOf<String, Any>().apply {
            if (config.maxOutputTokens != 0) put("max_output_tokens", config.maxOutputTokens)
            if (config.reasoning.isNotBlank()) put("reasoning", mapOf("effort" to config.reasoning))
            if (config.verbosity.isNotBlank()) put("text", mapOf("verbosity" to config.verbosity))
            config.temperature?.let { put("temperature", it) }
            config.topP?.let { put("top_p", it) }
            if (config.webSearch) put("tools", listOf(mapOf("type" to "web_search")))
        }
    }
}

internal data class ServerEvent(val name: String, val data: String)

/** SSE framing, including multiline data and CRLF, with bounded lines, events and total input. */
internal class EventStream(
    input: InputStream,
    private val cancellation: RequestCancellation,
    private val deadlineNanos: Long,
    private val maxLine: Int = 1024 * 1024,
    private val maxEvent: Int = 2 * 1024 * 1024,
    private val maxTotal: Int = 16 * 1024 * 1024,
) {
    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private var total = 0
    private var firstCharacter = true
    private var skipLf = false

    fun next(): ServerEvent? {
        var event = "message"
        val data = StringBuilder()
        while (true) {
            val line = readLine() ?: return null // An unclosed event is truncated, never dispatched.
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return ServerEvent(event, data.dropLast(1).toString())
                event = "message"
                continue
            }
            if (line.startsWith(':')) continue
            val split = line.indexOf(':')
            val field = if (split < 0) line else line.substring(0, split)
            val value = if (split < 0) "" else line.substring(split + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (data.length + value.length + 1 > maxEvent) throw ProviderException("服务返回内容过大")
                    data.append(value).append('\n')
                }
            }
        }
    }

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            cancellation.throwIfCancelled()
            if (System.nanoTime() > deadlineNanos) throw ProviderException("请求超时，请手动重试", "请求超时，可重试")
            val code = reader.read()
            if (code < 0) return null
            if (++total > maxTotal) throw ProviderException("服务返回内容过大")
            val char = code.toChar()
            if (firstCharacter) {
                firstCharacter = false
                if (char == '\uFEFF') continue
            }
            if (skipLf) { skipLf = false; if (char == '\n') continue }
            if (char == '\r') { skipLf = true; return line.toString() }
            if (char == '\n') return line.toString()
            if (line.length >= maxLine) throw ProviderException("服务返回内容过大")
            line.append(char)
        }
    }
}
