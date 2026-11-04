package com.alwayside.android

internal data class AppConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-6-luna",
    val transcriptionModel: String = "gpt-transcribe",
    val instructions: String = "你是问答助手，帮助用户翻译或解释屏幕上的内容。",
    val defaultQuestion: String = "请优先将内容翻译成中文；有必要时再简要解释。",
    val audioQuestion: String = "请将这段音频的内容翻译成中文，并按需要解释语境。",
    val autoUpdateImage: Boolean = false,
    val maxOutputTokens: Int = 8192,
    val reasoning: String = "none",
    val webSearch: Boolean = false,
    val verbosity: String = "",
    val temperature: Double? = null,
    val topP: Double? = null,
    val transcriptionPrompt: String = "",
    val audioOutput: String = "interpret",
    val textQuestion: String = "请将选中的文字翻译成中文，并按需要解释语境。",
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
    val kind: String = "text",
    val status: String = "已完成",
    val sourceText: String = "",
)
