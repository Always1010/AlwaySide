package com.alwayside.android

import java.net.URI
import java.util.UUID

internal enum class ApiProtocol(val title: String) {
    RESPONSES("Responses"), CHAT_COMPLETIONS("Chat Completions"), GEMINI("Gemini 原生"), CLAUDE("Claude Messages")
}

internal enum class ProviderKind(val title: String, val protocol: ApiProtocol, val baseUrl: String) {
    OPENAI("OpenAI", ApiProtocol.RESPONSES, "https://api.openai.com/v1"),
    DEEPSEEK("DeepSeek", ApiProtocol.RESPONSES, "https://api.deepseek.com"),
    QWEN("Qwen", ApiProtocol.CHAT_COMPLETIONS, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    GEMINI("Gemini", ApiProtocol.GEMINI, "https://generativelanguage.googleapis.com/v1beta"),
    CLAUDE("Claude", ApiProtocol.CLAUDE, "https://api.anthropic.com/v1"),
    CUSTOM("自定义兼容服务", ApiProtocol.RESPONSES, "https://api.openai.com/v1");

    companion object {
        fun infer(address: String) = entries.firstOrNull { it != CUSTOM && address.trimEnd('/') == it.baseUrl } ?: CUSTOM
    }
}

/** Credentials exist only in memory here; serialization is owned by SettingsStore. */
internal data class ConnectionProfile(
    val id: String, val name: String, val providerKind: ProviderKind, val apiProtocol: ApiProtocol,
    val baseUrl: String, val apiKey: String = "", val model: String = "", val transcriptionModel: String = "",
    val maxOutputTokens: Int = 0, val reasoning: String = "", val verbosity: String = "",
    val temperature: Double? = null, val topP: Double? = null, val webSearch: Boolean = false,
    val apiVersion: String = "2026-10-21", val workspaceId: String = "", val credentialRevision: Long = 0,
) {
    override fun toString() = "ConnectionProfile(id=$id, credentials redacted)"
    fun applyTo(config: AppConfig) = config.copy(connectionId = id, connectionName = name, providerKind = providerKind,
        apiProtocol = apiProtocol, baseUrl = baseUrl, apiKey = apiKey, model = model, transcriptionModel = transcriptionModel,
        maxOutputTokens = maxOutputTokens, reasoning = reasoning, verbosity = verbosity, temperature = temperature,
        topP = topP, webSearch = webSearch, apiVersion = apiVersion, workspaceId = workspaceId,
        credentialRevision = credentialRevision, continuationMessages = emptyList())

    companion object {
        fun capture(c: AppConfig) = ConnectionProfile(c.connectionId, c.connectionName, c.providerKind, c.apiProtocol,
            c.baseUrl, c.apiKey, c.model, c.transcriptionModel, c.maxOutputTokens, c.reasoning, c.verbosity,
            c.temperature, c.topP, c.webSearch, c.apiVersion, c.workspaceId, c.credentialRevision)
    }
}

internal object ProviderConnections {
    fun snapshot(config: AppConfig): List<ConnectionProfile> {
        config.serviceCatalog?.let { catalog -> return catalog.profiles.map { ConnectionProfile.capture(catalog.project(config, it.id)) } }
        val active = ConnectionProfile.capture(config)
        return if (config.connections.any { it.id == active.id })
            config.connections.map { if (it.id == active.id) active else it }
        else config.connections + active
    }

    fun select(config: AppConfig, id: String): AppConfig {
        if (id == config.connectionId) return config
        config.serviceCatalog?.let { catalog ->
            require(catalog.profiles.any { it.id == id && it.purpose == ModelPurpose.ANSWER }) { "请选择图文问答配置" }
            return catalog.copy(activeAnswerId = id).attach(config)
        }
        val profiles = snapshot(config)
        return (profiles.firstOrNull { it.id == id } ?: error("连接不存在")).applyTo(config).copy(connections = profiles)
    }

    fun add(config: AppConfig, kind: ProviderKind): AppConfig {
        require(config.serviceCatalog == null) { "请在管理 API 服务中添加密钥，再添加用途卡片" }
        require(snapshot(config).size < 20) { "最多保存 20 个连接" }
        val profile = ConnectionProfile(UUID.randomUUID().toString(), kind.title, kind, kind.protocol, kind.baseUrl)
        return profile.applyTo(config).copy(connections = snapshot(config) + profile)
    }

