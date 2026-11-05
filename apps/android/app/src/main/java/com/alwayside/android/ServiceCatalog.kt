package com.alwayside.android

import java.net.URI

internal enum class ModelPurpose(val title: String) { ANSWER("图文问答"), TRANSCRIPTION("音频转写") }
internal enum class FileTranscriptionApi { NONE, OPENAI_COMPATIBLE }

internal data class ServiceProvider(val id: String, val name: String, val kind: ProviderKind)
internal data class ServiceEndpoint(
    val id: String, val providerId: String, val name: String, val baseUrl: String,
    val answerProtocol: ApiProtocol, val transcriptionApi: FileTranscriptionApi = FileTranscriptionApi.NONE,
    val apiVersion: String = "2026-10-21", val workspaceId: String = "",
)
internal data class ServiceCredential(
    val id: String, val providerId: String, val name: String, val value: String = "", val revision: Long = 0,
    val endpointIds: Set<String> = emptySet(), val unreadable: Boolean = false,
) {
    override fun toString() = "ServiceCredential(id=$id, credential redacted)"
}
internal data class GenerationOptions(
    val maxOutputTokens: Int = 0, val reasoning: String = "", val verbosity: String = "",
    val temperature: Double? = null, val topP: Double? = null, val webSearch: Boolean = false,
) {
    fun applyTo(config: AppConfig) = config.copy(maxOutputTokens = maxOutputTokens, reasoning = reasoning,
        verbosity = verbosity, temperature = temperature, topP = topP, webSearch = webSearch)
    companion object {
        fun capture(c: AppConfig) = GenerationOptions(c.maxOutputTokens, c.reasoning, c.verbosity, c.temperature, c.topP, c.webSearch)
    }
}
internal data class ModelProfile(
    val id: String, val name: String, val purpose: ModelPurpose, val endpointId: String? = null,
    val credentialId: String? = null, val model: String = "", val options: GenerationOptions = GenerationOptions(),
    val transcriptionPrompt: String = "", val language: String = "",
)

