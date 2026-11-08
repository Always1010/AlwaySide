package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleConfigurationTest {
    private fun catalog(kind: ProviderKind = ProviderKind.OPENAI,
                        address: String = "https://api.openai.com/v1",
                        protocol: ApiProtocol = ApiProtocol.RESPONSES) = ServiceCatalog(
        providers = listOf(ServiceProvider("provider", "服务商", kind)),
        endpoints = listOf(ServiceEndpoint("endpoint", "provider", "接口", address, protocol)),
        credentials = listOf(ServiceCredential("key", "provider", "测试 Key", "fixture-secret", 9, setOf("endpoint"))),
    )

    @Test fun onlyOfficialOpenAiCredentialsCanBeUsed() {
        assertThrows(IllegalArgumentException::class.java) {
            SubtitleConfiguration.request(catalog(kind = ProviderKind.CUSTOM), "key", "fixture-model")
        }
        listOf("http://api.openai.com/v1", "https://api.openai.com.evil.example/v1",
            "https://user@api.openai.com/v1", "https://api.openai.com:444/v1",
            "https://api.openai.com/v1?proxy=1", "https://api.openai.com/v1#fragment",
            "https://api.openai.com/v1/../v1", "https://api.openai.com/v1/proxy",
            "https://api.openai.com/%761", "https://example.com/v1").forEach { address ->
            assertFalse(address, SubtitleConfiguration.official(catalog(address = address).endpoints.single()))
        }
        assertFalse(SubtitleConfiguration.official(catalog(protocol = ApiProtocol.CLAUDE).endpoints.single()))
        assertThrows(IllegalStateException::class.java) {
            SubtitleConfiguration.request(catalog(address = "https://example.com/v1"), "key", "fixture-model")
        }
    }

    @Test fun deletedUnreadableOrReboundKeysFailBeforeRequest() {
        val saved = catalog()
        assertThrows(IllegalStateException::class.java) { SubtitleConfiguration.request(saved, "missing", "fixture-model") }
        listOf(saved.credentials.single().copy(unreadable = true), saved.credentials.single().copy(value = "")).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                SubtitleConfiguration.request(saved.copy(credentials = listOf(key)), "key", "fixture-model")
            }
        }
        listOf(saved.credentials.single().copy(endpointIds = emptySet())).forEach { key ->
            assertThrows(IllegalStateException::class.java) {
                SubtitleConfiguration.request(saved.copy(credentials = listOf(key)), "key", "fixture-model")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            SubtitleConfiguration.request(saved.copy(endpoints = saved.endpoints.map { it.copy(providerId = "other") }), "key", "fixture-model")
        }
    }

    @Test fun requestUsesCurrentEncryptedKeyAndIndependentCleanTranslationConfiguration() {
        val request = SubtitleConfiguration.request(catalog(address = "https://api.openai.com:443/v1/",
            protocol = ApiProtocol.CHAT_COMPLETIONS), "key", "fixture-model")
        assertEquals(ProviderKind.OPENAI, request.providerKind)
        assertEquals("https://api.openai.com/v1", request.baseUrl)
        assertEquals("fixture-secret", request.apiKey)
        assertEquals(9L, request.credentialRevision)
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, request.apiProtocol)
        assertEquals("fixture-model", request.model)
        assertEquals("key", request.credentialId)
        assertEquals("endpoint", request.endpointId)
        assertTrue(request.instructions.isEmpty() && request.defaultQuestion.isEmpty() && request.audioQuestion.isEmpty() && request.textQuestion.isEmpty())
        assertTrue(request.continuationMessages.isEmpty() && request.connections.isEmpty())
        assertFalse(request.webSearch)
        assertEquals(0, request.maxOutputTokens)
        assertEquals("", request.reasoning)
        assertNull(request.temperature)
        assertNull(request.topP)
        listOf("", "bad model", "model\n", "x".repeat(201)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SubtitleConfiguration.request(catalog(), "key", invalid) }
        }
    }
}
