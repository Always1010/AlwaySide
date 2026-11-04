package com.alwayside.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never enter the unencrypted config or prompt presets. Errors fail closed. */
internal class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    fun load(): AppConfig = synchronized(lock) {
        val data = JSONObject(prefs.getString("config", "{}") ?: "{}")
        val defaults = AppConfig()
        AppConfig(
            apiKey = prefs.getString("credential", null)?.let(::decrypt).orEmpty(),
            baseUrl = data.optString("baseUrl", defaults.baseUrl),
            model = data.optString("model", defaults.model),
            transcriptionModel = data.optString("transcriptionModel", defaults.transcriptionModel),
            instructions = data.optString("instructions", defaults.instructions),
            defaultQuestion = data.optString("defaultQuestion", defaults.defaultQuestion),
            audioQuestion = data.optString("audioQuestion", defaults.audioQuestion),
            autoUpdateImage = data.optBoolean("autoUpdateImage", defaults.autoUpdateImage),
            maxOutputTokens = data.optInt("maxOutputTokens", defaults.maxOutputTokens),
            reasoning = data.optString("reasoning", defaults.reasoning),
            webSearch = data.optBoolean("webSearch", defaults.webSearch),
            verbosity = data.optString("verbosity", defaults.verbosity),
            temperature = if (data.isNull("temperature")) null else data.getDouble("temperature"),
            topP = if (data.isNull("topP")) null else data.getDouble("topP"),
            transcriptionPrompt = data.optString("transcriptionPrompt", defaults.transcriptionPrompt),
            audioOutput = data.optString("audioOutput", defaults.audioOutput),
            textQuestion = data.optString("textQuestion", defaults.textQuestion),
        )
    }

    fun save(config: AppConfig) = synchronized(lock) {
        validate(config)
        // Encrypt before touching preferences: a KeyStore failure must not write plaintext.
        val encrypted = config.apiKey.takeIf { it.isNotBlank() }?.let(::encrypt)
        val data = JSONObject().apply {
            put("baseUrl", config.baseUrl.trim().trimEnd('/'))
            put("model", config.model.trim())
            put("transcriptionModel", config.transcriptionModel.trim())
            put("instructions", config.instructions)
            put("defaultQuestion", config.defaultQuestion)
            put("audioQuestion", config.audioQuestion)
            put("autoUpdateImage", config.autoUpdateImage)
            put("maxOutputTokens", config.maxOutputTokens)
            put("reasoning", config.reasoning.trim())
            put("webSearch", config.webSearch)
            put("verbosity", config.verbosity)
            put("temperature", config.temperature)
            put("topP", config.topP)
            put("transcriptionPrompt", config.transcriptionPrompt)
            put("audioOutput", config.audioOutput)
            put("textQuestion", config.textQuestion)
        }
        val edit = prefs.edit().putString("config", data.toString())
        if (encrypted == null) edit.remove("credential") else edit.putString("credential", encrypted)
        check(edit.commit()) { "Settings write failed" }
    }

    fun clearCredential() = synchronized(lock) {
        check(prefs.edit().remove("credential").commit()) { "Credential removal failed" }
        deleteCredentialKey()
    }

    fun resetConfiguration() = synchronized(lock) {
        check(prefs.edit().remove("config").remove("credential").commit()) { "Settings reset failed" }
        deleteCredentialKey()
    }

    fun presets(): Map<String, PromptPreset> = synchronized(lock) {
        val data = JSONObject(prefs.getString("presets", "{}") ?: "{}")
        data.keys().asSequence().take(20).associateWith { name ->
            val value = data.getJSONObject(name)
            PromptPreset(value.getString("instructions"), value.getString("defaultQuestion"), value.getString("audioQuestion"))
        }.toSortedMap()
    }

    fun savePreset(name: String, preset: PromptPreset) = synchronized(lock) {
        require(name.isNotBlank() && name.length <= 60) { "Preset name is invalid" }
        require(listOf(preset.instructions, preset.defaultQuestion, preset.audioQuestion).all { it.length <= 32000 })
        val data = JSONObject(prefs.getString("presets", "{}") ?: "{}")
        require(data.has(name) || data.length() < 20) { "At most 20 presets" }
        data.put(name, JSONObject().put("instructions", preset.instructions)
            .put("defaultQuestion", preset.defaultQuestion).put("audioQuestion", preset.audioQuestion))
        check(prefs.edit().putString("presets", data.toString()).commit()) { "Preset write failed" }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun deleteCredentialKey() {
        // Removing an invalidated alias lets the next explicit save create a fresh key.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try { "1.${encode(cipher.iv)}.${encode(cipher.doFinal(bytes))}" } finally { bytes.fill(0) }
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.')
        check(parts.size == 3 && parts[0] == "1") { "Unknown credential format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        val bytes = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "alwayside_api_key_v1"
        fun validate(config: AppConfig) {
            require(runCatching { ProviderProtocol.endpoint(config.baseUrl, "responses") }.isSuccess) {
                "请输入以 /v1 结尾的 HTTPS Base URL，不含用户名、查询参数或片段"
            }
            require(config.model.isNotBlank() && config.transcriptionModel.isNotBlank()) { "模型名称不能为空" }
            require(config.model.length <= 200 && config.transcriptionModel.length <= 200) { "模型名称过长" }
            ProviderProtocol.validateOptions(config)
            require(listOf(config.instructions, config.defaultQuestion, config.audioQuestion, config.transcriptionPrompt, config.textQuestion).all { it.length <= 32000 }) { "单项提示词不能超过 32000 字符" }
            require(config.apiKey.length <= 8192 && config.apiKey.none { it.isWhitespace() }) { "API Key 不能包含空白字符" }
        }
    }
}

internal data class PromptPreset(val instructions: String, val defaultQuestion: String, val audioQuestion: String)
