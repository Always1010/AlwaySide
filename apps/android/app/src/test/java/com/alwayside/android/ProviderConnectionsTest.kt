package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ProviderConnectionsTest {
    @Test fun switchingConnectionsKeepsCredentialsAndGenerationSettingsSeparate() {
        val old = ConnectionConfigCodec.migrateLegacy(AppConfig(apiKey = "first", model = "first-model", webSearch = true))
        val added = ProviderConnections.add(old, ProviderKind.CLAUDE).copy(apiKey = "second", model = "second-model")
        val restored = ProviderConnections.select(added, "legacy")
        assertEquals("first", restored.apiKey); assertEquals("first-model", restored.model); assertTrue(restored.webSearch)
        val second = ProviderConnections.select(restored, added.connectionId)
        assertEquals("second", second.apiKey); assertEquals("second-model", second.model); assertFalse(second.webSearch)
    }

    @Test fun publicMetadataRoundTripNeverContainsCredentials() {
        val profile = ConnectionProfile.capture(AppConfig(apiKey = "must-not-serialize", model = "test"))
        val metadata = ConnectionConfigCodec.encode(profile)
        assertFalse(metadata.toString().contains("must-not-serialize"))
        assertEquals(profile, ConnectionConfigCodec.decode(metadata, "must-not-serialize"))
    }

    @Test fun migrationKeepsCustomEndpointAndSharedTranscription() {
        val old = AppConfig(baseUrl = "https://proxy.example/custom/v1", apiKey = "secret", reasoning = "high")
        val migrated = ConnectionConfigCodec.migrateLegacy(old)
        assertEquals(old.baseUrl, migrated.baseUrl); assertEquals(old.apiKey, migrated.apiKey)
        assertEquals(ProviderKind.CUSTOM, migrated.providerKind)
        assertEquals("high", migrated.reasoning)
        assertEquals(old.baseUrl, ProviderConnections.transcription(migrated).baseUrl)
        assertEquals(migrated, ConnectionConfigCodec.migrateLegacy(migrated))
    }

    @Test fun changingOriginRevokesCredentialAndDeletingBindingUnconfiguresTranscription() {
        val old = ConnectionConfigCodec.migrateLegacy(AppConfig(apiKey = "secret"))
        assertEquals("", ProviderConnections.addressChanged(old, "https://new.example/v1").apiKey)
        val added = ProviderConnections.add(old, ProviderKind.GEMINI)
        val removed = ProviderConnections.remove(added, "legacy")
        assertNull(removed.transcriptionConnectionId)
        assertThrows(ProviderException::class.java) { ProviderConnections.transcription(removed) }
        assertEquals("secret", ProviderConnections.transcription(added).apiKey)
    }
}
