package com.alwayside.android

import kotlin.math.roundToLong

internal enum class SubtitleSegmentation(val title: String) {
    ORIGINAL("原始分段"), SENTENCE("优先完整句");
}

/** A local reading projection. Archived cues, translations and export timestamps stay untouched. */
internal object SubtitleReading {
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "e.g", "i.e")
    private const val ENDINGS = ".!?。！？"
    private const val CLOSERS = "\"'”’）)]】」』"
    private data class Range(val start: Int, val end: Int, val complete: Boolean)
    private data class Piece(val cue: SubtitleCue, val start: Int, val end: Int, val nextStartMs: Long?)
    private data class Track(val text: String, val pieces: List<Piece>) {
        // Only segment timestamps are archived. Interpolate new boundaries inside a segment,
        // retaining segment start/end at its edges rather than spreading its pauses.
        fun time(offset: Int, end: Boolean): Long {
            val index = if (end) pieces.indexOfLast { it.start < offset } else pieces.indexOfFirst { it.end > offset }
            val piece = pieces[index]
            val fraction = (offset - piece.start).toDouble() / (piece.end - piece.start)
            if (fraction >= 1.0) return piece.cue.endMs
            // Provider segments may overlap. Keep interpolated starts ordered for the timeline's
            // binary search, while preserving an original end at an actual segment boundary.
            val limit = minOf(piece.cue.endMs, piece.nextStartMs ?: piece.cue.endMs)
            return piece.cue.startMs + ((limit - piece.cue.startMs) * fraction.coerceAtLeast(0.0)).roundToLong()
        }

        fun anchor(offset: Int, end: Boolean): Double {
            val index = if (end) pieces.indexOfLast { it.start < offset } else pieces.indexOfFirst { it.end > offset }
            val piece = pieces[index]
            return index + (offset - piece.start).toDouble().div(piece.end - piece.start).coerceIn(0.0, 1.0)
        }
    }

    fun project(bundle: SubtitleBundle, segmentation: SubtitleSegmentation): SubtitleBundle {
        if (segmentation == SubtitleSegmentation.ORIGINAL) return bundle
        val cues = mutableListOf<SubtitleCue>()
        val translations = linkedMapOf<Int, String>()
        val nextStarts = bundle.cues.zipWithNext().associate { (cue, next) -> cue.id to next.startMs }
        val group = mutableListOf<SubtitleCue>()
        fun flush() {
            if (group.isEmpty()) return
            val source = track(group, nextStarts) { it.text }
            val ranges = sentences(source.text)
            val chinese = if (group.all { !bundle.translations[it.id].isNullOrBlank() })
                track(group) { bundle.translations.getValue(it.id) } else null
            val translated = chinese?.let { sentences(it.text) }.orEmpty()
            ranges.forEachIndexed { index, sentence ->
                val id = cues.size + 1 // One archived cue can produce several projection IDs.
                val start = source.time(sentence.start, false)
                val end = source.time(sentence.end, true)
                cues += SubtitleCue(id, start, maxOf(start + 1, end), source.text.substring(sentence.start, sentence.end))
                if (chinese != null) {
                    val matching = if (translated.size == ranges.size) listOf(translated[index]) else {
                        // Translation may combine or expand source sentences. Preserve whole
                        // translated sentences instead of cutting Chinese at arbitrary characters.
                        val from = source.anchor(sentence.start, false)
                        val to = source.anchor(sentence.end, true)
                        translated.filter { chinese.anchor(it.start, false) < to && chinese.anchor(it.end, true) > from }
                    }
                    if (matching.isNotEmpty()) translations[id] = matching.joinToString("") {
                        chinese.text.substring(it.start, it.end)
                    }
                }
            }
            group.clear()
        }
        for (cue in bundle.cues) {
            if (cue.text.isBlank()) continue
            group += cue
            // Only sentence boundaries shared by both tracks end a group. Duration, size and
            // pauses must not cut a sentence in half. Retain an unfinished final sentence at EOF.
            val chinese = bundle.translations[cue.id]
            if (endsSentence(cue.text) && (chinese.isNullOrBlank() || endsSentence(chinese))) flush()
        }
        flush()
        return bundle.copy(cues = cues, translations = translations)
    }

    /** Brief gaps retain the previous cue; a paused player freezes the most recent sentence. */
    fun activeIndex(cues: List<SubtitleCue>, positionMs: Long, playing: Boolean): Int {
        val active = SubtitleTimeline.activeIndex(cues, positionMs)
        if (active >= 0) return active
        val previous = cues.indexOfLast { it.endMs <= positionMs }
        return previous.takeIf { it >= 0 && (!playing || positionMs - cues[it].endMs < 550) } ?: -1
    }

    private fun track(cues: List<SubtitleCue>, nextStarts: Map<Int, Long> = emptyMap(), value: (SubtitleCue) -> String): Track {
        val text = StringBuilder()
        val pieces = cues.map { cue ->
            val part = value(cue).trim()
            if (text.isNotEmpty() && !(isCjk(text.last()) && isCjk(part.first()))) text.append(' ')
            val start = text.length
            text.append(part)
            Piece(cue, start, text.length, nextStarts[cue.id])
        }
        return Track(text.toString(), pieces)
    }

    private fun isCjk(char: Char) = char in '\u2E80'..'\u9FFF' || char in '\uF900'..'\uFAFF' || char in "。！？，”’」』"

    private fun sentences(text: String): List<Range> {
        val result = mutableListOf<Range>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace() && index == start) { start++; index++; continue }
            if (!isSentenceEnd(text, index)) { index++; continue }
            var end = index + 1
            while (end < text.length && (text[end] in ENDINGS || text[end] in CLOSERS)) end++
            result += Range(start, end, true)
            start = end
            index = end
        }
        val end = text.trimEnd().length
        if (start < end) result += Range(start, end, false)
        return result
    }

    private fun isSentenceEnd(text: String, index: Int): Boolean {
        val char = text[index]
        if (char !in ENDINGS) return false
        if (char != '.') return true
        val previous = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (previous == '.' || next == '.') {
            val runEnd = (index until text.length).firstOrNull { text[it] != '.' } ?: text.length
            val following = text.substring(runEnd).firstOrNull { !it.isWhitespace() && it !in CLOSERS }
            if (following?.isLowerCase() == true) return false
        }
        if (previous?.isDigit() == true && next?.isDigit() == true) return false
        if (previous?.isLetter() == true && next?.isLetterOrDigit() == true) return false
        val wordStart = (index - 1 downTo 0).firstOrNull { !text[it].isLetter() && text[it] != '.' }?.plus(1) ?: 0
        val word = text.substring(wordStart, index)
        if (word.lowercase(java.util.Locale.ROOT) in abbreviations) return false
        if (word.length == 1 && word[0] in 'A'..'Z' && word != "I") return false
        if (word.contains('.') && word.split('.').all { it.length == 1 && it[0].isLetter() }) return false
        return true
    }

    private fun endsSentence(text: String): Boolean = sentences(text.trim()).lastOrNull()?.complete == true
}
