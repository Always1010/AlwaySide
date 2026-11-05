package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMessagesAdapterTest {
    private val config = AppConfig(model = "claude-sonnet-4-6", reasoning = "", providerKind = ProviderKind.CLAUDE, apiProtocol = ApiProtocol.CLAUDE)
    private fun m(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)
    private fun feed(decoder: AnswerDecoder, type: String, vararg fields: Pair<String, Any?>) {
        decoder.accept(type, m("type" to type, *fields)) {}
    }
    private fun start(decoder: AnswerDecoder) = feed(decoder, "message_start", "message" to m("content" to emptyList<Any?>()))
    private fun block(decoder: AnswerDecoder, index: Int, content: Map<String, Any?>) = feed(decoder, "content_block_start", "index" to index, "content_block" to content)
    private fun delta(decoder: AnswerDecoder, index: Int, content: Map<String, Any?>) = feed(decoder, "content_block_delta", "index" to index, "delta" to content)
    private fun close(decoder: AnswerDecoder, index: Int) = feed(decoder, "content_block_stop", "index" to index)
    private fun stop(decoder: AnswerDecoder, reason: String) {
        feed(decoder, "message_delta", "delta" to m("stop_reason" to reason))
        feed(decoder, "message_stop")
    }

    @Test fun requestHasSystemHistoryImageAndRequiredTokenLimit() {
        val adapter = ClaudeMessagesAdapter()
        val request = adapter.request(config.copy(maxOutputTokens = 0, instructions = "Translate", webSearch = true),
            listOf(ChatMessage("system", "discard"), ChatMessage("user", "old"), ChatMessage("assistant", "reply")),
            "question", "data:image/png;base64,AQID")
        assertEquals("messages", request.route)
        assertEquals(8192, request.body["max_tokens"])
        assertEquals("Translate", request.body["system"])
        assertEquals(true, request.body["stream"])
        val messages = request.body.items("messages").map { it.wireObject() }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals("old", messages[0]["content"])
        val content = messages.last().items("content").map { it.wireObject() }
        assertEquals(m("type" to "base64", "media_type" to "image/png", "data" to "AQID"), content[0]["source"])
        assertEquals(m("type" to "text", "text" to "question"), content[1])
        assertEquals(listOf(m("type" to "web_search_20250305", "name" to "web_search")), request.body["tools"])
        assertThrows(IllegalArgumentException::class.java) { adapter.request(config, emptyList(), "q", "https://example.com/p.png") }
    }

    @Test fun validatesVersionAndModelSpecificGenerationWithoutInventedFields() {
        fun body(c: AppConfig) = ClaudeMessagesAdapter().request(c, emptyList(), "q", null).body
        assertFalse(body(config).containsKey("thinking"))
        assertEquals(m("type" to "adaptive"), body(config.copy(reasoning = "max"))["thinking"])
        assertEquals(m("effort" to "max"), body(config.copy(reasoning = "max"))["output_config"])
        assertEquals(m("type" to "disabled"), body(config.copy(reasoning = "none"))["thinking"])
        assertEquals(m("type" to "between_tools"), body(config.copy(model = "claude-sonnet-5-5", reasoning = "none"))["thinking"])
        assertEquals(0.3, body(config.copy(apiVersion = "2023-06-01", temperature = 0.3))["temperature"])
        assertEquals(m("effort" to "low"), body(config.copy(model = "claude-opus-4-5-20251101", reasoning = "low"))["output_config"])
        assertEquals(m("effort" to "xhigh"), body(config.copy(model = "claude-opus-5-5", reasoning = "xhigh"))["output_config"])
        listOf(config.copy(temperature = 0.3), config.copy(topP = 0.99), config.copy(verbosity = "high"),
            config.copy(reasoning = "minimal"), config.copy(reasoning = "xhigh"),
            config.copy(model = "claude-sonnet-4-5", reasoning = "low"),
            config.copy(model = "claude-opus-4-5", reasoning = "max"),
            config.copy(model = "claude-opus-5-5", reasoning = "none"),
            config.copy(model = "claude-mythos-preview", reasoning = "none"),
            config.copy(model = "claude-haiku-4-5", maxOutputTokens = 128000),
            config.copy(apiVersion = "2023-06-01", model = "claude-opus-4-7", temperature = 0.3),
            config.copy(apiVersion = "2023-06-01", reasoning = "low", topP = 0.9)).forEach { c ->
            assertThrows(IllegalArgumentException::class.java) { body(c) }
        }
    }

    @Test fun streamRequiresClosedBlocksStopReasonAndMessageStop() {
        val decoder = ClaudeMessagesAdapter().decoder(config)
        start(decoder)
        block(decoder, 0, m("type" to "text", "text" to "Hello"))
        delta(decoder, 0, m("type" to "text_delta", "text" to " world"))
        close(decoder, 0)
        feed(decoder, "message_delta", "delta" to m("stop_reason" to "end_turn"))
        assertFalse(decoder.complete)
        assertThrows(ProviderException::class.java) { decoder.finish {} }
        val valid = ClaudeMessagesAdapter().decoder(config)
        start(valid); block(valid, 0, m("type" to "text", "text" to "Hello")); close(valid, 0)
        stop(valid, "end_turn")
        assertTrue(valid.complete)
        assertEquals("Hello", valid.finish {})
        assertEquals("Hello", valid.finish {})
        val open = ClaudeMessagesAdapter().decoder(config)
        start(open); block(open, 0, m("type" to "text", "text" to "partial"))
        assertThrows(ProviderException::class.java) { stop(open, "end_turn") }
    }

    @Test fun lengthClientToolRefusalErrorAndMissingStopNeverSucceed() {
        listOf("max_tokens", "tool_use", "refusal", "model_context_window_exceeded", "unknown").forEach { reason ->
            val decoder = ClaudeMessagesAdapter().decoder(config)
            start(decoder); block(decoder, 0, m("type" to "text", "text" to "partial")); close(decoder, 0)
            assertThrows(ProviderException::class.java) { stop(decoder, reason) }
            assertFalse(decoder.complete)
            assertThrows(ProviderException::class.java) { decoder.finish {} }
        }
        val missing = ClaudeMessagesAdapter().decoder(config)
        start(missing)
        assertThrows(ProviderException::class.java) { feed(missing, "message_stop") }
        val error = ClaudeMessagesAdapter().decoder(config)
        assertThrows(ProviderException::class.java) { feed(error, "error", "error" to m("type" to "overloaded_error")) }
        assertThrows(ProviderException::class.java) { error.finish {} }
    }

    @Test fun citationsUseStructuredLocationsAndToolErrorsInside200Fail() {
        val decoder = ClaudeMessagesAdapter().decoder(config)
        start(decoder)
        block(decoder, 0, m("type" to "text", "text" to "Answer"))
        delta(decoder, 0, m("type" to "citations_delta", "citation" to m("type" to "web_search_result_location", "url" to "https://example.com/a", "title" to "Source", "encrypted_index" to "keep")))
        close(decoder, 0); stop(decoder, "end_turn")
        assertEquals("Answer\n\n来源：\n- [Source](https://example.com/a)", decoder.finish {})
        val failed = ClaudeMessagesAdapter().decoder(config)
        start(failed)
        assertThrows(ProviderException::class.java) {
            block(failed, 0, m("type" to "web_search_tool_result", "tool_use_id" to "id", "content" to m("type" to "web_search_tool_result_error", "error_code" to "max_uses_exceeded")))
        }
        assertFalse(failed.complete)
        assertThrows(ProviderException::class.java) { failed.finish {} }
    }

    @Test fun pausePreservesFullRequestNativeBlocksToolInputsAndSignaturesForExplicitRetry() {
        val input = m("query" to "news", "nested" to listOf(true, 1, null))
        val adapter = ClaudeMessagesAdapter { json -> assertEquals("{\"query\":\"news\"}", json); input }
        val request = adapter.request(config, listOf(ChatMessage("user", "old"), ChatMessage("assistant", "old reply")), "q", "data:image/png;base64,AQID")
        val decoder = adapter.decoder(config)
        start(decoder)
        block(decoder, 0, m("type" to "thinking", "thinking" to "", "signature" to "", "future_field" to m("native" to true)))
        delta(decoder, 0, m("type" to "thinking_delta", "thinking" to "summary"))
        delta(decoder, 0, m("type" to "signature_delta", "signature" to "opaque-signature")); close(decoder, 0)
        block(decoder, 1, m("type" to "server_tool_use", "id" to "search1", "name" to "web_search", "input" to emptyMap<String, Any?>(), "caller" to m("type" to "direct")))
        delta(decoder, 1, m("type" to "input_json_delta", "partial_json" to "{\"query\":"))
        delta(decoder, 1, m("type" to "input_json_delta", "partial_json" to "\"news\"}")); close(decoder, 1)
        val results = m("type" to "web_search_tool_result", "tool_use_id" to "search1", "content" to listOf(m("type" to "web_search_result", "url" to "https://example.com", "encrypted_content" to "opaque")))
        block(decoder, 2, results); close(decoder, 2)
        block(decoder, 3, m("type" to "redacted_thinking", "data" to "secret")); close(decoder, 3)
        stop(decoder, "pause_turn")
        val paused = assertThrows(ProviderPausedException::class.java) { decoder.finish {} }
        assertEquals(request.body.items("messages"), paused.messages.dropLast(1))
        val content = paused.messages.last().items("content").map { it.wireObject() }
        assertEquals("opaque-signature", content[0]["signature"])
        assertEquals(m("native" to true), content[0]["future_field"])
        assertEquals(input, content[1]["input"])
        assertEquals(results, content[2])
        assertEquals("secret", content[3]["data"])
        val retry = ClaudeMessagesAdapter().request(config.copy(continuationMessages = paused.messages), listOf(ChatMessage("user", "wrong")), "wrong", null)
        assertEquals(paused.messages, retry.body["messages"])
        assertEquals(4, retry.body.items("messages").size)
    }

    @Test fun nonStreamingMessageUsesSameCompletionAndPauseRules() {
        val adapter = ClaudeMessagesAdapter()
        val request = adapter.request(config, emptyList(), "q", null)
        val decoder = adapter.decoder(config)
        val content = listOf(m("type" to "thinking", "thinking" to "", "signature" to "s"), m("type" to "text", "text" to "done"))
        decoder.accept("message", m("type" to "message", "content" to content, "stop_reason" to "pause_turn")) {}
        val paused = assertThrows(ProviderPausedException::class.java) { decoder.finish {} }
        assertEquals(request.body.items("messages") + m("role" to "assistant", "content" to content), paused.messages)
        val successful = ClaudeMessagesAdapter().decoder(config)
        successful.accept("message", m("type" to "message", "content" to listOf(m("type" to "text", "text" to "done")), "stop_reason" to "end_turn")) {}
        assertEquals("done", successful.finish {})
    }
}
