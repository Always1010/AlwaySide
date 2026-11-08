package com.alwayside.android

/** Display-only excerpt; never rewrites the stored answer or its searchable body. */
internal object ConversationPreview {
    fun text(source: String, title: String): String {
        val lines = source.take(4096).lineSequence()
            .filterNot { it.trimStart().let { line -> line.startsWith("```") || line.startsWith("~~~") } }
            .map { ConversationTitle.plainLine(it).replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && !Regex("^[-—=]{3,}$").matches(it) }.toList()
        val heading = normalize(ConversationTitle.plainLine(title))
        val body = lines.dropWhile { normalize(it).equals(heading, ignoreCase = true) }
        val excerpt = (body.ifEmpty { lines.take(1) }).joinToString(" ").take(180)
        return if (excerpt.lastOrNull()?.isHighSurrogate() == true) excerpt.dropLast(1) else excerpt
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim().trimEnd(':', '：', '。')
}
