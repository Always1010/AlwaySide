package com.alwayside.android

/** One adapter is created per HTTP request. Its snapshot also anchors explicit pause_turn retries. */
internal class ClaudeMessagesAdapter(
    private val parseObject: (String) -> Map<String, Any?> = WireJson::decode,
) : ChatAdapter {
    private var requestMessages: List<Map<String, Any?>> = emptyList()

    override fun request(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?): WireRequest {
        ProviderProtocol.validateOptions(config)
        require(config.verbosity.isBlank()) { "Claude 不支持 verbosity，请清空回答详细程度" }
        val model = config.model.lowercase()
        val adaptive = matches(model, "opus-4-6", "opus-4-7", "opus-4-8", "sonnet-4-6", "opus-5", "sonnet-5", "haiku-5-5", "fable-5", "mythos-5", "mythos-preview")
        val opus45 = matches(model, "opus-4-5")
        val effort = config.reasoning.takeUnless { it.isBlank() || it == "none" }
        if (effort != null) {
            require(adaptive || opus45) { "此 Claude 模型不支持 effort，请清空推理程度" }
            require(effort in setOf("low", "medium", "high", "xhigh", "max")) { "Claude effort 仅支持 low、medium、high、xhigh、max" }
            if (effort == "xhigh") require(matches(model, "opus-4-7", "opus-4-8", "opus-5", "sonnet-5", "haiku-5-5", "fable-5", "mythos-5")) { "此 Claude 模型不支持 xhigh effort" }
            if (effort == "max") require(!opus45) { "Claude Opus 4.5 不支持 max effort" }
        }
        val thinking = when {
            config.reasoning == "none" -> {
                require(!matches(model, "opus-5-5", "fable-5", "mythos-5", "mythos-preview")) { "此 Claude 模型无法关闭思考，请清空推理程度或选择 effort" }
                mapOf("type" to if (matches(model, "sonnet-5-5")) "between_tools" else "disabled")
            }
            effort != null && adaptive -> mapOf("type" to "adaptive")
            else -> null
        }
        val sampling = config.temperature != null || config.topP != null
        if (sampling) {
            require(config.apiVersion.ifBlank { "2026-10-21" } == "2023-06-01") { "Claude API 2026-10-21 已移除采样参数，请清空 temperature 和 top_p" }
            require(config.temperature == null || config.temperature in 0.0..1.0) { "Claude temperature 请填写 0 至 1" }
            val newer = matches(model, "opus-4-7", "opus-4-8", "opus-5", "sonnet-5", "haiku-5-5", "fable-5", "mythos-5", "mythos-preview")
            require(!newer || (config.temperature == null || config.temperature == 1.0) && (config.topP == null || config.topP == 0.99)) { "此 Claude 模型仅接受默认采样值" }
            if (thinking?.get("type") == "adaptive" && !newer) {
                require(config.temperature == null && (config.topP == null || config.topP >= 0.95)) { "Claude 思考模式不支持 temperature，top_p 需至少 0.95" }
            }
        }
        val maxTokens = config.maxOutputTokens.takeIf { it > 0 } ?: 8192
        if (matches(model, "opus-4-5", "sonnet-4-5", "haiku-4-5")) require(maxTokens <= 64000) { "此 Claude 模型最大输出为 64000 tokens" }
        val messages = if (config.continuationMessages.isNotEmpty()) {
            config.continuationMessages.map { nativeCopy(it).wireObject() }
        } else {
            boundedHistory(history).map { mapOf<String, Any?>("role" to it.role, "content" to it.text) } +
                mapOf("role" to "user", "content" to buildList<Map<String, Any?>> {
                    if (!imageDataUrl.isNullOrBlank()) add(imageBlock(imageDataUrl))
                    add(mapOf("type" to "text", "text" to question))
                })
        }
        requestMessages = messages
        return WireRequest("messages", linkedMapOf<String, Any?>(
            "model" to config.model, "max_tokens" to maxTokens, "stream" to true, "messages" to messages,
        ).apply {
            if (config.instructions.isNotBlank()) put("system", config.instructions)
            thinking?.let { put("thinking", it) }
            effort?.let { put("output_config", mapOf("effort" to it)) }
            config.temperature?.let { put("temperature", it) }
            config.topP?.let { put("top_p", it) }
            // The basic version avoids implicitly provisioning code execution for dynamic filtering.
            if (config.webSearch) put("tools", listOf(mapOf("type" to "web_search_20250305", "name" to "web_search")))
        })
    }

    override fun decoder(config: AppConfig): AnswerDecoder = ClaudeDecoder(
        requestMessages.ifEmpty { config.continuationMessages }, parseObject,
    )

    private fun imageBlock(dataUrl: String): Map<String, Any?> {
        val match = Regex("^data:(image/(?:jpeg|png|gif|webp));base64,([A-Za-z0-9+/]+={0,2})$").matchEntire(dataUrl)
        require(match != null) { "Claude 图片必须为 JPEG、PNG、GIF 或 WebP 的 base64 data URL" }
        return mapOf("type" to "image", "source" to mapOf("type" to "base64", "media_type" to match.groupValues[1], "data" to match.groupValues[2]))
    }

    private fun matches(model: String, vararg names: String) = names.any { model == "claude-$it" || model.startsWith("claude-$it-") }
}

