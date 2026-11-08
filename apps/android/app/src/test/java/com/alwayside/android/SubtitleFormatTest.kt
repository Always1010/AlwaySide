package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleFormatTest {
    @Test fun bothExportsUseExactlyTheSameOriginalAudioTimeline() {
        val cues = listOf(SubtitleCue(3, 1250, 3200, "First\r\n\r\nparagraph"),
            SubtitleCue(7, 3_661_999, 3_662_000, "Last"))
        val source = SubtitleFormat.srt(cues)
        val translated = SubtitleFormat.srt(cues, mapOf(3 to "第一段", 7 to "最后一段"))
        assertTrue(source.startsWith("1\n00:00:01,250 --> 00:00:03,200\nFirst\nparagraph\n\n"))
        assertTrue(source.contains("2\n01:01:01,999 --> 01:01:02,000\nLast\n\n"))
        assertEquals(source.lines().filter { " --> " in it }, translated.lines().filter { " --> " in it })
        assertThrows(IllegalArgumentException::class.java) { SubtitleFormat.srt(cues, mapOf(3 to "缺一段")) }
    }

    @Test fun timelineHandlesGapsExclusiveEndsAndOverlappingSpeech() {
        val cues = listOf(SubtitleCue(1, 100, 1000, "Long"), SubtitleCue(2, 300, 500, "Short"),
            SubtitleCue(3, 1500, 2000, "After gap"))
        assertEquals(-1, SubtitleTimeline.activeIndex(cues, 99))
        assertEquals(0, SubtitleTimeline.activeIndex(cues, 100))
        assertEquals(1, SubtitleTimeline.activeIndex(cues, 300))
        assertEquals(0, SubtitleTimeline.activeIndex(cues, 500))
        assertEquals(-1, SubtitleTimeline.activeIndex(cues, 1000))
        assertEquals(2, SubtitleTimeline.activeIndex(cues, 1999))
        assertEquals(-1, SubtitleTimeline.activeIndex(cues, 2000))
        assertEquals(-1, SubtitleTimeline.activeIndex(emptyList(), 0))
    }
}
