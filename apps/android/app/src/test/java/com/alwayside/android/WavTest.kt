package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test fun tenMinuteClipHasCorrectRiffSizeAndMonoPcmLayout() {
        val bytes=48000*2*600
        val data=Wav.header(bytes,48000)
        val buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44,data.size)
        assertEquals("RIFF",String(data,0,4,Charsets.US_ASCII))
        assertEquals(bytes+36,buffer.getInt(4))
        assertEquals(1,buffer.getShort(22).toInt())
        assertEquals(48000,buffer.getInt(24))
        assertEquals(96000,buffer.getInt(28))
        assertEquals(16,buffer.getShort(34).toInt())
        assertEquals(bytes,buffer.getInt(40))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsIncompleteSample() { Wav.header(3,48000) }
}
