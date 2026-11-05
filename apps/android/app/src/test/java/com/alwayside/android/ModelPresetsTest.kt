package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ModelPresetsTest {
    private fun group(id: String, protocol: String? = null, api: String? = null) = mapOf(
        "defaultModelId" to id,
        "models" to listOf(mapOf("id" to id, "name" to "推荐型号", "tag" to "图文")),
        "protocols" to listOfNotNull(protocol), "transcriptionApi" to (api ?: "NONE"),
    )
    private fun presets() = ModelPresets.decode(mapOf("schemaVersion" to 1, "providers" to mapOf(
        "OPENAI" to mapOf(
            "ANSWER" to group("answer-fixture", "RESPONSES"),
            "TRANSCRIPTION" to group("transcribe-fixture", api = "OPENAI_COMPATIBLE")),
        "QWEN" to mapOf("ANSWER" to group("qwen-fixture", "CHAT_COMPLETIONS")),
    )))
    private fun endpoint(protocol: ApiProtocol = ApiProtocol.RESPONSES,
                         api: FileTranscriptionApi = FileTranscriptionApi.NONE) =
        ServiceEndpoint("endpoint", "provider", "连接", "https://example.com/v1", protocol, api)

    @Test fun recommendationsAreIsolatedByProviderPurposeAndActualInterface() {
        val presets = presets()
        assertEquals("answer-fixture", presets.recommendations(ProviderKind.OPENAI, ModelPurpose.ANSWER, endpoint()).defaultModelId)
        assertTrue(presets.recommendations(ProviderKind.OPENAI, ModelPurpose.ANSWER, endpoint(ApiProtocol.CLAUDE)).models.isEmpty())
        assertEquals("qwen-fixture", presets.recommendations(ProviderKind.QWEN, ModelPurpose.ANSWER,
            endpoint(ApiProtocol.CHAT_COMPLETIONS)).defaultModelId)
        val transcribe = endpoint(ApiProtocol.CLAUDE, FileTranscriptionApi.OPENAI_COMPATIBLE)
        assertEquals("transcribe-fixture", presets.recommendations(ProviderKind.OPENAI, ModelPurpose.TRANSCRIPTION, transcribe).defaultModelId)
        assertTrue(presets.recommendations(ProviderKind.OPENAI, ModelPurpose.TRANSCRIPTION, endpoint()).models.isEmpty())
        (ProviderKind.entries - ProviderKind.OPENAI).forEach { kind ->
            assertEquals(ModelRecommendations(), presets.recommendations(kind, ModelPurpose.TRANSCRIPTION, transcribe))
        }
        assertEquals(ModelRecommendations(), presets.recommendations(ProviderKind.CUSTOM, ModelPurpose.ANSWER, endpoint()))
    }

    @Test fun initializationOnlyFillsBlankNewDraftAndPreservesAllOtherSettings() {
        val presets = presets()
        val draft = ModelProfile("draft", "OpenAI", ModelPurpose.ANSWER,
            options = GenerationOptions(reasoning = "high"), transcriptionPrompt = "保留提示", language = "zh")
        assertEquals(draft.copy(model = "answer-fixture"), presets.initializeNewProfile(draft, ProviderKind.OPENAI, endpoint()))
        val manual = draft.copy(model = "private-model-v2")
        assertSame(manual, presets.initializeNewProfile(manual, ProviderKind.OPENAI, endpoint()))
        assertEquals(draft, presets.initializeNewProfile(draft, ProviderKind.CUSTOM, endpoint()))
        assertEquals(draft, presets.initializeNewProfile(draft, ProviderKind.OPENAI, endpoint(ApiProtocol.GEMINI)))
    }

    @Test fun updatingRecommendationsNeverChangesSavedModelOrPurposeBinding() {
        val keys = ServiceCatalog().upsertKey(ApiKeyDraft(providerId = "openai", providerName = "OpenAI",
            kind = ProviderKind.OPENAI, name = "测试 Key", value = "fixture-secret",
            baseUrl = "https://example.com/v1", answerProtocol = ApiProtocol.RESPONSES))
        val key = keys.credentials.single()
        val saved = keys.upsertCard(ModelProfile("saved", "OpenAI", ModelPurpose.ANSWER,
            credentialId = key.id, providerId = key.providerId, model = "previous-user-model"))
        val endpoint = saved.endpoints.single()
        assertEquals("answer-fixture", presets().recommendations(ProviderKind.OPENAI, ModelPurpose.ANSWER, endpoint).defaultModelId)
        val restored = ServiceCatalogCodec.decode(ServiceCatalogCodec.encode(saved)) { key.value to false }
        assertEquals("previous-user-model", restored.profiles.single().model)
        assertEquals(saved.activeAnswerId, restored.activeAnswerId)
    }

    @Test fun invalidDefaultCannotBeOfferedAsARecommendedModel() {
        val invalid = group("known-model", "RESPONSES") + ("defaultModelId" to "missing-model")
        assertThrows(IllegalArgumentException::class.java) {
            ModelPresets.decode(mapOf("schemaVersion" to 1, "providers" to mapOf("OPENAI" to mapOf("ANSWER" to invalid))))
        }
    }
}
