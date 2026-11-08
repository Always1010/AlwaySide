package com.alwayside.android

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SubtitleEngineTest {
    @Test fun whisperUsesSegmentTimestampMultipartContract() {
        val whisper = SubtitleWire.prefix("boundary").toString(Charsets.UTF_8)
        assertTrue(whisper.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(whisper.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"))
        assertTrue(whisper.contains("name=\"language\"\r\n\r\nen\r\n"))
        assertTrue(whisper.contains("name=\"timestamp_granularities[]\"\r\n\r\nsegment\r\n"))
        assertFalse(whisper.contains("name=\"languages[]\""))
        assertFalse(whisper.contains("name=\"prompt\""))
        assertThrows(ProviderException::class.java) {
            SubtitleWire.validate(AppConfig(apiKey = "secret", baseUrl = "https://example.com/v1"))
        }
    }

    @Test fun timestampsStayOnOriginalTimelineAndMalformedSegmentsFail() {
        val part = SubtitleAudioPart(48_000L * 30, 48_000L * 35)
        val whisper = SubtitleWire.cues(mapOf("text" to "Hello world", "segments" to listOf(
            mapOf("start" to -0.01, "end" to 1.5, "text" to "Hello"),
            mapOf("start" to 1.49, "end" to 5.1, "text" to "world"),
        )), part)
        assertEquals(listOf(SubtitleCue(1, 30_000, 31_500, "Hello"), SubtitleCue(2, 31_490, 35_000, "world")), whisper)
        assertTrue(SubtitleWire.cues(mapOf("text" to "", "segments" to emptyList<Any>()), part).isEmpty())
        assertThrows(ProviderException::class.java) { SubtitleWire.cues(mapOf("text" to "Hello world"), part) }
        for (segments in listOf(
            listOf(mapOf("start" to Double.NaN, "end" to 2, "text" to "bad")),
            listOf(mapOf("start" to 1, "end" to 1, "text" to "bad")),
            listOf(mapOf("start" to -100, "end" to 100, "text" to "bad")),
            listOf(mapOf("start" to -0.251, "end" to 1, "text" to "bad")),
            listOf(mapOf("start" to 5.01, "end" to 6, "text" to "outside")),
            listOf(mapOf("start" to 2, "end" to 3, "text" to "second"),
                mapOf("start" to 0, "end" to 1, "text" to "first")),
            emptyList(),
        )) assertThrows(ProviderException::class.java) {
            SubtitleWire.cues(mapOf("text" to "bad", "segments" to segments), part)
        }
    }

    @Test fun overlappingSegmentsAndTrailingOverrunKeepTextWithinRealAudioBounds() {
        val part = SubtitleAudioPart(48_000L * 30, 48_000L * 35)
        val cues = SubtitleWire.cues(mapOf("text" to "Long nested tail", "segments" to listOf(
            mapOf("start" to 0, "end" to 4, "text" to "Long"),
            mapOf("start" to 1, "end" to 2, "text" to "nested"),
            mapOf("start" to 4, "end" to 30, "text" to "tail"),
        )), part)
        assertEquals(listOf(SubtitleCue(1, 30_000, 34_000, "Long"),
            SubtitleCue(2, 31_000, 32_000, "nested"), SubtitleCue(3, 34_000, 35_000, "tail")), cues)
        val bundle = SubtitleBundle("a".repeat(64), SubtitleMethod.WHISPER, part.endMs, "b".repeat(64),
            cues = cues, completedParts = 1, totalParts = 1, transcriptionComplete = true)
        SubtitleArchiveCodec.validate(bundle)
        assertEquals("Long\nnested\ntail", SubtitleQuestionTranscript.text(bundle))
        assertEquals(1, SubtitleTimeline.activeIndex(cues, 31_500))
        assertEquals(0, SubtitleTimeline.activeIndex(cues, 32_500))
        assertTrue(SubtitleFormat.srt(cues).contains("00:00:34,000 --> 00:00:35,000"))
    }

    @Test fun emptySegmentsAreIgnoredBeforeValidatingTheirUnusedTimestamps() {
        val part = SubtitleAudioPart(0, 48_000L * 5)
        val cues = SubtitleWire.cues(mapOf("text" to "Hello", "segments" to listOf(
            mapOf("start" to 0, "end" to 0, "text" to "  \n"),
            mapOf("start" to 0, "end" to 1, "text" to "Hello"),
            mapOf("start" to 30, "end" to 30, "text" to ""),
            mapOf("text" to " \t"),
        )), part)
        assertEquals(listOf(SubtitleCue(1, 0, 1000, "Hello")), cues)
        assertTrue(SubtitleWire.cues(mapOf("text" to "", "segments" to listOf(
            mapOf("start" to 0, "end" to 0, "text" to ""))), part).isEmpty())
    }

    @Test fun rejectsOverlongCueTextBeforeItCanReachTheArchive() {
        val part = SubtitleAudioPart(0, 48_000L * 3)
        val atLimit = "a".repeat(4_000)
        val tooLong = "$atLimit!"
        fun response(words: String): Map<String, Any?> = mapOf("text" to "transcript", "segments" to listOf(
            mapOf("start" to 0, "end" to 3, "text" to words)))
        assertEquals(atLimit, SubtitleWire.cues(response(atLimit), part).single().text)
        assertThrows(ProviderException::class.java) { SubtitleWire.cues(response(tooLong), part) }
    }

    @Test fun whisperSplitsAtSilenceWithoutDroppingSamplesAndRejectsInvalidWav() {
        val bytes = RecordingLimits.UPLOAD_PCM_BYTES + RecordingLimits.SAMPLE_RATE * 2
        val file = File.createTempFile("subtitle-slices-", ".wav")
        try {
            RandomAccessFile(file, "rw").use { source ->
                source.write(Wav.header(bytes, RecordingLimits.SAMPLE_RATE))
                source.setLength(44L + bytes)
            }
            val cancellation = RequestCancellation()
            val frames = SubtitleWav.frames(file, cancellation)
            val parts = SubtitleWav.whisperParts(file, frames, cancellation)
            assertEquals(2, parts.size)
            assertEquals(0L, parts.first().startFrame)
            assertEquals(frames, parts.last().endFrame)
            assertEquals(parts[0].endFrame, parts[1].startFrame)
            assertTrue(parts.all { (it.endFrame - it.startFrame) * 2 + 44 <= 20 * 1024 * 1024 })
            assertTrue(parts.first().endFrame < RecordingLimits.UPLOAD_PCM_BYTES / 2)
            cancellation.cancel()
            assertThrows(CancellationException::class.java) { SubtitleWav.whisperParts(file, frames, cancellation) }
            RandomAccessFile(file, "rw").use { it.seek(24); it.writeInt(16_000) }
            assertThrows(ProviderException::class.java) { SubtitleWav.frames(file, RequestCancellation()) }
        } finally { file.delete() }
    }
}
