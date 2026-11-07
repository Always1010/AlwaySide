package com.alwayside.android

import android.content.Context

/** No credentials: the chosen key stays in the existing encrypted settings store. */
internal class ReadingPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("point_reading", Context.MODE_PRIVATE)
    fun voice(): ReadingVoice {
        return ReadingPresets.resolve(prefs.getString("connection", "").orEmpty(),
            prefs.getString("model", null), prefs.getString("voice", null), kind())
    }
    fun kind(): ProviderKind {
        prefs.getString("providerKind", null)?.let { name -> runCatching { ProviderKind.valueOf(name) }.getOrNull()?.let { return it } }
        val catalog = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
        val key = catalog?.credentials?.firstOrNull { it.id == credentialId() }
        return catalog?.providers?.firstOrNull { it.id == key?.providerId }?.kind ?: ProviderKind.CUSTOM
    }
    fun credentialId() = prefs.getString("credential", "").orEmpty()
    fun select(credential: ServiceCredential, endpoint: ServiceEndpoint, kind: ProviderKind? = null) {
        val providerKind = kind ?: SettingsStore(context).load().serviceCatalog?.providers
            ?.firstOrNull { it.id == credential.providerId }?.kind ?: ProviderKind.CUSTOM
        check(prefs.edit().putString("credential", credential.id)
            .putString("providerKind", providerKind.name)
            .putString("connection", readingDigest(credential.id + "\u0000" + endpoint.baseUrl)).commit()) { "无法保存朗读服务" }
    }
    fun save(field: String, value: String) {
        require(field in setOf("model", "voice"))
        require(value.isNotBlank() && value.length <= 200 && value.none { it.isWhitespace() || it.isISOControl() }) { "请输入有效的模型或音色 ID" }
        val edit = prefs.edit().putString(field, value.trim())
        if (field == "model") edit.putString("voice", ReadingPresets.compatibleVoice(value.trim(), voice().voice))
        check(edit.commit()) { "无法保存朗读设置" }
    }
    fun request(context: Context): AppConfig {
        return connection(context).also { require(it.model.isNotBlank()) { "请到实验室选择朗读模型" } }
    }
    fun connection(context: Context): AppConfig {
        val settings = SettingsStore(context).load()
        val catalog = settings.serviceCatalog ?: error("请先在 API 管理中添加朗读服务的 Key")
        val credential = catalog.credentials.firstOrNull { it.id == credentialId() }
            ?: error("请到实验室选择朗读服务")
        val endpoint = catalog.endpoints.firstOrNull { it.id in credential.endpointIds }
            ?: error("朗读服务已删除，请重新选择")
        require(endpoint.answerProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)) { "请选择 Bearer 认证的兼容语音合成服务" }
        require(!credential.unreadable && credential.value.isNotBlank()) { "朗读 Key 不可用，请检查 API 管理" }
        require(voice().connection == readingDigest(credential.id + "\u0000" + endpoint.baseUrl)) { "API 地址已变更，请重新选择朗读服务" }
        return AppConfig(baseUrl = endpoint.baseUrl, apiKey = credential.value, apiProtocol = ApiProtocol.CHAT_COMPLETIONS,
            model = voice().model, connectionId = credential.id, credentialRevision = credential.revision,
            serviceCatalog = catalog, credentialId = credential.id, endpointId = endpoint.id,
            apiVersion = endpoint.apiVersion, workspaceId = endpoint.workspaceId)
    }
}
