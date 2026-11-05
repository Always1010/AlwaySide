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

    /** Install migration checks presence only; no credential is read or decrypted. */
    fun hasSavedConfiguration(): Boolean = prefs.contains("config") || prefs.contains("credential")

    /** Preparation reads presence and public fields only, never decrypts a credential or connects. */
    fun connectionPreparation(): ConnectionPreparation = synchronized(lock) {
        val data = JSONObject(prefs.getString("config", "{}") ?: "{}")
        if (data.optInt("schemaVersion", 1) == 3) {
            val catalog = ServiceCatalogCodec.decode(WireJson.decode(data.getJSONObject("services").toString())) { id ->
                (if (prefs.contains("credential.$id")) "present" else "") to false
            }
            val active = catalog.profiles.firstOrNull { it.id == catalog.activeAnswerId }
            val endpoint = catalog.endpoints.firstOrNull { it.id == active?.endpointId }
            val key = catalog.credentials.firstOrNull { it.id == active?.credentialId }
            ConnectionPreparation(endpoint != null, key != null && key.value.isNotBlank() && endpoint?.id in key.endpointIds,
                active?.model?.isNotBlank() == true,
                serviceResourcesReady = catalog.credentials.any { credential -> credential.value.isNotBlank() &&
                    catalog.endpoints.any { it.providerId == credential.providerId && it.id in credential.endpointIds } },
                transcriptionConfigured = catalog.activeTranscriptionId != null)
        } else if (data.optInt("schemaVersion", 1) == 2) {
            val active = data.optString("activeConnectionId")
            val profiles = data.optJSONArray("connections")
            val profile = (0 until (profiles?.length() ?: 0)).mapNotNull { profiles?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == active }
            ConnectionPreparation(profile?.optString("baseUrl").orEmpty().isNotBlank(),
                prefs.contains("credential.$active"), profile?.optString("model").orEmpty().isNotBlank())
        } else ConnectionPreparation(data.optString("baseUrl").isNotBlank(), prefs.contains("credential"), data.optString("model").isNotBlank())
    }

    fun load(): AppConfig = synchronized(lock) {
        val data = JSONObject(prefs.getString("config", "{}") ?: "{}")
        require(data.optInt("schemaVersion", 1) in 1..3) { "不支持的配置版本" }
        val defaults = AppConfig()
        val legacy = AppConfig(
            apiKey = readCredential("credential").first,
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
        if (data.optInt("schemaVersion", 1) == 3) {
            val catalog = ServiceCatalogCodec.decode(WireJson.decode(data.getJSONObject("services").toString())) { id ->
                readCredential("credential.$id")
            }
            return@synchronized catalog.attach(legacy)
        }
        if (!prefs.contains("config") && !prefs.contains("credential")) return@synchronized ServiceCatalog().attach(legacy)
        if (data.optInt("schemaVersion", 1) != 2) {
            val old = ConnectionConfigCodec.migrateLegacy(legacy)
            val catalog = ServiceCatalog.migrate(old)
            return@synchronized catalog.copy(credentials = catalog.credentials.map { it.copy(unreadable = readCredential("credential").second) }).attach(old)
        }
        val records = data.getJSONArray("connections")
        require(records.length() in 1..20) { "连接数量无效" }
        val profiles = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            val id = item.getString("id")
            ConnectionConfigCodec.decode(WireJson.decode(item.toString()), readCredential("credential.$id").first)
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "连接 ID 重复" }
        val active = profiles.firstOrNull { it.id == data.getString("activeConnectionId") } ?: error("当前连接不存在")
        val old = active.applyTo(legacy).copy(connections = profiles,
            transcriptionConnectionId = data.optionalPresetString("transcriptionConnectionId"))
        val migrated = ServiceCatalog.migrate(old)
        val safe = migrated.copy(credentials = migrated.credentials.map { credential ->
            val originalId = credential.id.removePrefix("key-")
            credential.copy(unreadable = readCredential("credential.$originalId").second)
        })
        safe.attach(old)
    }

    fun save(config: AppConfig) = synchronized(lock) {
        validate(config)
        val catalog = captureCompatibility(config)
        saveState(config, catalog)
    }

    fun saveCatalog(catalog: ServiceCatalog) = synchronized(lock) { saveState(load(), catalog) }

    /** Editors compare against the latest persisted snapshot; stale callbacks never overwrite newer changes. */
    fun updateCatalog(expected: ServiceCatalog, transform: (ServiceCatalog) -> ServiceCatalog) = synchronized(lock) {
        val current = load()
        require(current.serviceCatalog == expected) { "配置已改变，请重新打开后再修改" }
        val next = transform(expected)
        if (next != expected) saveState(current, next)
    }

    private fun readCredential(slot: String): Pair<String, Boolean> {
        val value = prefs.getString(slot, null) ?: return "" to false
        return runCatching { decrypt(value) to false }.getOrElse { "" to true }
    }

    private fun captureCompatibility(config: AppConfig): ServiceCatalog {
        val catalog = config.serviceCatalog ?: return ServiceCatalog.migrate(config)
        val active = catalog.profiles.firstOrNull { it.id == catalog.activeAnswerId } ?: return catalog
        val endpoint = catalog.endpoints.firstOrNull { it.id == active.endpointId }
        val key = catalog.credentials.firstOrNull { it.id == active.credentialId }
        return catalog.copy(
            profiles = catalog.profiles.map { if (it.id == active.id) it.copy(model = config.model,
                options = GenerationOptions.capture(config)) else it },
            endpoints = catalog.endpoints.map { if (it.id == endpoint?.id) it.copy(baseUrl = config.baseUrl,
                answerProtocol = config.apiProtocol, apiVersion = config.apiVersion, workspaceId = config.workspaceId) else it },
            credentials = catalog.credentials.map { if (it.id == key?.id && !key.unreadable)
                it.copy(value = config.apiKey, revision = config.credentialRevision) else it })
    }

    private fun saveState(config: AppConfig, catalog: ServiceCatalog) {
        catalog.validate()
        // Stage every ciphertext first. Preserve an unreadable slot until explicitly replaced/deleted.
        val encrypted = catalog.credentials.associate { credential ->
            val existingSlot = "credential.${credential.id}"
            val previous = prefs.getString(existingSlot, null)
                ?: if (credential.id.startsWith("key-")) prefs.getString("credential.${credential.id.removePrefix("key-")}", null)
                    ?: if (credential.id == "key-legacy") prefs.getString("credential", null) else null else null
            credential.id to if (credential.unreadable) previous.also { check(it != null) { "无法保留损坏的密钥，请先删除此密钥" } }
                else credential.value.takeIf(String::isNotBlank)?.let(::encrypt)
        }
        val data = JSONObject().apply {
            put("schemaVersion", 3)
            put("services", JSONObject(WireJson.encode(ServiceCatalogCodec.encode(catalog))))
            put("instructions", config.instructions)
            put("defaultQuestion", config.defaultQuestion)
            put("audioQuestion", config.audioQuestion)
            put("autoUpdateImage", config.autoUpdateImage)
            put("transcriptionPrompt", config.transcriptionPrompt)
            put("audioOutput", config.audioOutput)
            put("textQuestion", config.textQuestion)
        }
        val edit = prefs.edit().putString("config", data.toString())
        // Config, credential slots and removal of the legacy slot are one atomic preference commit.
        prefs.all.keys.filter { it == "credential" || it.startsWith("credential.") }.forEach { edit.remove(it) }
        encrypted.forEach { (id, value) -> if (value != null) edit.putString("credential.$id", value) }
        check(edit.commit()) { "Settings write failed" }
        ActiveServiceRequests.revoke(catalog)
    }

    fun clearCredential() = synchronized(lock) {
        val data = JSONObject(prefs.getString("config", "{}") ?: "{}")
        if (data.optInt("schemaVersion", 1) == 3) {
            val current = load()
            val catalog = current.serviceCatalog!!
            val key = catalog.profiles.firstOrNull { it.id == catalog.activeAnswerId }?.credentialId
            saveState(current, catalog.removeCredential(key ?: return@synchronized))
        } else if (data.optInt("schemaVersion", 1) == 2) {
            val id = data.getString("activeConnectionId")
            val profiles = data.getJSONArray("connections")
            for (i in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(i)
                if (profile.optString("id") == id) profile.put("credentialRevision", profile.optLong("credentialRevision") + 1)
            }
            check(prefs.edit().putString("config", data.toString()).remove("credential.$id").commit())
        } else check(prefs.edit().remove("credential").commit())
        // Other connections may still use the encryption key; never delete it here.
    }

    fun resetConfiguration() = synchronized(lock) {
        val edit = prefs.edit().remove("config").remove("credential")
        prefs.all.keys.filter { it.startsWith("credential.") }.forEach { edit.remove(it) }
        check(edit.commit()) { "Settings reset failed" }
        ActiveServiceRequests.revoke(ServiceCatalog())
        deleteCredentialKey()
    }

    fun presets(): Map<String, PromptPreset> = synchronized(lock) {
        val data = JSONObject(prefs.getString("presets", "{}") ?: "{}")
        data.keys().asSequence().take(20).associateWith { name ->
            val value = data.getJSONObject(name)
            PromptPreset(value.getString("instructions"), value.getString("defaultQuestion"), value.getString("audioQuestion"),
                value.optionalPresetString("textQuestion"), value.optionalPresetString("transcriptionPrompt"),
                value.optionalPresetString("audioOutput"))
        }.toSortedMap()
    }

    fun savePreset(name: String, preset: PromptPreset) = synchronized(lock) {
        require(name.isNotBlank() && name.length <= 60) { "Preset name is invalid" }
        require(listOfNotNull(preset.instructions, preset.defaultQuestion, preset.audioQuestion, preset.textQuestion,
            preset.transcriptionPrompt).all { it.length <= 32000 })
        require(preset.audioOutput == null || preset.audioOutput in setOf("transcript", "translate", "interpret"))
        val data = JSONObject(prefs.getString("presets", "{}") ?: "{}")
        require(data.has(name) || data.length() < 20) { "At most 20 presets" }
        data.put(name, JSONObject().put("instructions", preset.instructions)
            .put("defaultQuestion", preset.defaultQuestion).put("audioQuestion", preset.audioQuestion)
            .put("textQuestion", preset.textQuestion).put("transcriptionPrompt", preset.transcriptionPrompt)
            .put("audioOutput", preset.audioOutput))
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
            config.serviceCatalog?.let { catalog ->
                catalog.validate()
                ProviderProtocol.validateOptions(config)
                require(listOf(config.instructions, config.defaultQuestion, config.audioQuestion, config.transcriptionPrompt, config.textQuestion).all { it.length <= 32000 }) { "单项提示词不能超过 32000 字符" }
                return
            }
            require(runCatching { ProviderProtocol.endpoint(config.baseUrl, "responses") }.isSuccess) {
                "请输入 HTTPS 基础地址，不含用户名、查询参数或片段"
            }
            require(config.model.length <= 200 && config.transcriptionModel.length <= 200) { "模型名称过长" }
            require(config.connectionName.length in 1..80) { "连接名称请填写 1 至 80 个字符" }
            require(Regex("[A-Za-z0-9-]{1,80}").matches(config.connectionId)) { "连接 ID 无效" }
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(config.apiVersion)) { "API 版本请使用 YYYY-MM-DD" }
            require(config.workspaceId.length <= 200 && config.workspaceId.none { it.code < 33 || it.code > 126 }) { "Workspace ID 无效" }
            ProviderProtocol.validateOptions(config)
            require(listOf(config.instructions, config.defaultQuestion, config.audioQuestion, config.transcriptionPrompt, config.textQuestion).all { it.length <= 32000 }) { "单项提示词不能超过 32000 字符" }
            require(config.apiKey.length <= 4096 && config.apiKey.none { it.code < 33 || it.code > 126 }) { "API Key 请使用不含空白的有效凭证，最多 4096 字符" }
        }
    }
}

private fun JSONObject.optionalPresetString(name: String): String? =
    if (isNull(name)) null else getString(name)
