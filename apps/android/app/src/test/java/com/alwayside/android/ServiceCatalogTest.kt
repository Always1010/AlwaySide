package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

/** Offline regression coverage for credential isolation, purpose bindings and legacy migration. */
class ServiceCatalogTest {
    private val answer = ModelProfile("answer", "问答", ModelPurpose.ANSWER, "shared", "answer-key", "fixture-answer")
    private val asr = ModelProfile("asr", "转写", ModelPurpose.TRANSCRIPTION, "shared", "asr-key", "fixture-asr",
        transcriptionPrompt = "保留专有名词", language = "zh")
    private val catalog = ServiceCatalog(
        providers = listOf(ServiceProvider("first", "第一家", ProviderKind.CUSTOM), ServiceProvider("second", "第二家", ProviderKind.CUSTOM)),
        endpoints = listOf(
            ServiceEndpoint("shared", "first", "共用接入点", "https://first.example/v1", ApiProtocol.RESPONSES, FileTranscriptionApi.OPENAI_COMPATIBLE),
            ServiceEndpoint("other", "second", "另一接入点", "https://second.example/v1", ApiProtocol.RESPONSES, FileTranscriptionApi.OPENAI_COMPATIBLE)),
        credentials = listOf(
            ServiceCredential("answer-key", "first", "问答密钥", "fixture-answer-secret", 3, setOf("shared")),
            ServiceCredential("asr-key", "first", "转写密钥", "fixture-asr-secret", 7, setOf("shared")),
            ServiceCredential("other-key", "second", "另一家密钥", "fixture-other-secret", 2, setOf("other"))),
        profiles = listOf(answer, asr,
            ModelProfile("other-answer", "另一问答", ModelPurpose.ANSWER, "other", "other-key", "fixture-other"),
            ModelProfile("other-asr", "另一转写", ModelPurpose.TRANSCRIPTION, "other", "other-key", "fixture-other-asr")))

    @Test fun v1MigrationPreservesTheCustomEndpointSharedAsrAndGenerationOptions() {
        val legacy = ConnectionConfigCodec.migrateLegacy(AppConfig(baseUrl = "https://proxy.example/custom/v1",
            apiKey = "fixture-legacy-secret", model = "legacy-answer", transcriptionModel = "legacy-asr",
            maxOutputTokens = 2048, reasoning = "high", verbosity = "low", temperature = 0.4,
            topP = 0.8, webSearch = true, transcriptionPrompt = "legacy prompt", credentialRevision = 5))
        val migrated = ServiceCatalog.migrate(legacy)
        val projected = migrated.attach(AppConfig())
        assertEquals(legacy.baseUrl, projected.baseUrl)
        assertEquals(legacy.apiKey, projected.apiKey)
        assertEquals(legacy.model, projected.model)
        assertEquals(GenerationOptions.capture(legacy), GenerationOptions.capture(projected))
        val transcription = migrated.project(AppConfig(), migrated.activeTranscriptionId!!)
        assertEquals(legacy.transcriptionModel, transcription.transcriptionModel)
        assertEquals(legacy.transcriptionPrompt, transcription.transcriptionPrompt)
        assertEquals(projected.credentialId, transcription.credentialId)
        assertEquals(5L, transcription.credentialRevision)
        assertSame(migrated, ServiceCatalog.migrate(projected))
    }

