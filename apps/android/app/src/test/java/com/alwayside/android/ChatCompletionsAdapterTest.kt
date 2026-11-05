package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsAdapterTest {
    private val qwen = AppConfig(providerKind = ProviderKind.QWEN, apiProtocol = ApiProtocol.CHAT_COMPLETIONS,
        model = "qwen-plus", reasoning = "", maxOutputTokens = 0)
    private val custom = qwen.copy(providerKind = ProviderKind.CUSTOM, model = "custom-model")

    @Test fun requestUsesChatMessagesAndOpenAiImageBlocks() {
        val image = "data:image/jpeg;base64,AAAA"
        val history = listOf(ChatMessage("system", "untrusted"), ChatMessage("user", "previous question"),
            ChatMessage("assistant", "previous answer"), ChatMessage("user", ""))
        val request = ChatCompletionsAdapter.request(qwen, history, "describe", image)
        assertEquals("chat/completions", request.route)
        assertTrue(request.query.isEmpty())
        assertEquals(true, request.body["stream"])
        assertEquals(mapOf("include_usage" to true), request.body["stream_options"])
        val messages = request.body.items("messages").map { it.wireObject() }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"] })
        assertEquals(qwen.instructions, messages.first()["content"])
        assertEquals("previous question", messages[1]["content"])
        assertEquals(listOf(mapOf("type" to "text", "text" to "describe"),
            mapOf("type" to "image_url", "image_url" to mapOf("url" to image))), messages.last()["content"])
        assertFalse(request.body.containsKey("input"))
    }

    @Test fun textOnlyRequestKeepsTextAsStringAndBoundsHistory() {
        val history = (0..29).map { ChatMessage(if (it % 2 == 0) "user" else "assistant", "turn $it") }
        val messages = ChatCompletionsAdapter.request(qwen.copy(instructions = ""), history, "new", null)
            .body.items("messages").map { it.wireObject() }
        assertEquals(25, messages.size)
        assertEquals("turn 6", messages.first()["content"])
        assertEquals("new", messages.last()["content"])
    }

    @Test fun qwenExtensionsAreTopLevelAndUseTheDocumentedOlderOutputLimit() {
        val body = ChatCompletionsAdapter.request(qwen.copy(reasoning = "none", webSearch = true,
            maxOutputTokens = 4096, temperature = 0.5), emptyList(), "question", null).body
        assertEquals(4096, body["max_tokens"])
        assertEquals(false, body["enable_thinking"])
        assertEquals(true, body["enable_search"])
        assertFalse(body.containsKey("search_options"))
        assertEquals(0.5, body["temperature"])
        listOf("extra_body", "max_output_tokens", "max_completion_tokens", "reasoning", "reasoning_effort", "tools", "web_search_options", "text")
            .forEach { assertFalse("Unexpected $it", body.containsKey(it)) }
    }

    @Test fun newerQwenFamiliesUseCompletionBudgetAndOnlyDocumentedModelsUseEffort() {
        listOf("qwen3.8-max", "qwen3.7-max-2026-05-20", "qwen3.6-plus", "qwen3.5-plus-2026-02-15", "qwen3.5-flash").forEach { model ->
            val body = ChatCompletionsAdapter.request(qwen.copy(model = model, maxOutputTokens = 4096), emptyList(), "q", null).body
            assertEquals(model, 4096, body["max_completion_tokens"])
            assertFalse(body.containsKey("max_tokens"))
        }
        val body = ChatCompletionsAdapter.request(qwen.copy(model = "qwen3.8-max", reasoning = "high"), emptyList(), "q", null).body
        assertEquals("high", body["reasoning_effort"])
        assertFalse(body.containsKey("enable_thinking"))
        assertFalse(body.containsKey("thinking_budget"))
        val older = ChatCompletionsAdapter.request(qwen.copy(model = "qwen3.5-plus", reasoning = "none"), emptyList(), "q", null).body
        assertEquals(false, older["enable_thinking"])
        assertFalse(older.containsKey("reasoning_effort"))
    }

    @Test fun qwenOmniSearchUsesRequiredAgentStrategy() {
        listOf("qwen3.8-omni-flash", "qwen3.5-omni-plus", "qwen3.5-omni-flash-2026-03-15").forEach { model ->
            val body = ChatCompletionsAdapter.request(qwen.copy(model = model, webSearch = true), emptyList(), "q", null).body
            assertEquals(mapOf("search_strategy" to "agent"), body["search_options"])
        }
    }

    @Test fun unsupportedExplicitQwenControlsFailWithoutDroppingThem() {
        listOf(qwen.copy(verbosity = "high"), qwen.copy(reasoning = "low"),
            qwen.copy(model = "qwen3-235b-a22b-thinking-2507", reasoning = "none"),
            qwen.copy(model = "qwen-vl-max", reasoning = "none"), qwen.copy(model = "qwen-coder-plus", webSearch = true),
            qwen.copy(model = "unknown-model", webSearch = true), qwen.copy(temperature = 2.0), qwen.copy(topP = 0.0)).forEach { config ->
            assertThrows(ProviderException::class.java) { ChatCompletionsAdapter.request(config, emptyList(), "q", null) }
        }
    }

    @Test fun customChatUsesStandardFieldsWithoutQwenOrResponsesParameters() {
        val body = ChatCompletionsAdapter.request(custom.copy(maxOutputTokens = 2048, reasoning = "medium",
            verbosity = "low", webSearch = true, topP = 0.8), emptyList(), "q", null).body
        assertEquals(2048, body["max_completion_tokens"])
        assertEquals("medium", body["reasoning_effort"])
        assertEquals("low", body["verbosity"])
        assertEquals(0.8, body["top_p"])
        assertEquals(emptyMap<String, Any?>(), body["web_search_options"])
        listOf("max_tokens", "max_output_tokens", "enable_search", "search_options", "enable_thinking", "extra_body", "reasoning", "text", "tools")
            .forEach { assertFalse("Unexpected $it", body.containsKey(it)) }
        val defaults = ChatCompletionsAdapter.request(custom, emptyList(), "q", null).body
        listOf("max_completion_tokens", "reasoning_effort", "verbosity", "web_search_options", "temperature", "top_p")
            .forEach { assertFalse("Default unexpectedly sent $it", defaults.containsKey(it)) }
    }

    @Test fun continuationAndOtherProvidersAreRejectedInsteadOfSilentlyDiscarded() {
        listOf(qwen.copy(continuationMessages = listOf(mapOf("role" to "assistant"))), qwen.copy(providerKind = ProviderKind.GEMINI),
            qwen.copy(providerKind = ProviderKind.CLAUDE), qwen.copy(providerKind = ProviderKind.DEEPSEEK)).forEach { config ->
            assertThrows(ProviderException::class.java) { ChatCompletionsAdapter.request(config, emptyList(), "q", null) }
        }
    }

    @Test fun normalStreamIgnoresReasoningAndRequiresStopBeforeDone() {
        val decoder = ChatCompletionsAdapter.decoder(qwen)
        val deltas = mutableListOf<String>()
        decoder.accept("message", chunk(delta = mapOf("role" to "assistant", "content" to "", "reasoning_content" to "private reasoning")), deltas::add)
        decoder.accept("message", chunk("Hello "), deltas::add)
        decoder.accept("message", chunk("world"), deltas::add)
        assertFalse(decoder.complete)
        decoder.accept("message", chunk(reason = "stop"), deltas::add)
        assertTrue(decoder.complete)
        decoder.accept("__DONE__", emptyMap(), deltas::add)
        assertEquals("Hello world", decoder.finish(deltas::add))
        assertEquals(listOf("Hello ", "world"), deltas)
        assertEquals("Hello world", decoder.finish(deltas::add))
    }

    @Test fun validStopMayFinishAtEofAndUsageTailIsNotAnAnswer() {
        val decoder = ChatCompletionsAdapter.decoder(qwen)
        decoder.accept("message", chunk("answer", "stop")) {}
        decoder.accept("message", mapOf("choices" to emptyList<Any>(), "usage" to mapOf("total_tokens" to 100))) {}
        assertTrue(decoder.complete)
        assertEquals("answer", decoder.finish {})
    }

    @Test fun truncatedStreamAndPrematureDoneNeverBecomeComplete() {
        listOf(false, true).forEach { sendDone ->
            val decoder = ChatCompletionsAdapter.decoder(qwen)
            decoder.accept("message", chunk("partial")) {}
            assertFalse(decoder.complete)
            if (sendDone) assertThrows(ProviderException::class.java) { decoder.accept("__DONE__", emptyMap()) {} }
            assertThrows(ProviderException::class.java) { decoder.finish {} }
            assertFalse(decoder.complete)
        }
        val empty = ChatCompletionsAdapter.decoder(qwen)
        empty.accept("message", chunk(reason = "stop")) {}
        assertFalse(empty.complete)
        assertThrows(ProviderException::class.java) { empty.finish {} }
    }

    @Test fun failedTerminalReasonsAndToolCallsNeverSucceed() {
        listOf("length", "content_filter", "tool_calls", "function_call", "unexpected").forEach { reason ->
            val decoder = ChatCompletionsAdapter.decoder(qwen)
            decoder.accept("message", chunk("partial")) {}
            assertThrows(reason, ProviderException::class.java) { decoder.accept("message", chunk(reason = reason)) {} }
            assertFalse(decoder.complete)
            assertThrows(ProviderException::class.java) { decoder.finish {} }
        }
        val tool = ChatCompletionsAdapter.decoder(custom)
        assertThrows(ProviderException::class.java) { tool.accept("message", chunk(delta = mapOf("tool_calls" to listOf(mapOf("id" to "call"))))) {} }
        assertFalse(tool.complete)
    }

    @Test fun malformedAndErrorChunksCannotProduceACompleteAnswer() {
        val malformed = listOf(emptyMap(), mapOf("choices" to "wrong"), mapOf("choices" to emptyList<Any>()),
            mapOf("choices" to listOf(mapOf("index" to 0, "message" to mapOf("content" to "not streaming")))),
            chunk(delta = mapOf("content" to listOf("wrong"))), chunk(delta = mapOf("refusal" to "declined")),
            mapOf("error" to mapOf("message" to "server failure")), mapOf("choices" to listOf(mapOf("index" to 1, "delta" to emptyMap<String, Any?>()))))
        malformed.forEach { data ->
            val decoder = ChatCompletionsAdapter.decoder(qwen)
            assertThrows(ProviderException::class.java) { decoder.accept("message", data) {} }
            assertFalse(decoder.complete)
        }
        val decoder = ChatCompletionsAdapter.decoder(qwen)
        decoder.accept("message", chunk("answer", "stop")) {}
        assertThrows(ProviderException::class.java) { decoder.accept("message", chunk("extra")) {} }
        assertFalse(decoder.complete)
    }

    @Test fun qwenChatDoesNotInventSourcesFromTheDashscopeProtocol() {
        val decoder = ChatCompletionsAdapter.decoder(qwen.copy(webSearch = true))
        val frame = chunk("search answer", "stop") + mapOf("search_info" to mapOf("search_results" to listOf(
            mapOf("title" to "DashScope only", "url" to "https://example.com"))))
        decoder.accept("message", frame) {}
        assertEquals("search answer", decoder.finish {})
    }

    @Test fun customChatRendersOnlyStructuredSafeUrlCitations() {
        val decoder = ChatCompletionsAdapter.decoder(custom.copy(webSearch = true))
        fun citation(url: String, title: String) = mapOf("type" to "url_citation", "url_citation" to mapOf("url" to url, "title" to title,
            "start_index" to 0, "end_index" to 6))
        decoder.accept("message", chunk(reason = "stop", delta = mapOf("content" to "answer", "annotations" to listOf(
            citation("https://example.com/source", "Source"), citation("javascript:alert(1)", "unsafe"),
            citation("https://user:secret@example.com", "credentials"), citation("https://example.com/source", "Source"))))) {}
        val deltas = mutableListOf<String>()
        assertEquals("answer\n\n来源：\n- [Source](https://example.com/source)", decoder.finish(deltas::add))
        assertEquals(1, deltas.size)
        assertFalse(decoder.finish {}.contains("unsafe"))
    }

    @Test fun oversizedAnswerFailsInsteadOfCompleting() {
        val decoder = ChatCompletionsAdapter.decoder(qwen)
        assertThrows(ProviderException::class.java) { decoder.accept("message", chunk("x".repeat(256 * 1024 + 1), "stop")) {} }
        assertFalse(decoder.complete)
    }

    private fun chunk(content: String? = null, reason: String? = null,
        delta: Map<String, Any?> = if (content == null) emptyMap() else mapOf("content" to content)): Map<String, Any?> =
        mapOf("choices" to listOf(mapOf("index" to 0, "delta" to delta, "finish_reason" to reason)))
}