/** Canonical configuration. Flat AppConfig fields are immutable request snapshots / legacy UI projections. */
internal data class ServiceCatalog(
    val providers: List<ServiceProvider> = emptyList(), val endpoints: List<ServiceEndpoint> = emptyList(),
    val credentials: List<ServiceCredential> = emptyList(), val profiles: List<ModelProfile> = emptyList(),
    val activeAnswerId: String? = null, val activeTranscriptionId: String? = null,
) {
    override fun toString() = "ServiceCatalog(providers=${providers.size}, profiles=${profiles.size}, credentials redacted)"

    fun project(config: AppConfig, profileId: String): AppConfig {
        val profile = profiles.firstOrNull { it.id == profileId } ?: throw IllegalArgumentException("模型配置已删除")
        val endpoint = endpoints.firstOrNull { it.id == profile.endpointId }
        val provider = providers.firstOrNull { it.id == endpoint?.providerId }
        val credential = credentials.firstOrNull { it.id == profile.credentialId }
        val authorized = endpoint != null && credential != null && !credential.unreadable &&
            credential.providerId == endpoint.providerId && endpoint.id in credential.endpointIds
        return profile.options.applyTo(config).copy(
            serviceCatalog = this, connections = emptyList(), connectionId = profile.id, connectionName = profile.name,
            endpointId = endpoint?.id.orEmpty(), credentialId = credential?.id.orEmpty(),
            providerKind = provider?.kind ?: ProviderKind.CUSTOM,
            apiProtocol = if (profile.purpose == ModelPurpose.TRANSCRIPTION) ApiProtocol.CHAT_COMPLETIONS
                else endpoint?.answerProtocol ?: ApiProtocol.RESPONSES,
            baseUrl = endpoint?.baseUrl.orEmpty(), apiKey = if (authorized) credential!!.value else "",
            credentialRevision = credential?.revision ?: 0,
            apiVersion = endpoint?.apiVersion ?: "2026-10-21", workspaceId = endpoint?.workspaceId.orEmpty(),
            model = if (profile.purpose == ModelPurpose.ANSWER) profile.model else "",
            transcriptionModel = if (profile.purpose == ModelPurpose.TRANSCRIPTION) profile.model else "",
            transcriptionPrompt = if (profile.purpose == ModelPurpose.TRANSCRIPTION) profile.transcriptionPrompt else config.transcriptionPrompt,
            transcriptionLanguage = profile.language, transcriptionConnectionId = activeTranscriptionId,
            continuationMessages = emptyList())
    }

    fun attach(config: AppConfig): AppConfig = activeAnswerId?.let { project(config, it) }
        ?: GenerationOptions().applyTo(config).copy(serviceCatalog = this, apiKey = "", model = "", baseUrl = "",
            connectionId = "unconfigured", connectionName = "未配置图文问答", credentialId = "", endpointId = "",
            connections = emptyList(), transcriptionConnectionId = activeTranscriptionId, continuationMessages = emptyList())

    fun readiness(profile: ModelProfile): String? {
        val endpoint = endpoints.firstOrNull { it.id == profile.endpointId } ?: return "请选择服务接入点"
        val credential = credentials.firstOrNull { it.id == profile.credentialId } ?: return "请选择密钥"
        if (credential.unreadable) return "密钥无法解密，请替换或删除此密钥"
        if (credential.value.isBlank()) return "请先填写密钥"
        if (credential.providerId != endpoint.providerId || endpoint.id !in credential.endpointIds) return "密钥未授权用于此接入点"
        if (profile.model.isBlank()) return "请填写模型 ID"
        if (!Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}").matches(profile.model)) return "请填写有效的模型 ID"
        if (profile.purpose == ModelPurpose.TRANSCRIPTION && endpoint.transcriptionApi != FileTranscriptionApi.OPENAI_COMPATIBLE)
            return "此接入点未配置兼容文件转写接口"
        if (profile.purpose == ModelPurpose.ANSWER) {
            val config = project(AppConfig(), profile.id)
            return runCatching {
                ProviderCapabilities.validate(config, false)
                ModelOptionCompatibility.validate(config)
            }.exceptionOrNull()?.message
        }
        return null
    }

    fun activate(id: String): ServiceCatalog {
        val profile = profiles.firstOrNull { it.id == id } ?: error("模型配置已删除")
        require(readiness(profile) == null) { readiness(profile).orEmpty() }
        return if (profile.purpose == ModelPurpose.ANSWER) copy(activeAnswerId = id) else copy(activeTranscriptionId = id)
    }

    fun removeProfile(id: String) = copy(profiles = profiles.filterNot { it.id == id },
        activeAnswerId = activeAnswerId?.takeUnless { it == id }, activeTranscriptionId = activeTranscriptionId?.takeUnless { it == id })

    fun removeCredential(id: String): ServiceCatalog {
        val affected = profiles.filter { it.credentialId == id }.map { it.id }.toSet()
        return copy(credentials = credentials.filterNot { it.id == id },
            profiles = profiles.map { if (it.credentialId == id) it.copy(credentialId = null) else it },
            activeAnswerId = activeAnswerId?.takeUnless { it in affected },
            activeTranscriptionId = activeTranscriptionId?.takeUnless { it in affected })
    }

    fun removeEndpoint(id: String): ServiceCatalog {
        val affected = profiles.filter { it.endpointId == id }.map { it.id }.toSet()
        return copy(endpoints = endpoints.filterNot { it.id == id },
            credentials = credentials.map { it.copy(endpointIds = it.endpointIds - id) },
            profiles = profiles.map { if (it.endpointId == id) it.copy(endpointId = null, credentialId = null) else it },
            activeAnswerId = activeAnswerId?.takeUnless { it in affected },
            activeTranscriptionId = activeTranscriptionId?.takeUnless { it in affected })
    }

    fun removeProvider(id: String): ServiceCatalog {
        var next = this
        endpoints.filter { it.providerId == id }.forEach { next = next.removeEndpoint(it.id) }
        credentials.filter { it.providerId == id }.forEach { next = next.removeCredential(it.id) }
        return next.copy(providers = next.providers.filterNot { it.id == id })
    }

    fun validate() {
        fun ids(values: List<String>) {
            require(values.distinct().size == values.size && values.all { ID.matches(it) }) { "配置 ID 重复或无效" }
        }
        require(providers.size <= 50 && endpoints.size <= 100 && credentials.size <= 100 && profiles.size <= 200) { "配置数量超过上限" }
        ids(providers.map { it.id }); ids(endpoints.map { it.id }); ids(credentials.map { it.id }); ids(profiles.map { it.id })
        (providers.map { it.name } + endpoints.map { it.name } + credentials.map { it.name } + profiles.map { it.name })
            .forEach { require(it.isNotBlank() && it.length <= 80) { "名称请填写 1 至 80 个字符" } }
        endpoints.forEach { endpoint ->
            require(providers.any { it.id == endpoint.providerId }) { "接入点所属服务商不存在" }
            ProviderProtocol.endpoint(endpoint.baseUrl, "models")
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(endpoint.apiVersion)) { "API 版本请使用 YYYY-MM-DD" }
            require(endpoint.workspaceId.length <= 200 && endpoint.workspaceId.all { it.code in 33..126 }) { "Workspace ID 无效" }
        }
        credentials.forEach { credential ->
            require(providers.any { it.id == credential.providerId }) { "密钥所属服务商不存在" }
            require(credential.endpointIds.all { id -> endpoints.any { it.id == id && it.providerId == credential.providerId } }) { "密钥接入点范围无效" }
            require(credential.value.length <= 4096 && credential.value.all { it.code in 33..126 } && credential.revision >= 0) { "密钥格式无效" }
        }
        profiles.forEach { profile ->
            require(profile.endpointId == null || endpoints.any { it.id == profile.endpointId }) { "模型接入点不存在" }
            require(profile.credentialId == null || credentials.any { it.id == profile.credentialId }) { "模型密钥不存在" }
            if (profile.endpointId != null && profile.credentialId != null) {
                val endpoint = endpoints.first { it.id == profile.endpointId }
                val credential = credentials.first { it.id == profile.credentialId }
                require(endpoint.providerId == credential.providerId) { "模型配置不能使用另一家服务的密钥" }
            }
            require(profile.model.length <= 200 && profile.model.none(Char::isISOControl)) { "模型 ID 无效" }
            require(profile.transcriptionPrompt.length <= 32000) { "转写提示过长" }
            require(profile.language.isBlank() || Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(profile.language)) { "语言请填写语言代码或留空" }
            ProviderProtocol.validateOptions(profile.options.applyTo(AppConfig()))
            require(profile.purpose == ModelPurpose.ANSWER || profile.options == GenerationOptions()) { "转写配置不能包含问答生成参数" }
        }
        require(activeAnswerId == null || profiles.any { it.id == activeAnswerId && it.purpose == ModelPurpose.ANSWER }) { "图文问答绑定无效" }
        require(activeTranscriptionId == null || profiles.any { it.id == activeTranscriptionId && it.purpose == ModelPurpose.TRANSCRIPTION }) { "转写绑定无效" }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9-]{1,100}")
        fun sameOrigin(first: String, second: String): Boolean = runCatching {
            fun origin(value: String) = URI(value).let { Triple(it.scheme?.lowercase(), it.host?.lowercase(), if (it.port == -1) 443 else it.port) }
            origin(first) == origin(second)
        }.getOrDefault(false)

        fun migrate(config: AppConfig): ServiceCatalog {
            config.serviceCatalog?.let { return it }
            val connections = ProviderConnections.snapshot(config)
            val providers = connections.map { ServiceProvider("provider-${it.id}", if (it.name == "原有连接") "${it.providerKind.title}（原有配置）" else it.name, it.providerKind) }
            val endpoints = connections.map { ServiceEndpoint("endpoint-${it.id}", "provider-${it.id}", "默认接入点", it.baseUrl, it.apiProtocol,
                if (it.apiProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE,
                it.apiVersion, it.workspaceId) }
            val credentials = connections.map { ServiceCredential("key-${it.id}", "provider-${it.id}", "原有密钥", it.apiKey,
                it.credentialRevision, setOf("endpoint-${it.id}")) }
            val profiles = connections.flatMap { c ->
                val name = providers.first { it.id == "provider-${c.id}" }.name
                listOf(ModelProfile(c.id, name, ModelPurpose.ANSWER, "endpoint-${c.id}", "key-${c.id}", c.model,
                    GenerationOptions(c.maxOutputTokens, c.reasoning, c.verbosity, c.temperature, c.topP, c.webSearch))) +
                    if (c.transcriptionModel.isNotBlank()) listOf(ModelProfile("asr-${c.id}", name, ModelPurpose.TRANSCRIPTION,
                        "endpoint-${c.id}", "key-${c.id}", c.transcriptionModel, transcriptionPrompt = config.transcriptionPrompt)) else emptyList()
            }
            return ServiceCatalog(providers, endpoints, credentials, profiles, config.connectionId,
                config.transcriptionConnectionId?.let { id -> "asr-$id".takeIf { asr -> profiles.any { it.id == asr } } })
        }
    }
}

/** Use the real request codecs to identify known incompatibilities without making network calls. */
internal object ModelOptionCompatibility {
    fun validate(config: AppConfig) {
        val adapter: ChatAdapter = when (config.apiProtocol) {
            ApiProtocol.RESPONSES -> ResponsesAdapter
            ApiProtocol.CHAT_COMPLETIONS -> ChatCompletionsAdapter
            ApiProtocol.GEMINI -> GeminiAdapter
            ApiProtocol.CLAUDE -> ClaudeMessagesAdapter()
        }
        adapter.request(config, emptyList(), "参数检查", null)
    }
}
