package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class RemoteModelTest {
    @Test fun absentCapabilitiesStayUnknownWhileExplicitEmptyListsStayKnown() {
        val unknown = RemoteModel.decode(mapOf("id" to "vendor/model:latest"), ApiProtocol.RESPONSES)!!
        assertEquals("vendor/model:latest", unknown.id)
        assertNull(unknown.inputModalities)
        assertNull(unknown.outputModalities)
        assertNull(unknown.maxOutputTokens)
        assertNull(unknown.reasoningLevels)
        assertNull(unknown.generationMethods)
        val known = RemoteModel.decode(mapOf("id" to "fixture", "input_modalities" to emptyList<String>(),
            "output_modalities" to emptyList<String>(), "effort" to mapOf("supported_levels" to emptyList<String>()),
            "supportedGenerationMethods" to emptyList<String>()), ApiProtocol.RESPONSES)!!
        assertEquals(emptySet<String>(), known.inputModalities)
        assertEquals(emptySet<String>(), known.outputModalities)
        assertEquals(emptyList<String>(), known.reasoningLevels)
        assertEquals(emptySet<String>(), known.generationMethods)
    }

    @Test fun advertisedMetadataAndGeminiGenerationMethodsArePreserved() {
        val model = RemoteModel.decode(mapOf("id" to "fixture-model", "input_modalities" to listOf("text", "image"),
            "output_modalities" to listOf("text"), "max_output_tokens" to 32768L,
            "effort" to mapOf("supported_levels" to listOf("none", "low", "high"))), ApiProtocol.RESPONSES)!!
        assertEquals(setOf("text", "image"), model.inputModalities)
        assertEquals(setOf("text"), model.outputModalities)
        assertEquals(32768, model.maxOutputTokens)
        assertEquals(listOf("none", "low", "high"), model.reasoningLevels)
        val gemini = RemoteModel.decode(mapOf("name" to "models/gemini-fixture", "outputTokenLimit" to 8192,
            "supportedGenerationMethods" to listOf("generateContent", "countTokens")), ApiProtocol.GEMINI)!!
        assertEquals("gemini-fixture", gemini.id)
        assertEquals(8192, gemini.maxOutputTokens)
        assertEquals(setOf("generateContent", "countTokens"), gemini.generationMethods)
        assertNull(gemini.inputModalities)
        assertNull(gemini.reasoningLevels)
    }

    @Test fun invalidIdsAreRejectedAndMalformedCapabilitiesRemainUnknown() {
        listOf("", "model\nforged", "x".repeat(201)).forEach { id ->
            assertNull(RemoteModel.decode(mapOf("id" to id), ApiProtocol.CHAT_COMPLETIONS))
        }
        val malformed = RemoteModel.decode(mapOf("id" to "fixture", "input_modalities" to listOf("text", 1),
            "effort" to mapOf("supported_levels" to "high"), "max_output_tokens" to Long.MAX_VALUE), ApiProtocol.RESPONSES)!!
        assertNull(malformed.inputModalities)
        assertNull(malformed.reasoningLevels)
        assertNull(malformed.maxOutputTokens)
    }
}
