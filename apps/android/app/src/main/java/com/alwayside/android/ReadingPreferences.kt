package com.alwayside.android

import android.content.Context

/** No credentials: the chosen key stays in the existing encrypted settings store. */
internal class ReadingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("point_reading", Context.MODE_PRIVATE)
    fun voice() = ReadingVoice(prefs.getString("connection", "").orEmpty(),
        prefs.getString("model", "").orEmpty(), prefs.getString("voice", "alloy").orEmpty())
    fun credentialId() = prefs.getString("credential", "").orEmpty()
    fun select(credential: ServiceCredential, endpoint: ServiceEndpoint) {
        check(prefs.edit().putString("credential", credential.id)
            .putString("connection", readingDigest(credential.id + "\u0000" + endpoint.baseUrl)).commit()) { "无法保存朗读服务" }
    }
    fun save(field: String, value: String) {
        require(field in setOf("model", "voice"))
        require(value.isNotBlank() && value.length <= 200 && value.none { it.isWhitespace() || it.isISOControl() }) { "请输入有效的模型或音色 ID" }
        check(prefs.edit().putString(field, value.trim()).commit()) { "无法保存朗读设置" }
    }
    fun request(context: Context): AppConfig {
        val settings = SettingsStore(context).load()
        val catalog = settings.serviceCatalog ?: error("请先在 API 管理中添加朗读服务的 Key")
        val credential = catalog.credentials.firstOrNull { it.id == credentialId() }
            ?: error("请到实验室选择朗读服务")
        val endpoint = catalog.endpoints.firstOrNull { it.id in credential.endpointIds }
            ?: error("朗读服务已删除，请重新选择")
        require(endpoint.answerProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)) { "请选择 Bearer 认证的兼容语音合成服务" }
        require(!credential.unreadable && credential.value.isNotBlank()) { "朗读 Key 不可用，请检查 API 管理" }
        require(voice().connection == readingDigest(credential.id + "\u0000" + endpoint.baseUrl)) { "API 地址已变更，请重新选择朗读服务" }
        require(voice().model.isNotBlank()) { "请到实验室填写朗读模型" }
        return AppConfig(baseUrl = endpoint.baseUrl, apiKey = credential.value, apiProtocol = ApiProtocol.CHAT_COMPLETIONS,
            model = voice().model, connectionId = credential.id, credentialRevision = credential.revision)
    }
}
