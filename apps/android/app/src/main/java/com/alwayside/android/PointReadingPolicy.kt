package com.alwayside.android

import java.security.MessageDigest
import java.text.Normalizer

internal enum class ReadingOrigin { CHAT, IMAGE }

internal fun InteractionConfig.readingEnabled(origin: ReadingOrigin): Boolean = when (origin) {
    ReadingOrigin.CHAT -> chatPointReading
    ReadingOrigin.IMAGE -> imagePointReading
}

internal data class ReadingVoice(val connection: String = "", val model: String = "", val voice: String = "alloy") {
    val format: String get() = if (ReadingPresets.realtime(model)) "wav" else "mp3"
    fun key(text: String): String = readingDigest(listOf(if (format == "wav") "realtime-reading-v1" else "speech-v1", connection, model, voice, format,
        normalizeReadingText(text)).joinToString("\u0000"))
}

internal fun normalizeReadingText(text: String): String =
    Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n').trim(), Normalizer.Form.NFC)

internal fun readingDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
