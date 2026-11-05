package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class MarkdownInlineTest {
    @Test fun preservesFlatFormattingAndLeavesUnmatchedMarkersVisible() {
        assertEquals(listOf(
            MarkdownInline.Part("before "),
            MarkdownInline.Part("bold", MarkdownInline.Style.BOLD),
            MarkdownInline.Part(" "),
            MarkdownInline.Part("code", MarkdownInline.Style.CODE),
            MarkdownInline.Part(" "),
            MarkdownInline.Part("site", MarkdownInline.Style.LINK, "https://example.com/path"),
            MarkdownInline.Part(" after **open `open [open"),
        ), MarkdownInline.parse("before **bold** `code` [site](https://example.com/path) after **open `open [open"))
    }

    @Test fun matchesPreviousSyntaxForOverlappingAndMalformedMarkers() {
        val cases = listOf("", "****", "*****", "``", "```x`", "[[label](https://x)",
            "[](https://x)", "[x](https://)", "[x](http://x)", "[x](https://a b)",
            "[x](https://a(b))", "**a `b` [c](https://d)**", "**a\u2028b**", "**a\u0085b**")
        cases.forEach { assertEquals(it, legacy(it), MarkdownInline.parse(it)) }
        val random = Random(861)
        val alphabet = "*`[]()https:/ x\t\u2028"
        repeat(2000) {
            val value = buildString { repeat(random.nextInt(120)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            assertEquals(value, legacy(value), MarkdownInline.parse(value))
        }
    }

    @Test(timeout = 5000) fun handlesLongUnterminatedLabelsAndUrlsWithoutSuffixRescans() {
        val values = listOf("[".repeat(262144), "[x](https://".repeat(20000),
            "[".repeat(131072) + "](https://" + "x".repeat(131072),
            "**" + "x".repeat(262144), "`" + "x".repeat(262144))
        values.forEach { assertEquals(listOf(MarkdownInline.Part(it)), MarkdownInline.parse(it)) }
    }

    private fun legacy(value: String): List<MarkdownInline.Part> {
        val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https://[^\\s)]+)\\)")
        val parts = mutableListOf<MarkdownInline.Part>()
        var cursor = 0
        pattern.findAll(value).forEach { match ->
            if (cursor < match.range.first) parts += MarkdownInline.Part(value.substring(cursor, match.range.first))
            val group = when { match.groups[1] != null -> 1; match.groups[2] != null -> 2; else -> 3 }
            parts += MarkdownInline.Part(match.groupValues[group], when (group) {
                1 -> MarkdownInline.Style.BOLD
                2 -> MarkdownInline.Style.CODE
                else -> MarkdownInline.Style.LINK
            }, if (group == 3) match.groupValues[4] else null)
            cursor = match.range.last + 1
        }
        if (cursor < value.length) parts += MarkdownInline.Part(value.substring(cursor))
        return parts
    }
}
