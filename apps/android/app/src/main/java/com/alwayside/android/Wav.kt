package com.alwayside.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Wav {
    fun header(pcmBytes: Int, sampleRate: Int): ByteArray {
        require(pcmBytes >= 0 && pcmBytes <= Int.MAX_VALUE - 36 && pcmBytes % 2 == 0)
        require(sampleRate > 0 && sampleRate <= Int.MAX_VALUE / 2)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36+pcmBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate*2)
            putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(pcmBytes)
        }.array()
    }
}
