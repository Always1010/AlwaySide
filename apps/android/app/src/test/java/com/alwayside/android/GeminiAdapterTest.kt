package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAdapterTest {
    private val config = AppConfig(providerKind = ProviderKind.GEMINI, apiProtocol = ApiProtocol.GEMINI,
        model = "gemini-2.5-flash", reasoning = "", maxOutputTokens = 0, instructions = "System")

    @Test fun nativeRequestUsesContentsSystemInstructionAndSseRoute() {
        val request = GeminiAdapter.request(config.copy(model = "models/gemini-2.5-flash", maxOutputTokens = 4096, topP = 0.8),
            listOf(ChatMessage("system", "ignored"), ChatMessage("user", "First"), ChatMessage("assistant", "Previous")), "Next", null)
        assertEquals("models/gemini-2.5-flash:streamGenerateContent", request.route)
        assertEquals(mapOf("alt" to "sse"), request.query)
        assertEquals(mapOf("parts" to listOf(mapOf("text" to "System"))), request.body["systemInstruction"])
        assertEquals(listOf(
            mapOf("role" to "user", "parts" to listOf(mapOf("text" to "First"))),
            mapOf("role" to "model", "parts" to listOf(mapOf("text" to "Previous"))),
            mapOf("role" to "user", "parts" to listOf(mapOf("text" to "Next"))),
        ), request.body["contents"])
        assertEquals(mapOf("maxOutputTokens" to 4096, "topP" to 0.8), request.body["generationConfig"])
        assertFalse(request.body.containsKey("stream"))
        assertFalse(request.body.containsKey("model"))
    }

    @Test fun base64ImageBecomesInlineDataAndNoDataUrlIsSent() {
        val body = request(config, "data:image/png;base64,AQID")
        val parts = body.items("contents").last().wireObject().items("parts")
        assertEquals(mapOf("inlineData" to mapOf("mimeType" to "image/png", "data" to "AQID")), parts[1])
        listOf("https://example.com/a.png", "data:image/png;base64,", "data:image/png;base64,A===", "data:text/plain;base64,AQID").forEach {
            assertThrows(IllegalArgumentException::class.java) { request(config, it) }
        }
    }

    @Test fun modelPrefixIsRemovedExactlyOnceAndPathInjectionIsRejected() {
        listOf("models/models/gemini-2.5-flash", "../gemini", "gemini/../bad", "gemini?key=secret", "gemini:generateContent", "gemini%2Fbad", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { request(config.copy(model = it)) }
        }
    }

    @Test fun reasoningMapsToModelSpecificBudgetOrLevel() {
        assertEquals(mapOf("thinkingBudget" to 0, "includeThoughts" to false), thinking("gemini-2.5-flash", "none"))
        assertEquals(mapOf("thinkingBudget" to 512, "includeThoughts" to false), thinking("gemini-2.5-flash-lite", "minimal"))
        assertEquals(mapOf("thinkingBudget" to 1024, "includeThoughts" to false), thinking("gemini-2.5-flash", "low"))
        assertEquals(mapOf("thinkingBudget" to 8192, "includeThoughts" to false), thinking("gemini-2.5-pro", "medium"))
        assertEquals(32768, thinking("gemini-2.5-pro", "high")["thinkingBudget"])
        assertEquals(24576, thinking("gemini-2.5-flash", "high")["thinkingBudget"])
        assertEquals(mapOf("thinkingLevel" to "MEDIUM", "includeThoughts" to false), thinking("gemini-3.1-pro-preview", "medium"))
        assertEquals("MINIMAL", thinking("gemini-3-flash-preview", "minimal")["thinkingLevel"])
        assertEquals("LOW", thinking("gemini-3.8-flash", "low")["thinkingLevel"])
        assertFalse(request(config).containsKey("generationConfig"))
    }

    @Test fun explicitUnsupportedOptionsFailBeforeNetworking() {
        listOf(config.copy(verbosity = "high"), config.copy(reasoning = "xhigh"), config.copy(reasoning = "max"),
            config.copy(model = "gemini-2.5-pro", reasoning = "none"), config.copy(model = "gemini-3-pro-preview", reasoning = "medium"),
            config.copy(model = "gemini-3.1-pro-preview", reasoning = "none"), config.copy(model = "gemini-3.8-flash", reasoning = "minimal"),
            config.copy(model = "unknown", reasoning = "low"), config.copy(model = "unknown", webSearch = true),
            config.copy(model = "gemini-1.5-pro", webSearch = true), config.copy(model = "gemini-2.5-flash-image", webSearch = true),
            config.copy(model = "gemini-2.0-flash-lite", webSearch = true), config.copy(temperature = Double.NaN)).forEach {
            assertThrows(IllegalArgumentException::class.java) { request(it) }
        }
    }

    @Test fun searchUsesGenerateContentToolSchema() {
        assertEquals(listOf(mapOf("google_search" to emptyMap<String, Any?>())), request(config.copy(webSearch = true))["tools"])
        assertEquals(0.6, request(config.copy(temperature = 0.6)).obj("generationConfig")["temperature"])
    }

    @Test fun streamingTextExcludesThoughtPartsAndWaitsForEof() {
        val decoder = GeminiAdapter.decoder(config)
        val emitted = StringBuilder()
        decoder.accept("message", chunk(parts = listOf(mapOf("text" to "Hidden", "thought" to true), mapOf("text" to "Hello"))), { emitted.append(it); Unit })
        decoder.accept("message", chunk(" world", "STOP"), { emitted.append(it); Unit })
        assertEquals("Hello world", emitted.toString())
        assertFalse(decoder.complete)
        decoder.accept("message", mapOf("usageMetadata" to mapOf("totalTokenCount" to 10)), { emitted.append(it); Unit })
        assertEquals("Hello world", decoder.finish({ emitted.append(it); Unit }))
        assertTrue(decoder.complete)
        assertFalse(decoder.searchUsed)
        assertEquals("Hello world", decoder.finish({ emitted.append(it); Unit }))
    }

    @Test fun groundingSourcesAfterStopAreCollectedDeduplicatedAndSanitized() {
        val decoder = GeminiAdapter.decoder(config)
        decoder.accept("message", chunk("Answer", "STOP")) {}
        val sources = listOf(mapOf("web" to mapOf("uri" to "https://example.com/source", "title" to "Source")),
            mapOf("web" to mapOf("uri" to "https://example.com/source", "title" to "Source")),
            mapOf("web" to mapOf("uri" to "javascript:alert(1)", "title" to "Bad")))
        decoder.accept("message", mapOf("candidates" to listOf(mapOf("groundingMetadata" to mapOf("groundingChunks" to sources))))) {}
        assertEquals("Answer\n\n来源：\n- [Source](https://example.com/source)", decoder.finish {})
        assertTrue(decoder.searchUsed)
    }

    @Test fun searchDiagnosticRequiresGroundingMetadataRatherThanAnswerClaims() {
        val plain = GeminiAdapter.decoder(config)
        plain.accept("message", chunk("I searched the web: https://example.com", "STOP")) {}
        plain.finish {}
        assertFalse(plain.searchUsed)
        val grounded = GeminiAdapter.decoder(config)
        grounded.accept("message", mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to "Answer"))),
            "finishReason" to "STOP", "groundingMetadata" to mapOf("webSearchQueries" to listOf("example query")))))) {}
        grounded.finish {}
        assertTrue(grounded.searchUsed)
    }

    @Test fun searchSuggestionsAreSeparateMetadataAndMayArriveAfterStop() {
        val decoder = GeminiAdapter.decoder(config)
        assertEquals("", decoder.searchSuggestions)
        decoder.accept("message", chunk("Answer", "STOP")) {}
        val html = "<style>.suggestion{color:blue}</style><a href=\"https://www.google.com/search?q=example\">Example</a>"
        decoder.accept("message", groundingSuggestions(html)) {}
        decoder.accept("message", mapOf("usageMetadata" to mapOf("totalTokenCount" to 1))) {}
        assertEquals(html, decoder.searchSuggestions)
        assertFalse(decoder.complete)
        assertEquals("Answer", decoder.finish {})
        assertEquals(html, decoder.searchSuggestions)
    }

    @Test fun searchSuggestionsHaveBoundedSizeAndUseGenerateContentMetadataOnly() {
        val decoder = GeminiAdapter.decoder(config)
        val interactionResult = mapOf("type" to "google_search_result", "result" to listOf(mapOf("search_suggestions" to "ignored")))
        decoder.accept("message", mapOf("steps" to listOf(interactionResult))) {}
        assertEquals("", decoder.searchSuggestions)
        decoder.accept("message", groundingSuggestions("x".repeat(32768))) {}
        assertEquals(32768, decoder.searchSuggestions.length)
        assertThrows(ProviderException::class.java) { decoder.accept("message", groundingSuggestions("x".repeat(32769))) {} }
    }

    @Test fun truncationBlockedPromptAndProviderErrorsAreRejected() {
        listOf(chunk("partial", "MAX_TOKENS"), chunk("partial", "SAFETY"), chunk("partial", "RECITATION"),
            mapOf("promptFeedback" to mapOf("blockReason" to "SAFETY")), mapOf("error" to mapOf("message" to "Quota exceeded"))).forEach {
            val decoder = GeminiAdapter.decoder(config)
            assertThrows(ProviderException::class.java) { decoder.accept("message", it) {} }
            assertFalse(decoder.complete)
        }
    }

    @Test fun eofRequiresCandidateNonemptyAnswerAndNormalTerminalReason() {
        listOf(emptyMap(), mapOf("candidates" to emptyList<Any?>()), chunk("partial"), chunk("", "STOP"),
            chunk(parts = listOf(mapOf("text" to "Hidden", "thought" to true)), reason = "STOP")).forEach {
            val decoder = GeminiAdapter.decoder(config)
            decoder.accept("message", it) {}
            assertThrows(ProviderException::class.java) { decoder.finish {} }
            assertFalse(decoder.complete)
        }
    }

    private fun request(value: AppConfig, image: String? = null) = GeminiAdapter.request(value, emptyList(), "Question", image).body
    private fun groundingSuggestions(html: String): Map<String, Any?> = mapOf("candidates" to listOf(mapOf(
        "groundingMetadata" to mapOf("searchEntryPoint" to mapOf("renderedContent" to html)))))
    private fun thinking(model: String, effort: String) = request(config.copy(model = model, reasoning = effort)).obj("generationConfig").obj("thinkingConfig")
    private fun chunk(text: String = "", reason: String = "", parts: List<Map<String, Any?>> = listOf(mapOf("text" to text))): Map<String, Any?> =
        mapOf("candidates" to listOf(mapOf("index" to 0, "content" to mapOf("parts" to parts), "finishReason" to reason)))

}
