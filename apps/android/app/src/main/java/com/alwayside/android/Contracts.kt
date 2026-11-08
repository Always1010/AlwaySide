package com.alwayside.android

internal data class AppConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-6-luna",
    val transcriptionModel: String = "gpt-transcribe",
    val instructions: String = "你是 AlwaySide 悬浮助手，帮助用户理解内容、解答疑问。请结合上下文，给出简洁清楚的回答。",
    val defaultQuestion: String = "请帮助我读懂这张截图：主要内容是外语时，翻译成中文，按原文在上、译文在下的方式分段对照，不使用表格；主要内容是中文或非文字画面时，简要解释主要信息。仅在影响理解时补充词语、语境或图表含义，忽略无关的界面元素。看不清的内容明确说明。",
    val audioQuestion: String = "请根据这段音频的转写内容回答：外语内容翻译成中文，按原文在上、译文在下的方式分段对照，不使用表格；中文内容简要整理要点。仅在影响理解时补充关键表达或语境，不强行推断转写中不明确的信息。",
    val autoUpdateImage: Boolean = false,
    val maxOutputTokens: Int = 8192,
    val reasoning: String = "none",
    val webSearch: Boolean = false,
    val verbosity: String = "",
    val temperature: Double? = null,
    val topP: Double? = null,
    val transcriptionPrompt: String = "保留原语言和自然标点，按完整语意分段；能明确区分说话人时分行，不确定时不要强行划分。",
    val audioOutput: String = "interpret",
    val textQuestion: String = "请将选中的外语文字翻译成中文，保留原意和语气；有助于理解时补充关键表达或语境。若原文已经是中文，请简要解释其含义。短句直接给出结果。",
    val providerKind: ProviderKind = ProviderKind.CUSTOM,
    val apiProtocol: ApiProtocol = ApiProtocol.RESPONSES,
    val apiVersion: String = "2026-10-21",
    val workspaceId: String = "",
    val connectionId: String = "legacy",
    val connectionName: String = "原有连接",
    val connections: List<ConnectionProfile> = emptyList(),
    val transcriptionConnectionId: String? = "legacy",
    val credentialRevision: Long = 0,
    val continuationMessages: List<Map<String, Any?>> = emptyList(),
    val serviceCatalog: ServiceCatalog? = null,
    val credentialId: String = "",
    val endpointId: String = "",
    val transcriptionLanguage: String = "",
) {
    override fun toString() = "AppConfig(credentials redacted)"
}

internal data class ChatMessage(val role: String, val text: String, val evidence: String = "", val searchSuggestions: String = "")
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
    val favoriteAt: Long = 0,
    val historyEnteredAt: Long = 0,
    val tags: List<String> = emptyList(),
    val customTitle: String = "",
) {
    val isFavorite: Boolean get() = favoriteAt > 0
}
