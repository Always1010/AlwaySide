package com.alwayside.android

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class WavTranscriptionTest {
    @Test fun prefersSilenceNearLimitWithoutLosingOrRepeatingPcmBytes() {
        val bytesPerSecond = RecordingLimits.SAMPLE_RATE * 2
        val limit = RecordingLimits.UPLOAD_PCM_BYTES
        val pcm = ByteArray(limit + bytesPerSecond) { ((it % 251) + 1).toByte() }
        val silenceStart = limit - 2 * bytesPerSecond
        val silenceEnd = limit - bytesPerSecond
        pcm.fill(0, silenceStart, silenceEnd)
        withClip(pcm.size) { file ->
            RandomAccessFile(file, "rw").use { it.seek(44); it.write(pcm) }
            var offset = 0
            var calls = 0
            val result = WavTranscription.transcribe(file, RequestCancellation(), 1000) { source, bytes ->
                assertEquals(44L + offset, source.filePointer)
                assertTrue(bytes <= limit)
                assertEquals(0, bytes % 2)
                if (calls == 0) assertTrue(bytes in silenceStart until silenceEnd)
                val actual = ByteArray(bytes)
                source.readFully(actual)
                assertArrayEquals(pcm.copyOfRange(offset, offset + bytes), actual)
                offset += bytes
                "part${++calls}"
            }
            assertEquals(pcm.size, offset)
            assertEquals(2, calls)
            assertEquals("part1\npart2", result)
        }
    }

    @Test fun tenMinutesUploadsAllSamplesInOrderWithinPerRequestLimit() = withClip(RecordingLimits.pcmBytes(InteractionConfig().recordingMaxMinutes)) { file ->
        val lengths = mutableListOf<Int>()
        var nextPosition = 44L
        val result = WavTranscription.transcribe(file, RequestCancellation(), 1000) { source, bytes ->
            assertEquals(nextPosition, source.filePointer)
            assertTrue(bytes + 44 <= 20 * 1024 * 1024)
            assertEquals(0, bytes % 2)
            lengths.add(bytes)
            source.seek(source.filePointer + bytes)
            nextPosition += bytes
            "part${lengths.size}"
        }
        assertEquals(3, lengths.size)
        assertEquals(48_000 * 2 * 600, lengths.sum())
        assertEquals(file.length(), nextPosition)
        assertEquals("part1\npart2\npart3", result)
    }

    @Test fun cancellingAfterFirstPartPreventsFurtherRequests() = withClip(RecordingLimits.UPLOAD_PCM_BYTES + 2) { file ->
        val cancellation = RequestCancellation()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            WavTranscription.transcribe(file, cancellation, 1000) { source, bytes ->
                calls++
                source.seek(source.filePointer + bytes)
                cancellation.cancel()
                "first"
            }
        }
        assertEquals(1, calls)
    }

    @Test fun silentPartDoesNotDiscardLaterSpeech() = withClip(RecordingLimits.UPLOAD_PCM_BYTES + 2) { file ->
        var calls = 0
        val result = WavTranscription.transcribe(file, RequestCancellation(), 1000) { source, bytes ->
            source.seek(source.filePointer + bytes)
            if (++calls == 1) "" else "speech"
        }
        assertEquals("speech", result)
    }

    @Test fun rejectsChangedHeaderBeforeUploading() = withClip(100) { file ->
        RandomAccessFile(file, "rw").use { it.seek(40); it.writeInt(0) }
        assertThrows(ProviderException::class.java) {
            WavTranscription.transcribe(file, RequestCancellation(), 1000) { _, _ -> fail("must not upload"); "" }
        }
    }

    @Test fun unlimitedUsesWavCapacityAndInvalidMinutesAreRejected() {
        assertEquals(10, InteractionConfig().recordingMaxMinutes)
        assertEquals(RecordingLimits.MAX_PCM_BYTES, RecordingLimits.pcmBytes(0))
        Wav.header(RecordingLimits.pcmBytes(0), RecordingLimits.SAMPLE_RATE)
        assertThrows(IllegalArgumentException::class.java) { RecordingLimits.pcmBytes(-1) }
        assertThrows(IllegalArgumentException::class.java) { RecordingLimits.pcmBytes(RecordingLimits.MAX_MINUTES + 1) }
    }

    private fun withClip(bytes: Int, block: (File) -> Unit) {
        val file = File.createTempFile("alwayside-wav-", ".wav")
        try {
            RandomAccessFile(file, "rw").use {
                it.write(Wav.header(bytes, RecordingLimits.SAMPLE_RATE))
                it.setLength(bytes.toLong() + 44)
            }
            block(file)
        } finally { file.delete() }
    }
}
