package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ResponsesAdapterTest {
    @Test fun responsesKeepSystemTextImageAndHistoryFormat() {
        val request = ResponsesAdapter.request(AppConfig(), listOf(ChatMessage("assistant", "old")), "question", "data:image/png;base64,AAAA")
        assertEquals("responses", request.route)
        assertEquals(false, request.body["store"])
        assertEquals("input_image", request.body.items("input").last().wireObject().items("content").last().wireObject().text("type"))
    }
    @Test fun responseMustConfirmCompletionAndMatchAccumulatedText() {
        val decoder = ResponsesAdapter.decoder(AppConfig()); val chunks = StringBuilder()
        decoder.accept("", mapOf("type" to "response.output_text.delta", "delta" to "hello")) { chunks.append(it) }
        assertThrows(ProviderException::class.java) { decoder.finish {} }
        decoder.accept("", mapOf("type" to "response.completed", "response" to mapOf("status" to "completed",
            "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to "hello"))))))) {}
        assertEquals("hello", decoder.finish {}); assertEquals("hello", chunks.toString())
    }
    @Test fun failureAndUnsupportedDeepseekCapabilitiesCannotLookSuccessful() {
        assertThrows(ProviderException::class.java) { ResponsesAdapter.decoder(AppConfig()).accept("response.incomplete", emptyMap()) {} }
        assertThrows(ProviderException::class.java) { ResponsesAdapter.decoder(AppConfig()).accept("__DONE__", emptyMap()) {} }
        assertThrows(ProviderException::class.java) { ProviderCapabilities.validate(AppConfig(providerKind = ProviderKind.DEEPSEEK, model = "deepseek-v4-pro"), true) }
        assertFalse(ProviderCapabilities.search(AppConfig(providerKind = ProviderKind.DEEPSEEK, model = "deepseek-flash")))
    }
    @Test fun completedWithoutFinalTextCannotValidatePartialDeltas() {
        val decoder = ResponsesAdapter.decoder(AppConfig())
        decoder.accept("response.output_text.delta", mapOf("delta" to "partial")) {}
        assertThrows(ProviderException::class.java) {
            decoder.accept("response.completed", mapOf("response" to mapOf("status" to "completed", "output" to emptyList<Any>()))) {}
        }
        assertFalse(decoder.complete)
    }
}
