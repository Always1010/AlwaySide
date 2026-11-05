package com.alwayside.android

import java.util.Base64

/** Native GenerateContent only. Gemini's OpenAI-compatible endpoint uses ChatCompletionsAdapter. */
internal object GeminiAdapter : ChatAdapter {
    override fun request(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?): WireRequest {
        ProviderProtocol.validateOptions(config)
        require(config.verbosity.isBlank()) { "Gemini GenerateContent 不支持回答详细程度参数，请选择不设置" }
        val model = config.model.trim().removePrefix("models/")
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}").matches(model)) { "Gemini 模型名称无效，只能填写模型 ID 或 models/模型 ID" }
        val generation = linkedMapOf<String, Any?>()
        if (config.maxOutputTokens != 0) generation["maxOutputTokens"] = config.maxOutputTokens
        config.temperature?.let { generation["temperature"] = it }
        config.topP?.let { generation["topP"] = it }
        thinkingConfig(model, config.reasoning)?.let { generation["thinkingConfig"] = it }

        val contents = boundedHistory(history).map { message ->
            mapOf("role" to if (message.role == "assistant") "model" else "user", "parts" to listOf(mapOf("text" to message.text)))
        }.toMutableList()
        val parts = mutableListOf<Map<String, Any?>>(mapOf("text" to question))
        imageDataUrl?.let { parts.add(imagePart(it)) }
        contents.add(mapOf("role" to "user", "parts" to parts))
        val body = linkedMapOf<String, Any?>("contents" to contents)
        if (config.instructions.isNotBlank()) body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to config.instructions)))
        if (generation.isNotEmpty()) body["generationConfig"] = generation
        if (config.webSearch) {
            val unsupportedVariant = model.contains("audio") ||
                (model.contains("image") && !family(model, "gemini-3-pro-image") && !family(model, "gemini-3.1-flash-image")) ||
                family(model, "gemini-2.0-flash-lite")
            require(searchModels.any { family(model, it) } && !unsupportedVariant) {
                "此 Gemini 模型尚未确认支持 Google Search，请关闭联网搜索或选择支持的模型"
            }
            body["tools"] = listOf(mapOf("google_search" to emptyMap<String, Any?>()))
        }
        return WireRequest("models/$model:streamGenerateContent", body, mapOf("alt" to "sse"))
    }

    override fun decoder(config: AppConfig): AnswerDecoder = GeminiDecoder()

    private fun imagePart(dataUrl: String): Map<String, Any?> {
        require(dataUrl.length <= 12 * 1024 * 1024) { "Gemini 图片过大，请缩小截图范围" }
        val match = Regex("data:(image/(?:png|jpeg|webp|heic|heif));base64,([A-Za-z0-9+/]+={0,2})").matchEntire(dataUrl)
            ?: throw IllegalArgumentException("Gemini 图片必须为支持格式的 Base64 data URL")
        val data = match.groupValues[2]
        require(runCatching { Base64.getDecoder().decode(data).isNotEmpty() }.getOrDefault(false)) { "Gemini 图片 Base64 数据无效" }
        return mapOf("inlineData" to mapOf("mimeType" to match.groupValues[1], "data" to data))
    }

    private fun family(model: String, name: String) = model == name || model.startsWith("$name-")

    private fun thinkingConfig(model: String, effort: String): Map<String, Any?>? {
        if (effort.isBlank()) return null
        // These budgets are application mappings of effort labels, within Google's per-model ranges.
        // Explicit unsupported values must fail rather than silently reducing reasoning.
        val pro25 = family(model, "gemini-2.5-pro")
        val flash25 = family(model, "gemini-2.5-flash") && !model.contains("image") && !model.contains("audio")
        if (pro25 || flash25) {
            val budget = when (effort) {
                "none" -> { require(!pro25) { "Gemini 2.5 Pro 不能关闭思考，请选择不设置或其他推理程度" }; 0 }
                "minimal" -> 512
                "low" -> 1024
                "medium" -> 8192
                "high" -> if (pro25) 32768 else 24576
                else -> throw IllegalArgumentException("Gemini 2.5 不支持推理程度 $effort，请选择 minimal、low、medium、high 或不设置")
            }
            return mapOf("thinkingBudget" to budget, "includeThoughts" to false)
        }
        val levels = when {
            (model.contains("image") && !family(model, "gemini-3.1-flash-lite-image")) || model.contains("audio") -> emptySet()
            family(model, "gemini-3-pro") -> setOf("low", "high")
            family(model, "gemini-3.1-flash-lite-image") -> setOf("minimal", "high")
            family(model, "gemini-3.1-pro") || family(model, "gemini-3.7-flash") ||
                family(model, "gemini-3.8-flash") || family(model, "gemini-4-argon") -> setOf("low", "medium", "high")
            family(model, "gemini-3-flash") || family(model, "gemini-3.1-flash-lite") ||
                family(model, "gemini-3.5-flash") || family(model, "gemini-3.6-flash") -> setOf("minimal", "low", "medium", "high")
            else -> emptySet()
        }
        require(effort in levels) { "此 Gemini 模型不支持推理程度 $effort，请选择不设置或模型支持的思考级别（none 不等于 minimal）" }
        return mapOf("thinkingLevel" to effort.uppercase(java.util.Locale.ROOT), "includeThoughts" to false)
    }

    private val searchModels = listOf("gemini-2.0-flash", "gemini-2.5-pro", "gemini-2.5-flash", "gemini-3-pro",
        "gemini-3-flash", "gemini-3.1-pro", "gemini-3.1-flash-image", "gemini-3.5-flash-lite",
        "gemini-3.6-flash", "gemini-3.8-flash", "gemini-4-argon")
}

