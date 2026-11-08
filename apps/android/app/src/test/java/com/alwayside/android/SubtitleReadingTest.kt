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

    @Test fun pausesDurationAndLengthDoNotCutAnUnfinishedSentence() {
        val continuation = "very ".repeat(100).trim()
        val source = bundle(listOf(SubtitleCue(1, 0, 2000, "This is"),
            SubtitleCue(2, 4000, 6000, continuation), SubtitleCue(3, 6200, 24000, "long"),
            SubtitleCue(4, 24200, 25000, "indeed!"), SubtitleCue(5, 25100, 26000, "Next")))
        val result = SubtitleReading.project(source, SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("This is $continuation long indeed!", "Next"), result.cues.map { it.text })
        assertEquals(25000L, result.cues.first().endMs)
        val abbreviated = bundle(listOf(SubtitleCue(1, 0, 1000, "Ask Dr."), SubtitleCue(2, 1100, 3000, "Smith.")))
        assertEquals(1, SubtitleReading.project(abbreviated, SubtitleSegmentation.SENTENCE).cues.size)
    }

    @Test fun splitsMultipleSentencesAndJoinsTheTrailingSentenceAcrossSegments() {
        // The reported case: a segment contains several complete sentences and the start of another.
        val original = bundle(listOf(
            SubtitleCue(7, 0, 16000, "OpenAI made an announcement. There are seven problems. This problem, just so"),
            SubtitleCue(8, 16000, 23000, "you understand it, concerns fluids under extreme conditions.")), mapOf(
            7 to "OpenAI 发布了一则公告。一共有七个问题。为了让你了解这个问题，",
            8 to "它与流体在极端条件下的表现有关。"))
        val result = SubtitleReading.project(original, SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("OpenAI made an announcement.", "There are seven problems.",
            "This problem, just so you understand it, concerns fluids under extreme conditions."), result.cues.map { it.text })
        assertEquals(listOf("OpenAI 发布了一则公告。", "一共有七个问题。", "为了让你了解这个问题，它与流体在极端条件下的表现有关。"),
            result.cues.map { result.translations[it.id] })
        assertEquals(result.cues.size, result.cues.map { it.id }.toSet().size)
        assertTrue(result.cues.last().startMs < 16000)
        assertEquals(23000L, result.cues.last().endMs)
        assertEquals(2, original.cues.size)
        assertEquals(7, original.cues.first().id)
        assertEquals("OpenAI 发布了一则公告。一共有七个问题。为了让你了解这个问题，", original.translations[7])
    }

    @Test fun splitTimesStayInsideTheSegmentAndDriveSentenceSelection() {
        val result = SubtitleReading.project(bundle(listOf(SubtitleCue(1, 1000, 12000, "First. Next!"))), SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("First.", "Next!"), result.cues.map { it.text })
        assertEquals(1000L, result.cues.first().startMs)
        assertEquals(12000L, result.cues.last().endMs)
        assertTrue(result.cues.first().endMs <= result.cues.last().startMs)
        assertEquals(0, SubtitleReading.activeIndex(result.cues, 1000, true))
        assertEquals(1, SubtitleReading.activeIndex(result.cues, result.cues.last().startMs, true))
    }

    @Test fun decimalsAbbreviationsQuotesAndChinesePunctuationKeepTheirMeaning() {
        val source = bundle(listOf(SubtitleCue(1, 0, 15000,
            "Dr. Smith paid 3.14 dollars at example.com. He said, \"Really?!\" I was... thinking. 我知道了。接着说！")))
        val result = SubtitleReading.project(source, SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("Dr. Smith paid 3.14 dollars at example.com.", "He said, \"Really?!\"", "I was... thinking.", "我知道了。", "接着说！"),
            result.cues.map { it.text })
    }

    @Test fun overlappingSegmentsDoNotReorderProjectedSentenceStarts() {
        val source = bundle(listOf(SubtitleCue(1, 0, 10000, "First. Second."),
            SubtitleCue(2, 1000, 3000, "Next.")))
        val result = SubtitleReading.project(source, SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("First.", "Second.", "Next."), result.cues.map { it.text })
        assertEquals(result.cues.map { it.startMs }.sorted(), result.cues.map { it.startMs })
        assertEquals(2, SubtitleReading.activeIndex(result.cues, 1000, true))
    }

    @Test fun incompleteTranslationSentenceContinuesEvenWhenSourceSegmentEndsWithAPeriod() {
        val source = bundle(listOf(SubtitleCue(1, 0, 3000, "It is about fluids."),
            SubtitleCue(2, 3100, 6000, "Conditions are extreme.")), mapOf(1 to "它与流体有关，", 2 to "条件非常极端。"))
        val result = SubtitleReading.project(source, SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("It is about fluids.", "Conditions are extreme."), result.cues.map { it.text })
        assertTrue(result.cues.all { result.translations[it.id] == "它与流体有关，条件非常极端。" })
    }

    @Test fun expandedTranslationKeepsWholeSentencesWithoutCuttingCharacters() {
        val result = SubtitleReading.project(bundle(listOf(SubtitleCue(1, 0, 6000, "Keep going, you can do it.")),
            mapOf(1 to "继续前进。你能做到！")), SubtitleSegmentation.SENTENCE)
        assertEquals(1, result.cues.size)
        assertEquals("继续前进。你能做到！", result.translations[result.cues.single().id])
    }

    @Test fun emptyTrackAndUnfinishedRecordingTailAreRetainedSafely() {
        assertTrue(SubtitleReading.project(bundle(emptyList()), SubtitleSegmentation.SENTENCE).cues.isEmpty())
        val result = SubtitleReading.project(bundle(listOf(SubtitleCue(1, 0, 4000, "Done. A final unfinished"))), SubtitleSegmentation.SENTENCE)
        assertEquals(listOf("Done.", "A final unfinished"), result.cues.map { it.text })
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
