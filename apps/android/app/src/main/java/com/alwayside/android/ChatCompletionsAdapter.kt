package com.alwayside.android

/** OpenAI Chat wire format, with DashScope extensions isolated to the Qwen connection. */
internal object ChatCompletionsAdapter : ChatAdapter {
    override fun request(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?): WireRequest {
        ProviderProtocol.validateOptions(config)
        if (config.providerKind !in setOf(ProviderKind.QWEN, ProviderKind.OPENAI, ProviderKind.CUSTOM))
            throw ProviderException("该供应商不支持此 Chat Completions 适配器，请选择其原生协议")
        if (config.continuationMessages.isNotEmpty()) throw ProviderException("Chat Completions 不支持此继续生成状态，请重新提问")
        val messages = buildList<Map<String, Any?>> {
            if (config.instructions.isNotBlank()) add(mapOf("role" to "system", "content" to config.instructions))
            boundedHistory(history).forEach { add(mapOf("role" to it.role, "content" to it.text)) }
            val content: Any = if (imageDataUrl == null) question else listOf(
                mapOf("type" to "text", "text" to question),
                mapOf("type" to "image_url", "image_url" to mapOf("url" to imageDataUrl)))
            add(mapOf("role" to "user", "content" to content))
        }
        val body = linkedMapOf<String, Any?>("model" to config.model, "messages" to messages, "stream" to true,
            "stream_options" to mapOf("include_usage" to true))
        config.temperature?.let { body["temperature"] = it }
        config.topP?.let { body["top_p"] = it }
        if (config.providerKind == ProviderKind.QWEN) qwenOptions(config, body) else {
            if (config.maxOutputTokens != 0) body["max_completion_tokens"] = config.maxOutputTokens
            if (config.reasoning.isNotBlank()) body["reasoning_effort"] = config.reasoning
            if (config.verbosity.isNotBlank()) body["verbosity"] = config.verbosity
            if (config.webSearch) body["web_search_options"] = emptyMap<String, Any?>()
        }
        return WireRequest("chat/completions", body)
    }

    private fun qwenOptions(config: AppConfig, body: MutableMap<String, Any?>) {
        val model = config.model.lowercase()
        // Official Chat reference: max_completion_tokens is supported only by the named newer families.
        val modernLimit = Regex("qwen3\\.([5-8])-(max|plus|flash)(?:-|$)").find(model)?.let {
            it.groupValues[2] != "max" || it.groupValues[1].toInt() >= 7
        } == true
        if (config.maxOutputTokens != 0) body[if (modernLimit) "max_completion_tokens" else "max_tokens"] = config.maxOutputTokens
        if (config.verbosity.isNotBlank()) throw ProviderException("千问 Chat Completions 不支持 verbosity，请将回答详细程度设为不设置")
        if (config.temperature == 2.0) throw ProviderException("千问 temperature 必须小于 2")
        if (config.topP == 0.0) throw ProviderException("千问 top_p 必须大于 0")
        if (config.reasoning.isNotBlank()) {
            // Qwen3.8 documents effort levels and their server mappings. Older hybrid models expose only an on/off switch.
            val effortModel = Regex("qwen3\\.8-(max|flash|2\\.4t-a95b|27b|omni-flash)(?:-|$)").containsMatchIn(model)
            val hybrid = (model.startsWith("qwen3") || Regex("^qwen-(plus|flash|turbo)(?:-|$)").containsMatchIn(model)) &&
                !model.contains("thinking") && !model.contains("instruct") && !model.contains("coder") && !model.contains("character")
            when {
                effortModel -> body["reasoning_effort"] = config.reasoning
                config.reasoning == "none" && hybrid -> body["enable_thinking"] = false
                else -> throw ProviderException("该千问模型没有文档支持此推理程度，请设为不设置或选择支持 reasoning_effort 的 Qwen3.8 模型")
            }
        }
        if (config.webSearch) {
            // Unknown and older models can silently ignore enable_search; require a documented search family.
            val supportsSearch = Regex("^qwen-(max|plus|flash)(?:-|$)").containsMatchIn(model) ||
                Regex("^qwen3\\.8-(max|flash|2\\.4t-a95b|27b|omni-flash)(?:-|$)").containsMatchIn(model) ||
                Regex("^qwen3\\.7-(max|plus|flash)(?:-|$)").containsMatchIn(model) ||
                Regex("^qwen3\\.6-(plus|flash|27b|35b-a3b)(?:-|$)").containsMatchIn(model) ||
                Regex("^qwen3\\.5-(plus|flash|27b|35b-a3b|122b-a10b|397b-a17b|omni-plus|omni-flash)(?:-|$)").containsMatchIn(model)
            if (!supportsSearch) throw ProviderException("该千问模型没有文档支持 Chat Completions 联网搜索，请关闭搜索或选择支持搜索的模型")
            body["enable_search"] = true
            if (Regex("^qwen3\\.(8-omni-flash|5-omni-(plus|flash))(?:-|$)").containsMatchIn(model))
                body["search_options"] = mapOf("search_strategy" to "agent")
            // Qwen's OpenAI Chat protocol explicitly does not return search_info or structured source citations.
        }
    }

