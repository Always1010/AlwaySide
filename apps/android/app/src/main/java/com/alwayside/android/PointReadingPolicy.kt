package com.alwayside.android

import java.security.MessageDigest
import java.text.Normalizer

internal enum class ReadingOrigin { CHAT, IMAGE }

internal fun InteractionConfig.readingEnabled(origin: ReadingOrigin): Boolean = when (origin) {
    ReadingOrigin.CHAT -> chatPointReading
    ReadingOrigin.IMAGE -> imagePointReading
}

internal data class ReadingVoice(val connection: String = "", val model: String = "", val voice: String = "alloy") {
    fun key(text: String): String = readingDigest(listOf("speech-v1", connection, model, voice, "mp3",
        normalizeReadingText(text)).joinToString("\u0000"))
}

internal fun normalizeReadingText(text: String): String =
    Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n').trim(), Normalizer.Form.NFC)

internal fun readingDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Conservative line grouping of already-rendered text. Ambiguous blocks keep native selection. */
internal object ReadingParagraphs {
    data class Block(val text: String, val english: Boolean, val speech: String = text)
    private val label = Regex("^(?:英文原文|英语原文|英文|英语|原文|English|Original)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    fun split(text: String): List<Block> {
        val result = mutableListOf<Block>()
        text.split('\n').forEach { line ->
            val speech = line.trimStart().replaceFirst(label, "")
            val letters = speech.count(Char::isLetter)
            val latin = speech.count { it in 'A'..'Z' || it in 'a'..'z' }
            val english = latin > 0 && latin == letters &&
                !line.trimStart().startsWith("http") && !line.contains("://") &&
                !line.contains('|') && !line.contains('{') && !line.contains('}')
            val previous = result.lastOrNull()
            if (previous != null && previous.english == english && line.isNotBlank() && previous.text.isNotBlank())
                result[result.lastIndex] = previous.copy(text = previous.text + "\n" + line, speech = previous.speech + "\n" + speech)
            else result.add(Block(line, english, speech))
        }
        return result
    }
}
