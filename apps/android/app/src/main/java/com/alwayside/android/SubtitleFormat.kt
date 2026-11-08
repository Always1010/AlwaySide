package com.alwayside.android

import java.util.Locale

/** One timeline drives both exports; translations never get to change cue boundaries. */
internal object SubtitleFormat {
    fun srt(cues: List<SubtitleCue>, translations: Map<Int, String>? = null): String = buildString {
        cues.forEachIndexed { index, cue ->
            val text = if (translations == null) cue.text else requireNotNull(translations[cue.id])
            require(text.isNotBlank()) { "字幕内容为空" }
            append(index + 1).append('\n')
            append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
            // Blank lines delimit SRT blocks, so normalize paragraph breaks inside a cue.
            append(text.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
                .map(String::trim).filter(String::isNotEmpty).joinToString("\n"))
            append("\n\n")
        }
    }

    private fun timestamp(ms: Long): String {
        require(ms >= 0) { "字幕时间无效" }
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
            ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}

internal object SubtitleTimeline {
    /** Cue ends are exclusive. When speech overlaps, display the most recently started cue. */
    fun activeIndex(cues: List<SubtitleCue>, positionMs: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        for (index in low - 1 downTo 0) {
            if (positionMs < cues[index].endMs) return index
        }
        return -1
    }
}
