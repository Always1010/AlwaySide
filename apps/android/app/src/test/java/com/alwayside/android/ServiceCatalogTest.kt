package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

/** Offline business regressions for provider cards and independently managed API keys. */
class ServiceCatalogTest {
    private fun draft(name: String, providerId: String = "first", value: String = "fixture-$name-secret") =
        ApiKeyDraft(providerId = providerId, providerName = if (providerId == "first") "第一家" else "第二家",
            kind = ProviderKind.CUSTOM, name = name, value = value, baseUrl = "https://$providerId.example/v1",
            answerProtocol = ApiProtocol.RESPONSES, transcriptionApi = FileTranscriptionApi.OPENAI_COMPATIBLE)

    private fun ServiceCatalog.key(name: String) = credentials.single { it.name == name }
    private fun card(id: String, purpose: ModelPurpose, key: ServiceCredential) =
        ModelProfile(id, "不应作为卡片名称", purpose, credentialId = key.id,
            model = "fixture-$id", providerId = key.providerId)

    private fun keys() = ServiceCatalog().upsertKey(draft("answer-key"))
        .upsertKey(draft("asr-key")).upsertKey(draft("other-key", "second"))

    private fun catalog(): ServiceCatalog {
        val keys = keys()
        return keys.upsertCard(card("answer", ModelPurpose.ANSWER, keys.key("answer-key")))
            .upsertCard(card("asr", ModelPurpose.TRANSCRIPTION, keys.key("asr-key"))
                .copy(transcriptionPrompt = "保留专有名词", language = "zh"))
            .upsertCard(card("other-answer", ModelPurpose.ANSWER, keys.key("other-key")))
            .upsertCard(card("other-asr", ModelPurpose.TRANSCRIPTION, keys.key("other-key")))
    }

