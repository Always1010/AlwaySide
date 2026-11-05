package com.alwayside.android

internal object ResponsesAdapter : ChatAdapter {
    override fun request(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?): WireRequest {
        val input = boundedHistory(history).map { mapOf<String, Any?>("role" to it.role, "content" to it.text) }.toMutableList()
        val content = mutableListOf<Map<String, Any?>>(mapOf("type" to "input_text", "text" to question))
        imageDataUrl?.let { content.add(mapOf("type" to "input_image", "image_url" to it)) }
        input.add(mapOf("role" to "user", "content" to content))
        return WireRequest("responses", linkedMapOf<String, Any?>("model" to config.model,
            "instructions" to config.instructions, "input" to input, "store" to false, "stream" to true)
            .apply { putAll(ProviderProtocol.responseOptions(config)) })
    }

    override fun decoder(config: AppConfig): AnswerDecoder = object : AnswerDecoder {
        private val answer = AnswerText()
        override var complete = false; private set
        override var searchUsed = false; private set
        override fun accept(eventName: String, data: Map<String, Any?>, onDelta: (String) -> Unit) {
            when (data.text("type").ifBlank { eventName }) {
                "response.output_text.delta", "response.refusal.delta" -> answer.append(data.text("delta"), onDelta)
                "response.output_text.annotation.added" -> citation(data.obj("annotation"))
                "response.web_search_call.completed" -> searchUsed = true
                "response.failed", "error" -> throw ProviderException("服务生成回答失败，请检查模型配置或稍后重试")
                "response.incomplete" -> throw ProviderException("回答未完成，可能达到输出限制，请调整后重试")
                "__DONE__" -> throw ProviderException("服务未确认回答完成，请手动重试")
                "response.completed" -> {
                    val response = data.obj("response")
                    if (response.text("status") != "completed" || response["error"] != null || response["incomplete_details"] != null)
                        throw ProviderException("服务未成功完成回答，请手动重试")
                    val finalText = buildString {
                        response.items("output").forEach { raw ->
                            val item = raw.wireObject()
                            if (item.text("type") == "web_search_call" && item.text("status") == "completed") searchUsed = true
                            if (item.text("type") == "message") item.items("content").forEach { value ->
                                val part = value.wireObject()
                                when (part.text("type")) {
                                    "output_text" -> { append(part.text("text")); part.items("annotations").forEach { citation(it.wireObject()) } }
                                    "refusal" -> append(part.text("refusal"))
                                }
                                if (length > 256 * 1024) throw ProviderException("回答过长，请缩小问题范围")
                            }
                        }
                    }
                    if (answer.value.isEmpty()) answer.append(finalText, onDelta)
                    else if (finalText != answer.value) throw ProviderException("回答片段与最终结果不一致，请重试")
                    complete = true
                }
            }
        }
        private fun citation(item: Map<String, Any?>) {
            if (item.text("type") == "url_citation") answer.citation(item.text("url"), item.text("title"))
        }
        override fun finish(onDelta: (String) -> Unit): String {
            if (!complete) throw ProviderException("回答连接提前中断，结果不完整，请手动重试")
            return answer.finish(onDelta)
        }
    }
}