    fun remove(config: AppConfig, id: String): AppConfig {
        config.serviceCatalog?.let { return it.removeProfile(id).attach(config) }
        val remaining = snapshot(config).filterNot { it.id == id }
        require(remaining.isNotEmpty()) { "请至少保留一个连接，可先移除该连接的密钥" }
        val next = if (id == config.connectionId) remaining.first().applyTo(config) else config
        return next.copy(connections = remaining, transcriptionConnectionId = config.transcriptionConnectionId?.takeUnless { it == id })
    }

    fun transcription(config: AppConfig): AppConfig {
        config.serviceCatalog?.let { catalog ->
            val id = catalog.activeTranscriptionId ?: throw ProviderException("请先启用音频转写配置")
            val profile = catalog.profiles.firstOrNull { it.id == id && it.purpose == ModelPurpose.TRANSCRIPTION }
                ?: throw ProviderException("转写配置已删除，请重新选择")
            val endpoint = catalog.endpoints.firstOrNull { it.id == profile.endpointId }
            require(endpoint?.transcriptionApi == FileTranscriptionApi.OPENAI_COMPATIBLE) { "此接入点未配置兼容文件转写接口" }
            return catalog.project(config, id)
        }
        val id = config.transcriptionConnectionId ?: throw ProviderException("请先选择音频转写连接")
        val connection = snapshot(config).firstOrNull { it.id == id } ?: throw ProviderException("转写连接已删除，请重新选择")
        require(connection.apiProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)) {
            "该连接没有兼容文件转写接口，请选择 OpenAI 或兼容文件转写的连接"
        }
        return connection.applyTo(config).copy(apiProtocol = ApiProtocol.CHAT_COMPLETIONS)
    }

    fun addressChanged(config: AppConfig, address: String): AppConfig {
        val previous = runCatching { URI(config.baseUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull()
        val next = runCatching { URI(address).let { "${it.scheme}://${it.authority}" } }.getOrNull()
        return config.copy(baseUrl = address.trim(), apiKey = if (previous == next) config.apiKey else "",
            credentialRevision = config.credentialRevision + 1)
    }

    fun credentialsCurrent(requestedConfig: AppConfig, current: AppConfig, answer: Boolean, audio: Boolean): Boolean {
        current.serviceCatalog?.let { catalog ->
            val required = buildList {
                if (answer) add(requestedConfig)
                if (audio) add(transcription(requestedConfig))
            }
            return required.all { credentialCurrent(it, catalog) }
        }
        val saved = snapshot(current)
        val required = buildList {
            if (answer) add(ConnectionProfile.capture(requestedConfig))
            if (audio) add(ConnectionProfile.capture(transcription(requestedConfig)))
        }
        return required.all { requested -> saved.any { it.id == requested.id && it.apiKey.isNotBlank() &&
            it.apiKey == requested.apiKey && it.credentialRevision == requested.credentialRevision && it.baseUrl == requested.baseUrl } }
    }

    fun setTranscriptionModel(config: AppConfig, model: String): AppConfig {
        config.serviceCatalog?.let { catalog ->
            val id = catalog.activeTranscriptionId ?: throw ProviderException("请先选择音频转写配置")
            return catalog.copy(profiles = catalog.profiles.map { if (it.id == id) it.copy(model = model.trim()) else it }).attach(config)
        }
        val target = transcription(config)
        if (target.connectionId == config.connectionId) return config.copy(transcriptionModel = model.trim())
        val updated = ConnectionProfile.capture(target.copy(transcriptionModel = model.trim())).copy(apiProtocol =
            snapshot(config).first { it.id == target.connectionId }.apiProtocol)
        return config.copy(connections = snapshot(config).map { if (it.id == target.connectionId) updated else it })
    }

    fun credentialCurrent(request: AppConfig, current: ServiceCatalog): Boolean {
        val key = current.credentials.firstOrNull { it.id == request.credentialId } ?: return false
        val endpoint = current.endpoints.firstOrNull { it.id == request.endpointId } ?: return false
        return !key.unreadable && key.value.isNotBlank() && key.value == request.apiKey &&
            key.revision == request.credentialRevision && endpoint.id in key.endpointIds && key.providerId == endpoint.providerId &&
            endpoint.baseUrl == request.baseUrl && endpoint.apiVersion == request.apiVersion && endpoint.workspaceId == request.workspaceId
    }
}
