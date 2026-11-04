package com.alwayside.android

/** Derive a useful local title from existing answer text, with no additional model request. */
internal object ConversationTitle {
    fun resolve(stored: String, messages: List<ChatMessage>): String {
        val answer = messages.firstOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return stored
        val question = messages.firstOrNull { it.role == "user" }?.text?.take(60)
        return if (stored.isBlank() || stored == question) fromAnswer(answer.text).ifBlank { stored } else stored
    }

    fun fromAnswer(answer: String): String {
        val text = answer.lineSequence().map { line ->
            line.trim()
                .replace(Regex("^\\s*(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)、]\\s*)"), "")
                .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_`]+"), "")
                .replace(Regex("^(?:翻译如下|以下是(?:中文)?翻译|以下是解释|解释如下|中文翻译|译文)\\s*[:：]\\s*"), "")
                .replace(Regex("\\s+"), " ").trim()
        }.firstOrNull { line ->
            line.isNotBlank() && !Regex("^(?:翻译如下|以下是(?:中文)?翻译|以下是解释|解释如下|中文翻译|译文|[-—=]+)[:：]?$"
            ).matches(line)
        }.orEmpty()
        val count = text.codePointCount(0, text.length)
        return text.substring(0, text.offsetByCodePoints(0, minOf(32, count)))
    }
}
