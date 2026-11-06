package com.alwayside.android

import java.io.File
import java.io.RandomAccessFile

/** Reads the recorder's WAV sequentially so even long clips use a bounded upload buffer. */
internal object WavTranscription {
    private const val PCM_BYTES_PER_SECOND = RecordingLimits.SAMPLE_RATE * 2
    private const val SILENCE_SEARCH_BYTES = PCM_BYTES_PER_SECOND * 5
    private const val MIN_SILENCE_BYTES = PCM_BYTES_PER_SECOND / 5
    private const val SILENCE_AMPLITUDE = 327 // About 1% of PCM16 full scale (RMS).

    fun transcribe(file: File, cancellation: RequestCancellation, maxChars: Int,
                   sendPart: (RandomAccessFile, Int) -> String): String {
        cancellation.throwIfCancelled()
        val fileSize = file.length()
        if (!file.isFile || fileSize <= 44 || fileSize > RecordingLimits.MAX_PCM_BYTES.toLong() + 44)
            throw ProviderException("音频为空或超过 WAV 文件大小限制，请缩短录制")
        RandomAccessFile(file, "r").use { source ->
            val header = ByteArray(44)
            source.readFully(header)
            val pcmBytes = (fileSize - 44).toInt()
            if (!header.contentEquals(Wav.header(pcmBytes, RecordingLimits.SAMPLE_RATE)))
                throw ProviderException("录音 WAV 格式无效，请重新录制")
            val transcript = StringBuilder()
            var remaining = pcmBytes
            while (remaining > 0) {
                cancellation.throwIfCancelled()
                val partBytes = if (remaining <= RecordingLimits.UPLOAD_PCM_BYTES) remaining
                    else findPartBoundary(source, cancellation)
                val text = sendPart(source, partBytes).trim()
                cancellation.throwIfCancelled()
                if (source.filePointer != fileSize - remaining + partBytes)
                    throw ProviderException("音频分段读取不完整，请重新录制")
                if (text.isNotEmpty()) {
                    if (transcript.length + text.length + (if (transcript.isEmpty()) 0 else 1) > maxChars)
                        throw ProviderException("转写文字过长，请缩短录制")
                    if (transcript.isNotEmpty()) transcript.append('\n')
                    transcript.append(text)
                }
                remaining -= partBytes
            }
            if (source.length() != fileSize) throw ProviderException("音频文件在发送时发生变化，请重新录制")
            if (transcript.isEmpty()) throw ProviderException("未识别到可用的音频文字")
            return transcript.toString()
        }
    }

    /** Prefer a pause near the upload limit; every sample still belongs to exactly one part. */
    private fun findPartBoundary(source: RandomAccessFile, cancellation: RequestCancellation): Int {
        val start = source.filePointer
        val limit = RecordingLimits.UPLOAD_PCM_BYTES
        var offset = maxOf(0, limit - SILENCE_SEARCH_BYTES)
        val window = ByteArray(PCM_BYTES_PER_SECOND / 50) // 20 ms, independent of clip length.
        var quietStart = offset
        var boundary = limit
        try {
            source.seek(start + offset)
            while (offset < limit) {
                cancellation.throwIfCancelled()
                val bytes = minOf(window.size, limit - offset)
                source.readFully(window, 0, bytes)
                var energy = 0L
                for (i in 0 until bytes step 2) {
                    val sample = ((window[i].toInt() and 0xff) or (window[i + 1].toInt() shl 8)).toShort().toInt()
                    energy += sample.toLong() * sample
                }
                offset += bytes
                if (energy <= SILENCE_AMPLITUDE.toLong() * SILENCE_AMPLITUDE * (bytes / 2)) {
                    if (offset - quietStart >= MIN_SILENCE_BYTES) {
                        boundary = (quietStart + (offset - quietStart) / 2) and -2
                    }
                } else {
                    quietStart = offset
                }
            }
        } finally {
            source.seek(start)
        }
        return boundary
    }
}