    @Test fun v2MigrationPreservesEachConnectionAndIndependentTranscriptionBinding() {
        val first = ConnectionProfile("old-answer", "旧问答", ProviderKind.CUSTOM, ApiProtocol.RESPONSES,
            "https://old-answer.example/v1", "fixture-old-answer-key", "answer-model", "unused-asr",
            1536, "high", "low", 0.3, 0.7, true, credentialRevision = 4)
        val second = ConnectionProfile("old-asr", "旧转写", ProviderKind.QWEN, ApiProtocol.CHAT_COMPLETIONS,
            "https://old-asr.example/v1", "fixture-old-asr-key", "second-answer", "independent-asr",
            512, "", "", 0.6, 0.9, false, credentialRevision = 9)
        val legacy = first.applyTo(AppConfig()).copy(connections = listOf(first, second),
            transcriptionConnectionId = second.id, transcriptionPrompt = "independent prompt")
        val migrated = ServiceCatalog.migrate(legacy)
        listOf(first, second).forEach { original ->
            val projected = migrated.project(AppConfig(), original.id)
            assertEquals(original.apiKey, projected.apiKey)
            assertEquals(original.baseUrl, projected.baseUrl)
            assertEquals(original.model, projected.model)
            assertEquals(original.credentialRevision, projected.credentialRevision)
            assertEquals(GenerationOptions(original.maxOutputTokens, original.reasoning, original.verbosity,
                original.temperature, original.topP, original.webSearch), GenerationOptions.capture(projected))
        }
        val active = migrated.attach(AppConfig())
        val transcription = migrated.project(active, migrated.activeTranscriptionId!!)
        assertEquals(first.id, active.connectionId)
        assertEquals(second.apiKey, transcription.apiKey)
        assertEquals(second.transcriptionModel, transcription.transcriptionModel)
        assertEquals("independent prompt", transcription.transcriptionPrompt)
        assertSame(migrated, ServiceCatalog.migrate(active))
    }

    @Test fun savingProfilesAndMultipleKeysNeverActivatesAPurposeUntilExplicitSelection() {
        catalog.validate()
        assertNull(catalog.activeAnswerId)
        assertNull(catalog.activeTranscriptionId)
        assertEquals("", catalog.attach(AppConfig(apiKey = "stale-secret")).apiKey)
        val active = catalog.activate(answer.id).activate(asr.id)
        val edited = active.copy(profiles = active.profiles + answer.copy(id = "draft", name = "新建配置"))
        assertEquals(answer.id, edited.activeAnswerId)
        assertEquals(asr.id, edited.activeTranscriptionId)
        val answerConfig = edited.attach(AppConfig())
        val asrConfig = ProviderConnections.transcription(answerConfig)
        assertEquals(answerConfig.baseUrl, asrConfig.baseUrl)
        assertEquals("fixture-answer-secret", answerConfig.apiKey)
        assertEquals("fixture-asr-secret", asrConfig.apiKey)
        assertEquals("fixture-asr", asrConfig.transcriptionModel)
        assertEquals("zh", asrConfig.transcriptionLanguage)
        val switched = edited.activate("other-answer")
        assertEquals("other-answer", switched.activeAnswerId)
        assertEquals(asr.id, switched.activeTranscriptionId)
        assertEquals("fixture-asr-secret", ProviderConnections.transcription(switched.attach(AppConfig())).apiKey)
    }

    @Test fun deletingAReferencedKeyOrEndpointUnbindsOnlyAffectedPurposesWithoutFallback() {
        val active = catalog.activate(answer.id).activate(asr.id)
        val removedKey = active.removeCredential("answer-key")
        assertNull(removedKey.activeAnswerId)
        assertEquals(asr.id, removedKey.activeTranscriptionId)
        assertNull(removedKey.profiles.first { it.id == answer.id }.credentialId)
        assertEquals("", removedKey.attach(AppConfig(apiKey = "stale-secret")).apiKey)
        assertEquals("fixture-asr-secret", ProviderConnections.transcription(removedKey.attach(AppConfig())).apiKey)
        val split = active.activate("other-asr").removeEndpoint("shared")
        assertNull(split.activeAnswerId)
        assertEquals("other-asr", split.activeTranscriptionId)
        assertEquals("fixture-other-secret", ProviderConnections.transcription(split.attach(AppConfig())).apiKey)
        val removedShared = active.removeEndpoint("shared")
        assertNull(removedShared.activeAnswerId)
        assertNull(removedShared.activeTranscriptionId)
        assertThrows(ProviderException::class.java) { ProviderConnections.transcription(removedShared.attach(AppConfig())) }
        removedKey.validate()
        split.validate()
        removedShared.validate()
    }

