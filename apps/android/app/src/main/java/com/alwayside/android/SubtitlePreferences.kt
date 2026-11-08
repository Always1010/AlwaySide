package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/** Only references and subtitle preferences live here; Key values stay in SettingsStore. */
internal class SubtitlePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("audio_subtitles", Context.MODE_PRIVATE)
    private val presets by lazy {
        runCatching {
            context.assets.open(ModelPresets.ASSET).bufferedReader(Charsets.UTF_8).use {
                ModelPresets.decode(WireJson.decode(it.readText()))
            }
        }.getOrDefault(ModelPresets.EMPTY)
    }

    fun enabled(method: SubtitleMethod) = prefs.getBoolean(enabledKey(method), false)
    fun anyEnabled() = SubtitleMethod.entries.any(::enabled)
    fun setEnabled(method: SubtitleMethod, enabled: Boolean) = save(enabledKey(method), enabled)
    fun batchedTranslation() = prefs.getBoolean(KEY_BATCHED_TRANSLATION, false)
    fun saveBatchedTranslation(enabled: Boolean) = save(KEY_BATCHED_TRANSLATION, enabled)
    fun questionSubtitles() = prefs.getBoolean(KEY_QUESTION_SUBTITLES, false)
    fun saveQuestionSubtitles(enabled: Boolean) = save(KEY_QUESTION_SUBTITLES, enabled)
    fun credentialId() = prefs.getString(KEY_CREDENTIAL, "").orEmpty()
    fun translationModel() = prefs.getString(KEY_MODEL, null) ?: recommendations().defaultModelId
    fun recommendations() = presets.recommendations(ProviderKind.OPENAI, ModelPurpose.ANSWER,
        ServiceEndpoint("subtitle", "openai", "OpenAI", ProviderKind.OPENAI.baseUrl, ApiProtocol.RESPONSES))

    fun selectCredential(id: String) {
        SubtitleConfiguration.connection(catalog(), id)
        save(KEY_CREDENTIAL, id)
    }

    fun saveTranslationModel(model: String) {
        SubtitleConfiguration.validateModel(model)
        save(KEY_MODEL, model)
    }

    fun display() = SubtitleDisplay.entries.firstOrNull { it.name == prefs.getString(KEY_DISPLAY, null) }
        ?: SubtitleDisplay.BILINGUAL
    fun saveDisplay(value: SubtitleDisplay) = save(KEY_DISPLAY, value.name)
    fun request(): AppConfig = SubtitleConfiguration.request(catalog(), credentialId(), translationModel())
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
    private fun catalog() = SettingsStore(context).load().serviceCatalog
        ?: error("请先在 API 管理中添加 OpenAI Key")

    private fun save(key: String, value: Any) = synchronized(prefs) {
        val previous = prefs.all[key]
        fun write(value: Any?): Boolean = prefs.edit().apply {
            when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                else -> error("字幕设置类型无效")
            }
        }.commit()
        if (!write(value)) {
            // commit() can update the memory view before disk failure; restore the previous choice too.
            write(previous)
            error("无法保存字幕设置，请重试")
        }
    }

    companion object {
        const val KEY_CREDENTIAL = "credential"
        const val KEY_MODEL = "translationModel"
        const val KEY_DISPLAY = "display"
        const val KEY_BATCHED_TRANSLATION = "batchedTranslation"
        const val KEY_QUESTION_SUBTITLES = "questionSubtitles"
        fun enabledKey(method: SubtitleMethod) = "enabled.${method.id}"
    }
}

/** Rejects custom and edited endpoints before an existing encrypted Key can be reused. */
internal object SubtitleConfiguration {
    fun official(endpoint: ServiceEndpoint): Boolean {
        val uri = runCatching { URI(endpoint.baseUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host.equals("api.openai.com", ignoreCase = true) &&
            uri.port in setOf(-1, 443) && uri.rawPath in setOf("/v1", "/v1/") &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            endpoint.answerProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)
    }

    fun connection(catalog: ServiceCatalog, id: String): Pair<ServiceCredential, ServiceEndpoint> {
        val credential = catalog.credentials.firstOrNull { it.id == id }
            ?: error("请到实验室选择音频字幕的 OpenAI Key")
        require(catalog.providers.any { it.id == credential.providerId && it.kind == ProviderKind.OPENAI }) {
            "音频字幕仅支持 OpenAI 官方服务，请重新选择 Key"
        }
        require(!credential.unreadable && credential.value.isNotBlank()) { "字幕 Key 不可用，请检查 API 管理" }
        val endpoint = catalog.endpoints.firstOrNull {
            it.id in credential.endpointIds && it.providerId == credential.providerId && official(it)
        } ?: error("字幕仅支持 https://api.openai.com/v1，请检查 API 管理后重新选择")
        return credential to endpoint
    }

    fun validateModel(model: String) {
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}").matches(model)) { "请输入有效的翻译模型 ID" }
    }

    fun request(catalog: ServiceCatalog, id: String, model: String): AppConfig {
        val (credential, endpoint) = connection(catalog, id)
        validateModel(model)
        return AppConfig(apiKey = credential.value, baseUrl = ProviderKind.OPENAI.baseUrl,
            providerKind = ProviderKind.OPENAI, apiProtocol = endpoint.answerProtocol, model = model,
            transcriptionModel = "", instructions = "", defaultQuestion = "", audioQuestion = "", textQuestion = "",
            maxOutputTokens = 0, reasoning = "", connectionId = credential.id, connectionName = "音频字幕",
            credentialRevision = credential.revision, credentialId = credential.id, endpointId = endpoint.id,
            transcriptionConnectionId = null, apiVersion = endpoint.apiVersion)
    }
}