private fun nativeCopy(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to nativeCopy(it.value) }
    is List<*> -> value.map(::nativeCopy)
    else -> value
}

private class ClaudeDecoder(
    private val messages: List<Map<String, Any?>>,
    private val parseObject: (String) -> Map<String, Any?>,
) : AnswerDecoder {
    private val answer = AnswerText()
    private val blocks = sortedMapOf<Int, MutableMap<String, Any?>>()
    private val openBlocks = mutableSetOf<Int>()
    private val inputJson = mutableMapOf<Int, StringBuilder>()
    private var started = false
    private var stopReason = ""
    private var failure: String? = null
    private var finished: String? = null
    override var complete = false
        private set

    override fun accept(eventName: String, data: Map<String, Any?>, onDelta: (String) -> Unit) {
        val type = data.text("type").ifBlank { eventName }
        if (type == "error" || eventName == "error" || data.obj("error").isNotEmpty()) fail("Claude 服务返回错误")
        if (complete) fail("Claude 完成事件之后仍返回内容")
        when (type) {
            "message" -> {
                if (started) fail("Claude 重复返回消息")
                started = true
                data.items("content").forEachIndexed { index, raw ->
                    val block = nativeCopy(raw).wireObject().toMutableMap()
                    blocks[index] = block
                    inspectBlock(block, onDelta)
                }
                stopReason = data.text("stop_reason")
                terminal()
            }
            "message_start" -> {
                if (started) fail("Claude 重复开始消息")
                started = true
                if (data.obj("message").items("content").isNotEmpty()) fail("Claude 开始事件内容无效")
            }
            "content_block_start" -> {
                requireStarted()
                val index = index(data)
                if (blocks.containsKey(index) || stopReason.isNotBlank()) fail("Claude 内容块顺序无效")
                val block = nativeCopy(data.obj("content_block")).wireObject().toMutableMap()
                if (block.text("type").isBlank()) fail("Claude 内容块无效")
                blocks[index] = block
                openBlocks.add(index)
                inspectBlock(block, onDelta)
            }
            "content_block_delta" -> {
                requireStarted()
                val index = index(data)
                if (index !in openBlocks) fail("Claude 内容增量缺少开始事件")
                val block = blocks.getValue(index)
                val delta = data.obj("delta")
                when (delta.text("type")) {
                    "text_delta" -> {
                        if (block.text("type") != "text") fail("Claude 文字块类型不匹配")
                        val text = delta.text("text")
                        block["text"] = block.text("text") + text
                        answer.append(text, onDelta)
                    }
                    "thinking_delta" -> block["thinking"] = block.text("thinking") + delta.text("thinking")
                    "signature_delta" -> block["signature"] = block.text("signature") + delta.text("signature")
                    "input_json_delta" -> {
                        val json = inputJson.getOrPut(index) { StringBuilder() }
                        if (json.length + delta.text("partial_json").length > 2 * 1024 * 1024) fail("Claude 工具输入过大")
                        json.append(delta.text("partial_json"))
                    }
                    "citations_delta" -> {
                        val citation = nativeCopy(delta.obj("citation")).wireObject()
                        block["citations"] = block.items("citations") + listOf(citation)
                        citation(citation)
                    }
                    else -> fail("Claude 返回无法保留的内容增量类型")
                }
            }
            "content_block_stop" -> {
                val index = index(data)
                if (!openBlocks.remove(index)) fail("Claude 内容结束事件缺少开始事件")
                inputJson.remove(index)?.let { json ->
                    blocks.getValue(index)["input"] = try { parseObject(json.toString()) } catch (_: Exception) { fail("Claude 工具输入 JSON 无效") }
                }
            }
            "message_delta" -> {
                requireStarted()
                if (openBlocks.isNotEmpty()) fail("Claude 消息在内容块结束前终止")
                val reason = data.obj("delta").text("stop_reason")
                if (reason.isNotBlank()) {
                    if (stopReason.isNotBlank()) fail("Claude 重复返回终止原因")
                    stopReason = reason
                }
            }
            "message_stop" -> { requireStarted(); terminal() }
            "ping" -> Unit
            // The protocol allows new event types; they do not terminate the stream.
            else -> Unit
        }
    }

    private fun terminal() {
        if (openBlocks.isNotEmpty() || stopReason.isBlank()) fail("Claude 回答未完整结束，请重试")
        when (stopReason) {
            "end_turn", "stop_sequence" -> complete = true
            "pause_turn" -> complete = true
            "max_tokens", "model_context_window_exceeded" -> fail("Claude 回答达到长度限制，未完整结束")
            "tool_use" -> fail("Claude 请求了尚未支持的客户端工具")
            "refusal" -> fail("Claude 拒绝了本次请求")
            else -> fail("Claude 回答异常终止（$stopReason）")
        }
    }

    private fun inspectBlock(block: Map<String, Any?>, emit: (String) -> Unit) {
        if (block.text("type").endsWith("_tool_result")) {
            val result = block.obj("content")
            if (result.text("type").endsWith("_error") || result.text("error_code").isNotBlank()) fail("Claude 搜索工具执行失败")
            if (block.items("content").any { it.wireObject().text("type").endsWith("_error") }) fail("Claude 搜索工具执行失败")
        }
        if (block.text("type") == "text") {
            answer.append(block.text("text"), emit)
            block.items("citations").forEach { citation(it.wireObject()) }
        }
    }

    private fun citation(value: Map<String, Any?>) {
        if (value.text("type") == "web_search_result_location") answer.citation(value.text("url"), value.text("title"))
    }

    override fun finish(onDelta: (String) -> Unit): String {
        failure?.let { throw ProviderException(it) }
        if (!complete) fail("Claude 回答连接提前结束，请重试")
        if (stopReason == "pause_turn") {
            if (messages.isEmpty()) fail("Claude 继续生成缺少原始请求")
            throw ProviderPausedException(messages + mapOf("role" to "assistant", "content" to blocks.values.map { nativeCopy(it) }))
        }
        return finished ?: answer.finish(onDelta).also { finished = it }
    }

    private fun requireStarted() { if (!started) fail("Claude 缺少消息开始事件") }
    private fun index(data: Map<String, Any?>): Int = (data["index"] as? Number)?.toInt()?.takeIf { it in 0..4095 }
        ?: fail("Claude 内容块索引无效")
    private fun fail(message: String): Nothing { failure = message; complete = false; throw ProviderException(message) }
}
