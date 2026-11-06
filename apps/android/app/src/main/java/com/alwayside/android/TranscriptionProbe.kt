package com.alwayside.android

import java.io.File
import java.io.RandomAccessFile

/** One recorder-compatible silent second tests file acceptance, not speech recognition. */
internal object TranscriptionProbe {
    private const val PCM_BYTES = RecordingLimits.SAMPLE_RATE * 2
    fun audio(): ByteArray = Wav.header(PCM_BYTES, RecordingLimits.SAMPLE_RATE) + ByteArray(PCM_BYTES)

    fun <T> read(file: File, send: (RandomAccessFile, Int) -> T): T = RandomAccessFile(file, "r").use { source ->
        require(source.length() == PCM_BYTES.toLong() + 44) { "测试音频长度无效" }
        val header = ByteArray(44).also(source::readFully)
        require(header.contentEquals(Wav.header(PCM_BYTES, RecordingLimits.SAMPLE_RATE))) { "测试音频格式无效" }
        send(source, PCM_BYTES)
    }
}
