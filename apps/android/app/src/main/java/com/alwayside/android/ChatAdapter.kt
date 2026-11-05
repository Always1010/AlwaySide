package com.alwayside.android

import java.net.URI

internal data class WireRequest(val route: String, val body: Map<String, Any?>, val query: Map<String, String> = emptyMap())

internal interface ChatAdapter {
    fun request(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?): WireRequest
    fun decoder(config: AppConfig): AnswerDecoder
}

/** Pure map-based codec boundary: tests need no Android JSON runtime or paid API. */
internal interface AnswerDecoder {
    val complete: Boolean
    fun accept(eventName: String, data: Map<String, Any?>, onDelta: (String) -> Unit)
    fun finish(onDelta: (String) -> Unit): String
}

internal class ProviderPausedException(val messages: List<Map<String, Any?>>) : Exception("搜索尚未完成，请继续生成")

@Suppress("UNCHECKED_CAST")
internal fun Any?.wireObject(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
internal fun Any?.wireList(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: ""
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].wireObject()
internal fun Map<String, Any?>.items(key: String): List<Any?> = this[key].wireList()

internal fun boundedHistory(history: List<ChatMessage>): List<ChatMessage> {
    val selected = ArrayList<ChatMessage>(); var budget = 65_536
    for (message in history.asReversed()) {
        if (message.role !in setOf("user", "assistant") || message.text.isBlank()) continue
        if (selected.size >= 24 || message.text.length > budget) break
        selected.add(message); budget -= message.text.length
    }
    return selected.asReversed()
}

internal class AnswerText {
    private val answer = StringBuilder()
    private val citations = linkedMapOf<String, String>()
    val value: String get() = answer.toString()
    fun append(text: String, emit: (String) -> Unit) {
        if (answer.length + text.length > 256 * 1024) throw ProviderException("回答过长，请缩小问题范围")
        answer.append(text); if (text.isNotEmpty()) emit(text)
    }
    fun citation(url: String, title: String) {
        if (citations.size >= 10 || url.length > 2048) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return
        citations[uri.toASCIIString().replace("(", "%28").replace(")", "%29")] =
            title.ifBlank { uri.host }.take(160).replace(Regex("[\\[\\]\\r\\n\\\\]"), " ")
    }
    fun finish(emit: (String) -> Unit): String {
        if (answer.isBlank()) throw ProviderException("服务没有返回文字回答")
        if (citations.isNotEmpty()) append("\n\n来源：\n" + citations.entries.joinToString("\n") { (url, title) -> "- [$title]($url)" }, emit)
        return value
    }
}
