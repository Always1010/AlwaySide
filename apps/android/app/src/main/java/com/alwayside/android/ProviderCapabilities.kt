package com.alwayside.android

/** Known limitations are enforced; unknown model IDs remain usable and are never labelled verified. */
internal object ProviderCapabilities {
    fun search(config: AppConfig): Boolean = when {
        config.apiProtocol == ApiProtocol.RESPONSES && config.providerKind == ProviderKind.DEEPSEEK -> config.model == "deepseek-v4-pro"
        config.apiProtocol == ApiProtocol.RESPONSES -> true
        config.apiProtocol == ApiProtocol.GEMINI -> config.model.removePrefix("models/").startsWith("gemini-")
        config.apiProtocol == ApiProtocol.CLAUDE -> true
        config.apiProtocol == ApiProtocol.CHAT_COMPLETIONS && config.providerKind == ProviderKind.GEMINI -> false
        else -> true
    }
    fun verbosity(config: AppConfig) = config.providerKind in setOf(ProviderKind.OPENAI, ProviderKind.CUSTOM) &&
        config.apiProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)
    fun sampling(config: AppConfig) = !(config.apiProtocol == ApiProtocol.CLAUDE && config.apiVersion >= "2026-10-21")
    fun validate(config: AppConfig, image: Boolean) {
        if (config.webSearch && !search(config)) throw ProviderException("当前服务、协议或模型不支持联网搜索，请关闭搜索或更换模型")
        if (config.verbosity.isNotBlank() && !verbosity(config)) throw ProviderException("当前服务不支持回答详细程度参数，请设为不设置")
        if ((config.temperature != null || config.topP != null) && !sampling(config)) throw ProviderException("当前 Claude API 版本不支持采样参数，请清空")
        if (image && config.providerKind == ProviderKind.DEEPSEEK && config.model == "deepseek-v4-pro")
            throw ProviderException("此 DeepSeek 模型不支持图片，请选择支持视觉的模型，例如 deepseek-flash")
    }
    fun label(config: AppConfig) = "${config.connectionName} · ${config.model.ifBlank { "未选择模型" }}"
}
