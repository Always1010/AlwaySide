package com.alwayside.android

internal data class AppConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val transcriptionModel: String = "gpt-transcribe",
    val instructions: String = "你是问答助手，帮助用户翻译或解释屏幕上的内容。",
    val defaultQuestion: String = "请优先将内容翻译成中文；有必要时再简要解释。",
    val audioQuestion: String = "请将这段音频的内容翻译成中文，并按需要解释语境。",
    val autoUpdateImage: Boolean = false,
    val maxOutputTokens: Int = 4096,
    val reasoning: String = "",
    val webSearch: Boolean = false,
) {
    override fun toString() = "AppConfig(credentials redacted)"
}

internal data class ChatMessage(val role: String, val text: String, val evidence: String = "")
internal data class StoredConversation(
    val id: String,
    val updatedAt: Long,
    val title: String,
    val messages: List<ChatMessage>,
    val evidence: String,
    val transcript: String = "",
)
