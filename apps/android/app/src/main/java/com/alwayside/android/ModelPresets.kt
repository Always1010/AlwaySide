package com.alwayside.android

internal data class ModelPreset(val id: String, val name: String, val tag: String)

internal data class ModelRecommendations(
    val defaultModelId: String = "", val models: List<ModelPreset> = emptyList(),
)

/** Offline suggestions only: never an account-access or runtime capability assertion. */
internal class ModelPresets private constructor(private val groups: Map<Pair<ProviderKind, ModelPurpose>, Group>) {
    private data class Group(
        val recommendations: ModelRecommendations,
        val protocols: Set<ApiProtocol>, val transcriptionApi: FileTranscriptionApi,
    )

    fun recommendations(kind: ProviderKind, purpose: ModelPurpose, endpoint: ServiceEndpoint): ModelRecommendations {
        val group = groups[kind to purpose] ?: return ModelRecommendations()
        val compatible = when (purpose) {
            ModelPurpose.ANSWER -> endpoint.answerProtocol in group.protocols
            ModelPurpose.TRANSCRIPTION -> group.transcriptionApi != FileTranscriptionApi.NONE &&
                endpoint.transcriptionApi == group.transcriptionApi
        }
        return if (compatible) group.recommendations else ModelRecommendations()
    }

    /** Call once for a new draft after its first usable key is selected, never while loading saved profiles. */
    fun initializeNewProfile(profile: ModelProfile, kind: ProviderKind, endpoint: ServiceEndpoint): ModelProfile =
        if (profile.model.isNotBlank()) profile else profile.copy(
            model = recommendations(kind, profile.purpose, endpoint).defaultModelId)

    companion object {
        const val ASSET = "model-presets.json"
        val EMPTY = ModelPresets(emptyMap())

        /** JSON parsing stays at the Android boundary; selection rules are testable without Android. */
        fun decode(raw: Map<String, Any?>): ModelPresets {
            require(raw["schemaVersion"] == 1) { "不支持的模型推荐配置版本" }
            val providers = requireNotNull(raw["providers"] as? Map<*, *>) { "缺少模型推荐服务商" }
            val groups = mutableMapOf<Pair<ProviderKind, ModelPurpose>, Group>()
            providers.forEach { (providerName, providerValue) ->
                val kind = ProviderKind.valueOf(providerName as String)
                val purposes = requireNotNull(providerValue as? Map<*, *>)
                purposes.forEach { (purposeName, purposeValue) ->
                    val purpose = ModelPurpose.valueOf(purposeName as String)
                    val value = requireNotNull(purposeValue as? Map<*, *>)
                    val models = requireNotNull(value["models"] as? List<*>).map { item ->
                        val model = requireNotNull(item as? Map<*, *>)
                        val id = requireNotNull(model["id"] as? String)
                        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}").matches(id)) { "无效的推荐模型 ID" }
                        ModelPreset(id, requireNotNull(model["name"] as? String).also { require(it.isNotBlank()) },
                            model["tag"] as? String ?: "")
                    }
                    require(models.map { it.id }.distinct().size == models.size) { "推荐模型 ID 重复" }
                    val default = requireNotNull(value["defaultModelId"] as? String)
                    require(if (models.isEmpty()) default.isEmpty() else models.any { it.id == default }) {
                        "默认模型必须属于推荐列表"
                    }
                    val protocols = (value["protocols"] as? List<*>).orEmpty()
                        .map { ApiProtocol.valueOf(it as String) }.toSet()
                    val api = (value["transcriptionApi"] as? String)?.let(FileTranscriptionApi::valueOf)
                        ?: FileTranscriptionApi.NONE
                    if (models.isNotEmpty()) require(when (purpose) {
                        ModelPurpose.ANSWER -> protocols.isNotEmpty()
                        ModelPurpose.TRANSCRIPTION -> api != FileTranscriptionApi.NONE
                    }) { "推荐模型必须声明适用接口" }
                    groups[kind to purpose] = Group(ModelRecommendations(default, models), protocols, api)
                }
            }
            return ModelPresets(groups)
        }
    }
}
