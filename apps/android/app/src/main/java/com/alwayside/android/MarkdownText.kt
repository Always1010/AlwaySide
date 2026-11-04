package com.alwayside.android

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Small native renderer: headings, bold, inline/fenced code, lists and HTTPS links. No HTML. */
internal object MarkdownText {
    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        var code = false
        source.lines().forEach { line ->
            if (line.startsWith("```")) { code = !code; return@forEach }
            val heading = if (!code) Regex("^(#{1,4})\\s+").find(line) else null
            val clean = if (heading != null) line.substring(heading.value.length) else line
            val start = out.length
            if (code) {
                out.append(clean)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https://[^\\s)]+)\\)")
                var cursor = 0
                pattern.findAll(clean).forEach { match ->
                    out.append(clean.substring(cursor, match.range.first))
                    val begin = out.length
                    val group = when { match.groups[1] != null -> 1; match.groups[2] != null -> 2; else -> 3 }
                    out.append(match.groupValues[group])
                    val span = when (group) { 1 -> StyleSpan(Typeface.BOLD); 2 -> TypefaceSpan("monospace"); else -> URLSpan(match.groupValues[4]) }
                    out.setSpan(span, begin, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cursor = match.range.last + 1
                }
                out.append(clean.substring(cursor))
            }
            if (heading != null) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.2f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            out.append('\n')
        }
        return out
    }
}
