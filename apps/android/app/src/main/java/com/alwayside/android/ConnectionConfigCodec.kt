package com.alwayside.android

/** Versioned public metadata; credentials are deliberately absent from the format. */
internal object ConnectionConfigCodec {
    fun encode(profile: ConnectionProfile): Map<String, Any?> = linkedMapOf(
        "id" to profile.id, "name" to profile.name, "provider" to profile.providerKind.name,
        "protocol" to profile.apiProtocol.name, "baseUrl" to profile.baseUrl.trim().trimEnd('/'),
        "model" to profile.model.trim(), "transcriptionModel" to profile.transcriptionModel.trim(),
        "maxOutputTokens" to profile.maxOutputTokens, "reasoning" to profile.reasoning,
        "verbosity" to profile.verbosity, "temperature" to profile.temperature, "topP" to profile.topP,
        "webSearch" to profile.webSearch, "apiVersion" to profile.apiVersion,
        "workspaceId" to profile.workspaceId, "credentialRevision" to profile.credentialRevision)

    fun decode(data: Map<String, Any?>, credential: String): ConnectionProfile {
        val id = data.text("id")
        require(Regex("[A-Za-z0-9-]{1,80}").matches(id)) { "连接 ID 无效" }
        return ConnectionProfile(id, data.text("name"), ProviderKind.valueOf(data.text("provider")),
            ApiProtocol.valueOf(data.text("protocol")), data.text("baseUrl"), credential,
            data.text("model"), data.text("transcriptionModel"),
            (data["maxOutputTokens"] as? Number)?.toInt() ?: 0, data.text("reasoning"), data.text("verbosity"),
            (data["temperature"] as? Number)?.toDouble(), (data["topP"] as? Number)?.toDouble(),
            data["webSearch"] == true, data.text("apiVersion").ifBlank { "2026-10-21" },
            data.text("workspaceId"), (data["credentialRevision"] as? Number)?.toLong() ?: 0)
    }

    fun migrateLegacy(config: AppConfig): AppConfig {
        val legacy = config.copy(providerKind = ProviderKind.infer(config.baseUrl), apiProtocol = ApiProtocol.RESPONSES,
            connectionId = "legacy", connectionName = "原有连接", transcriptionConnectionId = "legacy")
        return legacy.copy(connections = listOf(ConnectionProfile.capture(legacy)))
    }
}
