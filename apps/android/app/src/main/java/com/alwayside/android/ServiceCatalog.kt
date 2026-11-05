package com.alwayside.android

import java.net.URI
import java.util.UUID

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
    val providerId: String = "",
)

/** UI draft for a named credential and its connection; never persisted until Save. */
internal data class ApiKeyDraft(
    val id: String? = null, val providerId: String, val providerName: String, val kind: ProviderKind,
    val name: String = "", val value: String = "", val baseUrl: String, val answerProtocol: ApiProtocol,
    val transcriptionApi: FileTranscriptionApi = FileTranscriptionApi.NONE,
    val apiVersion: String = "2026-10-21", val workspaceId: String = "",
) {
    override fun toString() = "ApiKeyDraft(id=$id, credential redacted)"
}

/** Canonical configuration. Flat AppConfig fields are immutable request snapshots / legacy UI projections. */
internal data class ServiceCatalog(
    val providers: List<ServiceProvider> = emptyList(), val endpoints: List<ServiceEndpoint> = emptyList(),
    val credentials: List<ServiceCredential> = emptyList(), val profiles: List<ModelProfile> = emptyList(),
    val activeAnswerId: String? = null, val activeTranscriptionId: String? = null,
) {
    override fun toString() = "ServiceCatalog(providers=${providers.size}, profiles=${profiles.size}, credentials redacted)"

    fun providerIdOf(profile: ModelProfile): String = profile.providerId.ifBlank {
        endpoints.firstOrNull { it.id == profile.endpointId }?.providerId.orEmpty()
    }

    fun upsertKey(draft: ApiKeyDraft): ServiceCatalog {
        val old = draft.id?.let { id -> credentials.firstOrNull { it.id == id } ?: error("密钥已删除，请重新打开") }
        require(old == null || old.providerId == draft.providerId) { "已有密钥不能更换所属服务商，请另行添加" }
        val provider = providers.firstOrNull { it.id == draft.providerId }
            ?: ServiceProvider(draft.providerId, draft.providerName.trim(), draft.kind)
        require(provider.kind == draft.kind) { "服务商类型已改变，请重新选择" }
        require(providers.none { it.id != provider.id && it.name.equals(provider.name, ignoreCase = true) }) { "该服务商已存在，请选择已有服务商" }
        val label = draft.name.trim()
        require(credentials.none { it.id != old?.id && it.providerId == provider.id && it.name.equals(label, ignoreCase = true) }) { "此服务商已有同名密钥，请换一个名称" }
        val oldEndpoint = old?.endpointIds?.singleOrNull()?.let { id -> endpoints.firstOrNull { it.id == id } }
        val address = draft.baseUrl.trim().trimEnd('/')
        require(runCatching { ProviderProtocol.endpoint(address, "models") }.isSuccess) { "请输入 HTTPS API 基础地址，不含账号、查询参数或片段" }
        val enteredKey = draft.value.trim()
        require(enteredKey.isNotBlank() || old != null && !old.unreadable && old.value.isNotBlank()) { "请填写 API Key" }
        require(oldEndpoint == null || sameOrigin(oldEndpoint.baseUrl, address) || enteredKey.isNotBlank()) { "API 地址已更换站点，请重新填写密钥" }
        val endpoint = ServiceEndpoint(oldEndpoint?.id ?: UUID.randomUUID().toString(), provider.id, label, address,
            draft.answerProtocol, draft.transcriptionApi, draft.apiVersion.trim(), draft.workspaceId.trim())
        val key = ServiceCredential(old?.id ?: UUID.randomUUID().toString(), provider.id, label,
            enteredKey.ifBlank { old!!.value }, old?.revision ?: 0, setOf(endpoint.id))
        val changed = old != null && (key.value != old.value || old.unreadable || oldEndpoint?.copy(name = endpoint.name) != endpoint)
        val savedKey = if (changed) key.copy(revision = old!!.revision + 1) else key
        val next = copy(
            providers = if (providers.any { it.id == provider.id }) providers else providers + provider,
            endpoints = if (oldEndpoint == null) endpoints + endpoint else endpoints.map { if (it.id == endpoint.id) endpoint else it },
            credentials = if (old == null) credentials + savedKey else credentials.map { if (it.id == old.id) savedKey else it },
            profiles = profiles.map { if (it.credentialId == key.id) it.copy(endpointId = endpoint.id, providerId = provider.id) else it })
        next.validate()
        return if (next == this) this else next
    }

    fun upsertCard(profile: ModelProfile): ServiceCatalog {
        val providerId = providerIdOf(profile)
        val provider = providers.firstOrNull { it.id == providerId } ?: error("请先选择服务商")
        val old = profiles.firstOrNull { it.id == profile.id }
        val firstForPurpose = old == null && profiles.none { it.purpose == profile.purpose }
        require(old == null || providerIdOf(old) == providerId && old.purpose == profile.purpose) { "已有卡片不能更换服务商或用途" }
        require(profiles.none { it.id != profile.id && it.purpose == profile.purpose && providerIdOf(it) == providerId }) { "该服务商已添加，请编辑已有卡片" }
        val key = credentials.firstOrNull { it.id == profile.credentialId } ?: error("请选择已有 API 密钥")
        require(key.providerId == providerId) { "只能选择这家服务商的密钥" }
        val endpoint = key.endpointIds.singleOrNull() ?: error("密钥连接信息无效，请在 API 管理中修复")
        val card = profile.copy(name = provider.name, providerId = provider.id, endpointId = endpoint,
            model = profile.model.trim(), language = profile.language.trim())
        val next = copy(profiles = if (old == null) profiles + card else profiles.map { if (it.id == card.id) card else it })
        next.validate()
        require(next.readiness(card) == null) { next.readiness(card).orEmpty() }
        val saved = if (firstForPurpose) next.activate(card.id) else next
        return if (saved == this) this else saved
    }

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
        if ((profile.purpose == ModelPurpose.ANSWER && activeAnswerId == id) ||
            (profile.purpose == ModelPurpose.TRANSCRIPTION && activeTranscriptionId == id)) return this
        return if (profile.purpose == ModelPurpose.ANSWER) copy(activeAnswerId = id) else copy(activeTranscriptionId = id)
    }

    fun removeProfile(id: String) = copy(profiles = profiles.filterNot { it.id == id },
        activeAnswerId = activeAnswerId?.takeUnless { it == id }, activeTranscriptionId = activeTranscriptionId?.takeUnless { it == id })

    fun removeCredential(id: String): ServiceCatalog {
        val affected = profiles.filter { it.credentialId == id }.map { it.id }.toSet()
        val owned = credentials.firstOrNull { it.id == id }?.endpointIds.orEmpty()
        return copy(credentials = credentials.filterNot { it.id == id },
            endpoints = endpoints.filterNot { it.id in owned },
            profiles = profiles.map { if (it.credentialId == id) it.copy(credentialId = null, endpointId = null,
                providerId = providerIdOf(it)) else it },
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
        profiles.filter { providerIdOf(it) == id }.forEach { next = next.removeProfile(it.id) }
        credentials.filter { it.providerId == id }.forEach { next = next.removeCredential(it.id) }
        return next.copy(providers = next.providers.filterNot { it.id == id }, endpoints = next.endpoints.filterNot { it.providerId == id })
    }

    fun validate() {
        fun ids(values: List<String>) {
            require(values.distinct().size == values.size && values.all { ID.matches(it) }) { "配置 ID 重复或无效" }
        }
        require(providers.size <= 50 && endpoints.size <= 100 && credentials.size <= 100 && profiles.size <= 200) { "配置数量超过上限" }
        ids(providers.map { it.id }); ids(endpoints.map { it.id }); ids(credentials.map { it.id }); ids(profiles.map { it.id })
        (providers.map { it.name } + endpoints.map { it.name } + credentials.map { it.name } + profiles.map { it.name })
            .forEach { require(it.isNotBlank() && it.length <= 80) { "名称请填写 1 至 80 个字符" } }
        require(providers.map { it.name.lowercase() }.distinct().size == providers.size) { "服务商名称不能重复" }
        require(credentials.flatMap { it.endpointIds }.distinct().size == credentials.size) { "每条密钥须有独立的连接信息" }
        endpoints.forEach { endpoint ->
            require(providers.any { it.id == endpoint.providerId }) { "接入点所属服务商不存在" }
            ProviderProtocol.endpoint(endpoint.baseUrl, "models")
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(endpoint.apiVersion)) { "API 版本请使用 YYYY-MM-DD" }
            require(endpoint.workspaceId.length <= 200 && endpoint.workspaceId.all { it.code in 33..126 }) { "Workspace ID 无效" }
        }
        credentials.forEach { credential ->
            require(providers.any { it.id == credential.providerId }) { "密钥所属服务商不存在" }
            require(credential.endpointIds.all { id -> endpoints.any { it.id == id && it.providerId == credential.providerId } }) { "密钥接入点范围无效" }
            require(credential.endpointIds.size == 1) { "每条密钥须有一组连接信息" }
            require(credentials.none { it.id != credential.id && it.providerId == credential.providerId && it.name.equals(credential.name, ignoreCase = true) }) { "同一服务商的密钥名称不能重复" }
            require(credential.value.length <= 4096 && credential.value.all { it.code in 33..126 } && credential.revision >= 0) { "密钥格式无效" }
        }
        profiles.forEach { profile ->
            require(providers.any { it.id == providerIdOf(profile) }) { "卡片所属服务商不存在" }
            require(profile.endpointId == null || endpoints.any { it.id == profile.endpointId }) { "模型接入点不存在" }
            require(profile.credentialId == null || credentials.any { it.id == profile.credentialId }) { "模型密钥不存在" }
            if (profile.endpointId != null && profile.credentialId != null) {
                val endpoint = endpoints.first { it.id == profile.endpointId }
                val credential = credentials.first { it.id == profile.credentialId }
                require(endpoint.providerId == credential.providerId) { "模型配置不能使用另一家服务的密钥" }
                require(providerIdOf(profile) == credential.providerId && endpoint.id in credential.endpointIds) { "卡片只能引用所属服务商密钥的连接信息" }
            }
            require(profile.model.length <= 200 && profile.model.none(Char::isISOControl)) { "模型 ID 无效" }
            require(profile.transcriptionPrompt.length <= 32000) { "转写提示过长" }
            require(profile.language.isBlank() || Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(profile.language)) { "语言请填写语言代码或留空" }
            ProviderProtocol.validateOptions(profile.options.applyTo(AppConfig()))
            require(profile.purpose == ModelPurpose.ANSWER || profile.options == GenerationOptions()) { "转写配置不能包含问答生成参数" }
        }
        require(profiles.map { it.purpose to providerIdOf(it) }.distinct().size == profiles.size) { "同一用途下每家服务商只能有一张卡片" }
        require(activeAnswerId == null || profiles.any { it.id == activeAnswerId && it.purpose == ModelPurpose.ANSWER }) { "图文问答绑定无效" }
        require(activeTranscriptionId == null || profiles.any { it.id == activeTranscriptionId && it.purpose == ModelPurpose.TRANSCRIPTION }) { "转写绑定无效" }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9-]{1,100}")
        fun sameOrigin(first: String, second: String): Boolean = runCatching {
            fun origin(value: String) = URI(value).let { Triple(it.scheme?.lowercase(), it.host?.lowercase(), if (it.port == -1) 443 else it.port) }
            origin(first) == origin(second)
        }.getOrDefault(false)

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