    @Test fun crossProviderAndUnauthorizedEndpointBindingsCannotExposeASecret() {
        val crossProvider = catalog.copy(profiles = listOf(answer.copy(credentialId = "other-key")))
        assertThrows(IllegalArgumentException::class.java) { crossProvider.validate() }
        assertEquals("", crossProvider.project(AppConfig(apiKey = "stale-secret"), answer.id).apiKey)
        val unauthorized = catalog.copy(credentials = catalog.credentials.map {
            if (it.id == "answer-key") it.copy(endpointIds = emptySet()) else it
        })
        unauthorized.validate()
        assertNotNull(unauthorized.readiness(answer))
        assertThrows(IllegalArgumentException::class.java) { unauthorized.activate(answer.id) }
        assertEquals("", unauthorized.project(AppConfig(apiKey = "stale-secret"), answer.id).apiKey)
    }

    @Test fun publicMetadataContainsNoSecretsAndReloadKeepsKeysAndBindingsIndependent() {
        val active = catalog.activate(answer.id).activate(asr.id)
        val metadata = ServiceCatalogCodec.encode(active)
        active.credentials.forEach { assertFalse(metadata.toString().contains(it.value)) }
        val secrets = active.credentials.associate { it.id to it.value }
        val restored = ServiceCatalogCodec.decode(metadata) { id -> secrets.getValue(id) to false }
        assertEquals(active, restored)
        assertEquals("fixture-answer-secret", restored.attach(AppConfig()).apiKey)
        assertEquals("fixture-asr-secret", ProviderConnections.transcription(restored.attach(AppConfig())).apiKey)
    }

    @Test fun oneUnreadableKeyLeavesTheOtherProviderUsableWithoutFallback() {
        val active = catalog.activate(answer.id).activate("other-asr")
        val restored = ServiceCatalogCodec.decode(ServiceCatalogCodec.encode(active)) { id ->
            if (id == "answer-key") "" to true
            else catalog.credentials.first { it.id == id }.value to false
        }
        assertNotNull(restored.readiness(restored.profiles.first { it.id == answer.id }))
        assertEquals(answer.id, restored.activeAnswerId)
        assertEquals("", restored.attach(AppConfig()).apiKey)
        assertEquals("fixture-other-secret", ProviderConnections.transcription(restored.attach(AppConfig())).apiKey)
        assertEquals("fixture-other-secret", restored.activate("other-answer").attach(AppConfig()).apiKey)
    }

    @Test fun retryUsesOriginalCredentialsAfterPurposeSwitchesButRejectsRevocationAndReplacement() {
        val original = catalog.activate(answer.id).activate(asr.id).attach(AppConfig())
        val switched = catalog.activate("other-answer").activate("other-asr")
        assertTrue(ProviderConnections.credentialsCurrent(original, switched.attach(AppConfig()), answer = true, audio = true))
        val revoked = switched.removeCredential("answer-key").attach(AppConfig())
        assertFalse(ProviderConnections.credentialsCurrent(original, revoked, answer = true, audio = false))
        assertTrue(ProviderConnections.credentialsCurrent(original, revoked, answer = false, audio = true))
        listOf<(ServiceCredential) -> ServiceCredential>(
            { it.copy(value = "replacement-secret") },
            { it.copy(revision = it.revision + 1) },
            { it.copy(endpointIds = emptySet()) },
            { it.copy(value = "", unreadable = true) }
        ).forEach { replace ->
            val changed = switched.copy(credentials = switched.credentials.map { if (it.id == "asr-key") replace(it) else it })
            assertFalse(ProviderConnections.credentialsCurrent(original, changed.attach(AppConfig()), answer = false, audio = true))
            assertTrue(ProviderConnections.credentialsCurrent(original, changed.attach(AppConfig()), answer = true, audio = false))
        }
        assertFalse(ProviderConnections.credentialsCurrent(original,
            switched.removeEndpoint("shared").attach(AppConfig()), answer = true, audio = true))
    }
}