    @Test fun firstCardOfEachPurposeIsSelectedAndLaterAdditionsKeepTheSelection() {
        val saved = catalog()
        saved.validate()
        assertEquals("answer", saved.activeAnswerId)
        assertEquals("asr", saved.activeTranscriptionId)
        assertEquals("fixture-answer-key-secret", saved.attach(AppConfig(apiKey = "stale-secret")).apiKey)
        assertEquals(listOf("第一家", "第一家", "第二家", "第二家"), saved.profiles.map { it.name })
        assertThrows(IllegalArgumentException::class.java) {
            saved.upsertCard(card("duplicate-answer", ModelPurpose.ANSWER, saved.key("asr-key")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            saved.upsertCard(card("duplicate-asr", ModelPurpose.TRANSCRIPTION, saved.key("answer-key")))
        }
        val active = saved.activate("answer").activate("asr")
        assertSame(active, active.activate("answer"))
        assertSame(active, active.activate("asr"))
        val answer = active.attach(AppConfig())
        val asr = ProviderConnections.transcription(answer)
        assertEquals("fixture-answer-key-secret", answer.apiKey)
        assertEquals("fixture-asr-key-secret", asr.apiKey)
        assertNotEquals(answer.endpointId, asr.endpointId)
        assertEquals("fixture-asr", asr.transcriptionModel)
        assertEquals("保留专有名词", asr.transcriptionPrompt)
        assertEquals("zh", asr.transcriptionLanguage)
    }

    @Test fun emptyListsStayUnselectedAndOnlySavingANewFirstCardSelectsIt() {
        val keys = keys()
        assertNull(keys.activeAnswerId)
        assertNull(keys.activeTranscriptionId)
        assertThrows(IllegalArgumentException::class.java) {
            keys.upsertCard(card("invalid", ModelPurpose.ANSWER, keys.key("answer-key")).copy(model = ""))
        }
        assertNull(keys.activeAnswerId)
        val first = keys.upsertCard(card("one", ModelPurpose.ANSWER, keys.key("answer-key")))
        assertEquals("one", first.activeAnswerId)
        assertNull(first.activeTranscriptionId)
        val empty = first.removeProfile("one")
        assertNull(empty.activeAnswerId)
        assertTrue(empty.profiles.isEmpty())
        val restored = empty.upsertCard(card("new-first", ModelPurpose.ANSWER, empty.key("answer-key")))
        assertEquals("new-first", restored.activeAnswerId)
        val second = restored.upsertCard(card("second", ModelPurpose.ANSWER, restored.key("other-key")))
        assertEquals("new-first", second.activeAnswerId)
        val switched = second.activate("second")
        assertEquals("second", switched.activeAnswerId)
        assertSame(switched, switched.activate("second"))
        assertEquals("second", switched.upsertCard(switched.profiles.first { it.id == "new-first" }.copy(model = "updated")).activeAnswerId)
    }

    @Test fun keyNamesAreTrimmedAndUniqueWithinAStableProvider() {
        val first = ServiceCatalog().upsertKey(draft("  Production  ", value = "fixture-production-secret"))
        val key = first.key("Production")
        assertThrows(IllegalArgumentException::class.java) { first.upsertKey(draft("production")) }
        val twoProviders = first.upsertKey(draft("production", "second"))
        assertEquals(2, twoProviders.credentials.size)
        assertThrows(IllegalArgumentException::class.java) {
            twoProviders.upsertKey(draft("Moved", "second", "replacement-secret").copy(id = key.id))
        }
        assertThrows(IllegalArgumentException::class.java) {
            twoProviders.upsertKey(draft("Production").copy(id = key.id, kind = ProviderKind.OPENAI))
        }
        assertThrows(IllegalArgumentException::class.java) { first.upsertKey(draft("empty", value = "")) }
    }

    @Test fun cardsRequireAReadyOwnProviderKeyAndCompatibleModelOptions() {
        val keys = keys()
        val own = card("answer", ModelPurpose.ANSWER, keys.key("answer-key"))
        assertThrows(IllegalArgumentException::class.java) { keys.upsertCard(own.copy(model = "")) }
        assertThrows(IllegalArgumentException::class.java) {
            keys.upsertCard(own.copy(credentialId = keys.key("other-key").id))
        }
        val qwen = keys.upsertKey(draft("qwen-key", "qwen").copy(providerName = "千问",
            kind = ProviderKind.QWEN, answerProtocol = ApiProtocol.CHAT_COMPLETIONS))
        assertThrows(IllegalArgumentException::class.java) {
            qwen.upsertCard(card("qwen-answer", ModelPurpose.ANSWER, qwen.key("qwen-key"))
                .copy(model = "qwen3-max", options = GenerationOptions(reasoning = "high")))
        }
        val corrupted = keys.copy(profiles = listOf(own.copy(
            endpointId = keys.key("answer-key").endpointIds.single(), credentialId = keys.key("other-key").id)))
        assertThrows(IllegalArgumentException::class.java) { corrupted.validate() }
        assertEquals("", corrupted.project(AppConfig(apiKey = "stale-secret"), own.id).apiKey)
    }

    @Test fun editingAKeyPreservesBlankSecretsAndRequiresReentryForAnotherOrigin() {
        val saved = catalog()
        val original = saved.key("answer-key")
        val renamed = saved.upsertKey(draft("Renamed", value = "").copy(id = original.id))
        assertEquals(original.value, renamed.key("Renamed").value)
        assertEquals(original.revision, renamed.key("Renamed").revision)
        val changedPath = renamed.upsertKey(draft("Renamed", value = "").copy(id = original.id,
            baseUrl = "https://first.example/alternate/v1"))
        assertEquals(original.revision + 1, changedPath.key("Renamed").revision)
        assertEquals(original.endpointIds, changedPath.key("Renamed").endpointIds)
        assertThrows(IllegalArgumentException::class.java) {
            changedPath.upsertKey(draft("Renamed", value = "").copy(id = original.id,
                baseUrl = "https://unrelated.example/v1"))
        }
        val replaced = changedPath.upsertKey(draft("Renamed", value = "new-secret").copy(id = original.id,
            baseUrl = "https://unrelated.example/v1"))
        assertEquals("new-secret", replaced.key("Renamed").value)
        assertEquals(original.revision + 2, replaced.key("Renamed").revision)
        assertEquals("https://unrelated.example/v1", replaced.project(AppConfig(), "answer").baseUrl)
    }

    @Test fun switchingACardKeyUsesItsEndpointAndKeepsGenerationParameters() {
        val active = catalog().activate("answer").activate("asr")
        val profile = active.profiles.single { it.id == "answer" }.copy(
            options = GenerationOptions(maxOutputTokens = 2048, reasoning = "high", verbosity = "low", temperature = 0.4))
        val changed = active.upsertCard(profile.copy(credentialId = active.key("asr-key").id))
        val edited = changed.profiles.single { it.id == "answer" }
        assertEquals(active.key("asr-key").endpointIds.single(), edited.endpointId)
        assertEquals(profile.options, edited.options)
        assertEquals("answer", changed.activeAnswerId)
        assertEquals("asr", changed.activeTranscriptionId)
        assertEquals("fixture-asr-key-secret", changed.attach(AppConfig()).apiKey)
    }

    @Test fun deletingAKeyRemovesItsEndpointAndUnbindsOnlyAffectedCardsWithoutFallback() {
        val active = catalog().activate("answer").activate("asr")
        val removed = active.key("answer-key")
        val next = active.removeCredential(removed.id)
        next.validate()
        assertFalse(next.endpoints.any { it.id in removed.endpointIds })
        assertNull(next.activeAnswerId)
        assertEquals("asr", next.activeTranscriptionId)
        val retained = next.profiles.single { it.id == "answer" }
        assertEquals("first", retained.providerId)
        assertEquals("first", next.providerIdOf(retained))
        assertNull(retained.credentialId)
        assertNull(retained.endpointId)
        assertEquals("", next.attach(AppConfig(apiKey = "stale-secret")).apiKey)
        assertEquals("fixture-asr-key-secret", ProviderConnections.transcription(next.attach(AppConfig())).apiKey)
        assertThrows(IllegalArgumentException::class.java) { next.activate("answer") }
    }

    @Test fun publicCodecContainsNoSecretsAndPreservesProviderIdentityAfterKeyDeletion() {
        val active = catalog().activate("answer").activate("asr")
        val saved = active.removeCredential(active.key("answer-key").id)
        val metadata = ServiceCatalogCodec.encode(saved)
        active.credentials.forEach { assertFalse(metadata.toString().contains(it.value)) }
        val restored = ServiceCatalogCodec.decode(metadata) { id -> saved.credentials.single { it.id == id }.value to false }
        assertEquals(saved, restored)
        assertEquals("first", restored.profiles.single { it.id == "answer" }.providerId)
        assertEquals("fixture-asr-key-secret", ProviderConnections.transcription(restored.attach(AppConfig())).apiKey)
    }

    @Test fun oneUnreadableKeyLeavesOtherKeysUsableWithoutFallback() {
        val active = catalog().activate("answer").activate("asr")
        val brokenId = active.key("answer-key").id
        val restored = ServiceCatalogCodec.decode(ServiceCatalogCodec.encode(active)) { id ->
            if (id == brokenId) "" to true else active.credentials.single { it.id == id }.value to false
        }
        assertNotNull(restored.readiness(restored.profiles.single { it.id == "answer" }))
        assertEquals("answer", restored.activeAnswerId)
        assertEquals("", restored.attach(AppConfig(apiKey = "stale-secret")).apiKey)
        assertEquals("fixture-asr-key-secret", ProviderConnections.transcription(restored.attach(AppConfig())).apiKey)
        assertEquals("fixture-other-key-secret", restored.activate("other-answer").attach(AppConfig()).apiKey)
    }

    @Test fun retryKeepsOriginalPurposeCredentialsButRejectsTheirRevocationOrReplacement() {
        val saved = catalog()
        val original = saved.activate("answer").activate("asr").attach(AppConfig())
        val switched = saved.activate("other-answer").activate("other-asr")
        assertTrue(ProviderConnections.credentialsCurrent(original, switched.attach(AppConfig()), answer = true, audio = true))
        val revoked = switched.removeCredential(saved.key("answer-key").id).attach(AppConfig())
        assertFalse(ProviderConnections.credentialsCurrent(original, revoked, answer = true, audio = false))
        assertTrue(ProviderConnections.credentialsCurrent(original, revoked, answer = false, audio = true))
        val replaced = switched.upsertKey(draft("asr-key", value = "replacement-secret")
            .copy(id = saved.key("asr-key").id)).attach(AppConfig())
        assertFalse(ProviderConnections.credentialsCurrent(original, replaced, answer = false, audio = true))
        assertTrue(ProviderConnections.credentialsCurrent(original, replaced, answer = true, audio = false))
    }

    @Test fun revokingOneKeyCancelsOnlyItsRequestsAndPurposeSwitchingDoesNotCancel() {
        val active = catalog().activate("answer").activate("asr")
        val answerToken = RequestCancellation()
        val asrToken = RequestCancellation()
        ActiveServiceRequests.revoke(active)
        try {
            ActiveServiceRequests.track(active.project(AppConfig(), "answer"), answerToken)
            ActiveServiceRequests.track(active.project(AppConfig(), "asr"), asrToken)
            ActiveServiceRequests.revoke(active.activate("other-answer").activate("other-asr"))
            answerToken.throwIfCancelled()
            asrToken.throwIfCancelled()
            ActiveServiceRequests.revoke(active.removeCredential(active.key("asr-key").id))
            answerToken.throwIfCancelled()
            assertThrows(java.util.concurrent.CancellationException::class.java) { asrToken.throwIfCancelled() }
        } finally {
            ActiveServiceRequests.release(answerToken)
            ActiveServiceRequests.release(asrToken)
        }
    }
}
