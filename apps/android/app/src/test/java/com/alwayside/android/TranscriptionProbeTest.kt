package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranscriptionProbeTest {
    @Test fun silentProbeUsesRecorderFormatAndAcceptsAnEmptySuccessfulResult() {
        val file = File.createTempFile("transcription-probe-", ".wav")
        try {
            file.writeBytes(TranscriptionProbe.audio())
            val result = TranscriptionProbe.read(file) { source, bytes ->
                assertEquals(44L, source.filePointer)
                assertEquals(RecordingLimits.SAMPLE_RATE * 2, bytes)
                val pcm = ByteArray(bytes).also(source::readFully)
                assertTrue(pcm.all { it == 0.toByte() })
                ""
            }
            assertEquals("", result)
            file.writeBytes(Wav.header(32000, 16000) + ByteArray(32000))
            assertThrows(IllegalArgumentException::class.java) {
                TranscriptionProbe.read(file) { _, _ -> fail("invalid fixture must not upload") }
            }
        } finally { file.delete() }
    }
}
