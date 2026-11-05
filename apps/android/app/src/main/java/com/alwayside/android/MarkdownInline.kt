package com.alwayside.android

/** Flat inline syntax, parsed in O(n) time even when every opening marker is unmatched. */
internal object MarkdownInline {
    enum class Style { PLAIN, BOLD, CODE, LINK }
    data class Part(val text: String, val style: Style = Style.PLAIN, val url: String? = null)

    fun parse(source: String): List<Part> {
        val size = source.length
        if (size == 0) return emptyList()
        // Each lookup returns the nearest delimiter; failed openings never rescan a suffix.
        val bold = IntArray(size + 1) { size }
        val tick = IntArray(size + 1) { size }
        val bracket = IntArray(size + 1) { size }
        val urlStop = IntArray(size + 1) { size }
        for (index in size - 1 downTo 0) {
            val char = source[index]
            bold[index] = when {
                dotStop(char) -> size
                char == '*' && index + 1 < size && source[index + 1] == '*' -> index
                else -> bold[index + 1]
            }
            tick[index] = if (char == '`') index else tick[index + 1]
            bracket[index] = if (char == ']') index else bracket[index + 1]
            urlStop[index] = if (char == ')' || asciiWhitespace(char)) index else urlStop[index + 1]
        }
        val result = mutableListOf<Part>()
        var plain = 0
        var index = 0
        while (index < size) {
            var end = size
            var contentStart = index
            var contentEnd = index
            var style = Style.PLAIN
            var url: String? = null
            when (source[index]) {
                '*' -> if (index + 3 < size && source[index + 1] == '*' && !dotStop(source[index + 2])) {
                    val close = bold[index + 3]
                    if (close < size) {
                        contentStart = index + 2; contentEnd = close; end = close + 2
                        style = Style.BOLD
                    }
                }
                '`' -> if (index + 1 < size) {
                    val close = tick[index + 1]
                    if (close < size && close > index + 1) {
                        contentStart = index + 1; contentEnd = close; end = close + 1
                        style = Style.CODE
                    }
                }
                '[' -> if (index + 1 < size) {
                    val close = bracket[index + 1]
                    // The label cannot contain ']', so only its nearest closing bracket can match.
                    if (close > index + 1 && close + 10 < size && source.startsWith("](https://", close)) {
                        val stop = urlStop[close + 10]
                        if (stop > close + 10 && stop < size && source[stop] == ')') {
                            contentStart = index + 1; contentEnd = close; end = stop + 1
                            style = Style.LINK; url = source.substring(close + 2, stop)
                        }
                    }
                }
            }
            if (style == Style.PLAIN) { index++; continue }
            if (plain < index) result += Part(source.substring(plain, index))
            result += Part(source.substring(contentStart, contentEnd), style, url)
            index = end
            plain = end
        }
        if (plain < size) result += Part(source.substring(plain))
        return result
    }

    private fun asciiWhitespace(char: Char) = char == ' ' || char in '\t'..'\r'
    private fun dotStop(char: Char) = char == '\n' || char == '\r' || char == '\u0085' || char == '\u2028' || char == '\u2029'
}
