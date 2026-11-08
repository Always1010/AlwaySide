package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleReadingTest {
    private fun bundle(cues: List<SubtitleCue>, translations: Map<Int, String> = emptyMap()) =
        SubtitleBundle("a".repeat(64), SubtitleMethod.WHISPER, 60_000, "b".repeat(64), cues, translations)

    @Test fun completeSentenceJoinsMatchingLanguagesWithoutChangingArchivedCues() {
        val original = bundle(listOf(SubtitleCue(1, 0, 2000, "Find a small habit"),
            SubtitleCue(2, 2200, 4000, "you can return to."), SubtitleCue(3, 4200, 6000, "Keep going.")),
            mapOf(1 to "找到一个小习惯，", 2 to "每天坚持。", 3 to "继续前进。"))
        val result = SubtitleReading.project(original, SubtitleSegmentation.SENTENCE)
        assertEquals(2, result.cues.size)
        assertEquals("Find a small habit you can return to.", result.cues[0].text)
        assertEquals("找到一个小习惯，每天坚持。", result.translations[1])
        assertEquals(4000L, result.cues[0].endMs)
        assertEquals(3, original.cues.size)
        assertSame(original, SubtitleReading.project(original, SubtitleSegmentation.ORIGINAL))
    }

    @Test fun longPauseDurationAndSentencePunctuationBoundGroups() {
        val source = bundle(listOf(SubtitleCue(1, 0, 2000, "Hello"),
            SubtitleCue(2, 4000, 6000, "again"), SubtitleCue(3, 6200, 24000, "and again"),
            SubtitleCue(4, 24200, 25000, "Done!"), SubtitleCue(5, 25100, 26000, "Next")))
        assertEquals(5, SubtitleReading.project(source, SubtitleSegmentation.SENTENCE).cues.size)
        val abbreviated = bundle(listOf(SubtitleCue(1, 0, 1000, "Ask Dr."), SubtitleCue(2, 1100, 3000, "Smith.")))
        assertEquals(1, SubtitleReading.project(abbreviated, SubtitleSegmentation.SENTENCE).cues.size)
    }

    @Test fun incompleteTranslationsDoNotPretendTheWholeSentenceIsTranslated() {
        val result = SubtitleReading.project(bundle(listOf(SubtitleCue(1, 0, 1000, "A small"),
            SubtitleCue(2, 1100, 2000, "habit.")), mapOf(1 to "一个小")), SubtitleSegmentation.SENTENCE)
        assertNull(result.translations[1])
    }

    @Test fun shortGapsAndPausesRetainTextButLongPlayingSilenceClearsIt() {
        val cues = listOf(SubtitleCue(1, 1000, 2000, "Hello"), SubtitleCue(2, 4000, 5000, "Again"))
        assertEquals(-1, SubtitleReading.activeIndex(cues, 0, false))
        assertEquals(0, SubtitleReading.activeIndex(cues, 2100, true))
        assertEquals(-1, SubtitleReading.activeIndex(cues, 3000, true))
        assertEquals(0, SubtitleReading.activeIndex(cues, 3000, false))
        assertEquals(1, SubtitleReading.activeIndex(cues, 4000, true))
    }
}
