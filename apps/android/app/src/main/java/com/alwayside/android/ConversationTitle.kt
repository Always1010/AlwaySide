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
                .let(::stripLinks)
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

    /** Preserve the former flat link/image syntax without rescanning unmatched suffixes. */
    private fun stripLinks(source: String): String {
        val size = source.length
        if ('[' !in source) return source
        val bracket = IntArray(size + 1) { size }
        val parenthesis = IntArray(size + 1) { size }
        for (index in size - 1 downTo 0) {
            bracket[index] = if (source[index] == ']') index else bracket[index + 1]
            parenthesis[index] = if (source[index] == ')') index else parenthesis[index + 1]
        }
        val result = StringBuilder(size)
        var plain = 0
        var index = 0
        while (index < size) {
            val open = if (source[index] == '!' && index + 1 < size && source[index + 1] == '[') index + 1 else index
            if (source[open] == '[') {
                val close = bracket[open + 1]
                if (close + 1 < size && source[close + 1] == '(') {
                    val end = parenthesis[close + 2]
                    if (end < size) {
                        result.append(source, plain, index)
                        result.append(source, open + 1, close)
                        index = end + 1
                        plain = index
                        continue
                    }
                }
            }
            index++
        }
        return result.append(source, plain, size).toString()
    }
}
