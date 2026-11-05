package com.alwayside.android

/** Public schema v3. Credential values are supplied separately by encrypted storage. */
internal object ServiceCatalogCodec {
    fun encode(c: ServiceCatalog): Map<String, Any?> = linkedMapOf(
        "providers" to c.providers.map { mapOf("id" to it.id, "name" to it.name, "kind" to it.kind.name) },
        "endpoints" to c.endpoints.map { mapOf("id" to it.id, "providerId" to it.providerId, "name" to it.name,
            "baseUrl" to it.baseUrl, "answerProtocol" to it.answerProtocol.name, "transcriptionApi" to it.transcriptionApi.name,
            "apiVersion" to it.apiVersion, "workspaceId" to it.workspaceId) },
        "credentials" to c.credentials.map { mapOf("id" to it.id, "providerId" to it.providerId, "name" to it.name,
            "revision" to it.revision, "endpointIds" to it.endpointIds.toList()) },
        "profiles" to c.profiles.map { mapOf("id" to it.id, "name" to it.name, "purpose" to it.purpose.name,
            "endpointId" to it.endpointId, "credentialId" to it.credentialId, "model" to it.model,
            "options" to options(it.options), "transcriptionPrompt" to it.transcriptionPrompt, "language" to it.language) },
        "activeAnswerId" to c.activeAnswerId, "activeTranscriptionId" to c.activeTranscriptionId)

    private fun options(o: GenerationOptions) = mapOf("maxOutputTokens" to o.maxOutputTokens, "reasoning" to o.reasoning,
        "verbosity" to o.verbosity, "temperature" to o.temperature, "topP" to o.topP, "webSearch" to o.webSearch)

    fun decode(data: Map<String, Any?>, credential: (String) -> Pair<String, Boolean>): ServiceCatalog {
        listOf("providers", "endpoints", "credentials", "profiles").forEach { key ->
            require(data[key] is List<*> && data.items(key).size <= 200) { "服务配置格式无效" }
        }
        fun Map<String, Any?>.nullable(key: String) = this[key] as? String
        val catalog = ServiceCatalog(
            providers = data.items("providers").map { value -> value.wireObject().let { ServiceProvider(it.text("id"), it.text("name"), ProviderKind.valueOf(it.text("kind"))) } },
            endpoints = data.items("endpoints").map { value -> value.wireObject().let { ServiceEndpoint(it.text("id"), it.text("providerId"), it.text("name"),
                it.text("baseUrl"), ApiProtocol.valueOf(it.text("answerProtocol")), FileTranscriptionApi.valueOf(it.text("transcriptionApi")),
                it.text("apiVersion"), it.text("workspaceId")) } },
            credentials = data.items("credentials").map { value -> value.wireObject().let {
                val (secret, unreadable) = credential(it.text("id"))
                ServiceCredential(it.text("id"), it.text("providerId"), it.text("name"), secret,
                    (it["revision"] as? Number)?.toLong() ?: 0, it.items("endpointIds").map { id -> id as String }.toSet(), unreadable)
            } },
            profiles = data.items("profiles").map { value -> value.wireObject().let {
                val o = it["options"].wireObject()
                ModelProfile(it.text("id"), it.text("name"), ModelPurpose.valueOf(it.text("purpose")), it.nullable("endpointId"),
                    it.nullable("credentialId"), it.text("model"), GenerationOptions((o["maxOutputTokens"] as? Number)?.toInt() ?: 0,
                        o.text("reasoning"), o.text("verbosity"), (o["temperature"] as? Number)?.toDouble(), (o["topP"] as? Number)?.toDouble(), o["webSearch"] == true),
                    it.text("transcriptionPrompt"), it.text("language"))
            } }, activeAnswerId = data.nullable("activeAnswerId"), activeTranscriptionId = data.nullable("activeTranscriptionId"))
        catalog.validate()
        return catalog
    }
}
