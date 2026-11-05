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
        var firstLine = true
        source.lines().forEach { line ->
            if (line.startsWith("```")) { code = !code; return@forEach }
            if (!firstLine) out.append('\n')
            firstLine = false
            val heading = if (!code) Regex("^(#{1,4})\\s+").find(line) else null
            val clean = if (heading != null) line.substring(heading.value.length) else line
            val start = out.length
            if (code) {
                out.append(clean)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                MarkdownInline.parse(clean).forEach { part ->
                    val begin = out.length
                    out.append(part.text)
                    val span = when (part.style) {
                        MarkdownInline.Style.PLAIN -> null
                        MarkdownInline.Style.BOLD -> StyleSpan(Typeface.BOLD)
                        MarkdownInline.Style.CODE -> TypefaceSpan("monospace")
                        MarkdownInline.Style.LINK -> URLSpan(part.url)
                    }
                    if (span != null) out.setSpan(span, begin, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (heading != null) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.2f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
