package com.alwayside.android

internal enum class SubtitleSegmentation(val title: String) {
    ORIGINAL("原始分段"), SENTENCE("优先完整句");
}

/** A local reading projection. Archived cues, translations and export timestamps stay untouched. */
internal object SubtitleReading {
    private const val MAX_GAP_MS = 1_200L
    private const val MAX_DURATION_MS = 18_000L
    private const val MAX_CHARACTERS = 360
    private val sentenceEnd = Regex("[.!?。！？][\\\"'”’）)]*$")
    private val abbreviation = Regex("(?i)(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|vs|etc)|\\b[A-Z])\\.$")

    fun project(bundle: SubtitleBundle, segmentation: SubtitleSegmentation): SubtitleBundle {
        if (segmentation == SubtitleSegmentation.ORIGINAL) return bundle
        val cues = mutableListOf<SubtitleCue>()
        val translations = linkedMapOf<Int, String>()
        var group = mutableListOf<SubtitleCue>()
        fun flush() {
            if (group.isEmpty()) return
            val first = group.first()
            cues += first.copy(endMs = group.last().endMs, text = group.joinToString(" ") { it.text.trim() })
            val chinese = group.map { bundle.translations[it.id].orEmpty().trim() }
            if (chinese.all(String::isNotBlank)) translations[first.id] = chinese.joinToString("")
            group = mutableListOf()
        }
        for (cue in bundle.cues) {
            val previous = group.lastOrNull()
            if (previous != null && (endsSentence(previous.text) || cue.startMs - previous.endMs > MAX_GAP_MS ||
                    cue.endMs - group.first().startMs > MAX_DURATION_MS ||
                    group.sumOf { it.text.length + 1 } + cue.text.length > MAX_CHARACTERS)) flush()
            group += cue
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

    private fun endsSentence(text: String): Boolean {
        val value = text.trim()
        return sentenceEnd.containsMatchIn(value) && !abbreviation.containsMatchIn(value)
    }
}