    override fun decoder(config: AppConfig): AnswerDecoder = ChatCompletionsDecoder(config.providerKind != ProviderKind.QWEN)
}

private class ChatCompletionsDecoder(private val readAnnotations: Boolean) : AnswerDecoder {
    private val answer = AnswerText()
    private var stopped = false
    private var failed = false
    private var done = false
    private var result: String? = null
    override val complete: Boolean get() = stopped && !failed && answer.value.isNotBlank()

    override fun accept(eventName: String, data: Map<String, Any?>, onDelta: (String) -> Unit) {
        if (failed) fail("回答流已经失败，请重新提问")
        if (eventName == "__DONE__") {
            if (!complete) fail("回答流提前结束，未收到正常完成状态")
            done = true
            return
        }
        if (done || result != null) fail("回答完成后仍收到额外内容")
        if (eventName == "error" || data["error"] != null || data["code"] != null) fail("Chat Completions 服务返回错误，请检查连接、模型和参数")
        val choices = data["choices"] as? List<*> ?: fail("Chat Completions 回答格式无效：缺少 choices")
        if (choices.isEmpty()) {
            if (!stopped || data["usage"] !is Map<*, *>) fail("回答流包含无效的空 choices 数据")
            return // The documented usage-only final chunk is not an answer and not a terminal signal.
        }
        if (choices.size != 1) fail("回答流返回了多个候选，无法确定完整回答")
        val choice = choices.single() as? Map<*, *> ?: fail("回答候选格式无效")
        val index = choice["index"] as? Number ?: fail("回答候选缺少 index")
        if (index.toDouble() != 0.0) fail("回答候选 index 无效")
        if (stopped) fail("正常完成状态后仍收到回答内容")
        val delta = choice["delta"] as? Map<*, *> ?: fail("回答流缺少 delta")
        if (delta["role"] != null && delta["role"] != "assistant") fail("回答流角色无效")
        if (delta["tool_calls"] != null && (delta["tool_calls"] !is List<*> || (delta["tool_calls"] as List<*>).isNotEmpty()) ||
            delta["function_call"] != null) fail("模型请求了未支持的工具调用，回答尚未完成")
        val refusal = delta["refusal"]
        if (refusal != null && refusal != "") fail("服务拒绝了此请求，请调整问题后重试")
        val reasoning = delta["reasoning_content"]
        if (reasoning != null && reasoning !is String) fail("回答流的推理内容格式无效")
        val content = delta["content"]
        if (content != null && content !is String) fail("回答流的文字内容格式无效")
        // Validate completion before emitting the final chunk, so length/filter/tool failures cannot be treated as success.
        val reason = choice["finish_reason"]
        if (reason != null && reason != "stop") fail(when (reason) {
            "length" -> "回答达到输出上限，内容尚未完成，请提高输出 tokens 或缩小问题范围"
            "content_filter" -> "回答被服务内容过滤，未能完整生成"
            "tool_calls", "function_call" -> "模型请求了未支持的工具调用，回答尚未完成"
            else -> "回答返回了未知的完成状态"
        })
        if (content is String) {
            try { answer.append(content, onDelta) } catch (error: ProviderException) { failed = true; throw error }
        }
        if (readAnnotations) readCitations(delta["annotations"])
        if (reason == "stop") stopped = true
    }

    private fun readCitations(value: Any?) {
        if (value == null) return
        val annotations = value as? List<*> ?: fail("回答来源格式无效")
        annotations.take(100).forEach { raw ->
            val annotation = raw.wireObject()
            if (annotation.text("type") == "url_citation") {
                val citation = annotation.obj("url_citation")
                answer.citation(citation.text("url"), citation.text("title"))
            }
        }
    }

    override fun finish(onDelta: (String) -> Unit): String {
        if (!complete) fail("回答流未正常完成，请重新提问")
        result?.let { return it }
        return try { answer.finish(onDelta).also { result = it } } catch (error: ProviderException) { failed = true; throw error }
    }

    private fun fail(message: String): Nothing {
        failed = true
        throw ProviderException(message)
    }
}