private class GeminiDecoder : AnswerDecoder {
    private val answer = AnswerText()
    private var sawCandidate = false
    private var stopped = false
    private var result: String? = null
    override var searchUsed = false
        private set
    override var searchSuggestions = ""
        private set
    // Google can deliver grounding and usage metadata after the candidate's STOP chunk.
    override val complete: Boolean get() = result != null

    override fun accept(eventName: String, data: Map<String, Any?>, onDelta: (String) -> Unit) {
        if (complete) throw ProviderException("Gemini 流已结束后仍收到数据")
        if (eventName == "error" || data.containsKey("error")) {
            throw ProviderException("Gemini 返回错误：" + data.obj("error").text("message").ifBlank { "生成失败" }.take(500))
        }
        val blocked = data.obj("promptFeedback").text("blockReason")
        if (blocked.isNotBlank() && blocked != "BLOCK_REASON_UNSPECIFIED") throw ProviderException("Gemini 拒绝处理此问题：$blocked")
        val candidates = data.items("candidates")
        if (candidates.size > 1) throw ProviderException("Gemini 返回了意外的多个候选回答")
        candidates.forEach { value ->
            val candidate = value.wireObject()
            if (candidate.isEmpty() || (candidate["index"] as? Number)?.toInt()?.let { it != 0 } == true) {
                throw ProviderException("Gemini 候选回答格式无效")
            }
            sawCandidate = true
            val reason = candidate.text("finishReason")
            when (reason) {
                "", "FINISH_REASON_UNSPECIFIED", "STOP" -> Unit
                "MAX_TOKENS" -> throw ProviderException("Gemini 回答因达到最大输出 tokens 而截断，请增加上限后重试")
                else -> throw ProviderException("Gemini 回答未正常完成：$reason")
            }
            candidate.obj("content").items("parts").forEach { partValue ->
                val part = partValue.wireObject()
                if (part["thought"] != true) {
                    val text = part.text("text")
                    if (stopped && text.isNotEmpty()) throw ProviderException("Gemini 在结束标记后继续返回回答文字")
                    answer.append(text, onDelta)
                }
            }
            val grounding = candidate.obj("groundingMetadata")
            val suggestions = grounding.obj("searchEntryPoint").text("renderedContent")
            if (suggestions.length > 32768) throw ProviderException("Gemini 搜索建议内容过大")
            if (suggestions.isNotBlank()) searchSuggestions = suggestions
            if (grounding.items("webSearchQueries").any { it is String && it.isNotBlank() }) searchUsed = true
            grounding.items("groundingChunks").forEach { chunk ->
                val web = chunk.wireObject().obj("web")
                val uri = runCatching { java.net.URI(web.text("uri")) }.getOrNull()
                if (uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) searchUsed = true
                answer.citation(web.text("uri"), web.text("title"))
            }
            candidate.obj("citationMetadata").items("citationSources").forEach { source ->
                val citation = source.wireObject()
                answer.citation(citation.text("uri"), citation.text("title"))
            }
            if (reason == "STOP") stopped = true
        }
    }

    override fun finish(onDelta: (String) -> Unit): String {
        result?.let { return it }
        if (!sawCandidate) throw ProviderException("Gemini 没有返回候选回答，请检查问题或安全限制")
        if (!stopped) throw ProviderException("Gemini 响应流提前结束，未收到正常结束原因")
        return answer.finish(onDelta).also { result = it }
    }
}
