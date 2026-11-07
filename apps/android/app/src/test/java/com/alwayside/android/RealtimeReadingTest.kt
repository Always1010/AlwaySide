package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class RealtimeReadingTest {
    @Test fun defaultAndModelChangesUseVoicesSupportedByTheirActualProtocol() {
        assertEquals("gpt-realtime-2.1-mini",ReadingPresets.defaultModel(ProviderKind.OPENAI))
        assertTrue(ReadingPresets.realtime(ReadingPresets.DEFAULT_MODEL))
        assertEquals("marin",ReadingPresets.defaultVoice(ReadingPresets.DEFAULT_MODEL))
        assertTrue("marin" in ReadingPresets.voices("gpt-realtime-2.1"))
        assertFalse("nova" in ReadingPresets.voices("gpt-realtime-2.1-mini"))
        assertEquals("alloy",ReadingPresets.compatibleVoice("tts-1","marin"))
        assertEquals("my-voice",ReadingPresets.compatibleVoice("tts-1","my-voice"))
        assertEquals("",ReadingPresets.defaultModel(ProviderKind.QWEN))
        assertFalse(ReadingPresets.realtime("gpt-realtime-2.1-mini-invalid"))
        val choices=ReadingPresets.supportedModels(listOf(RemoteModel("gpt-realtime-2.1"),RemoteModel("gpt-audio-1.5"),
            RemoteModel("gpt-realtime-2.1-mini"),RemoteModel("chat-model",outputModalities=setOf("audio"))))
        assertEquals(listOf("gpt-realtime-2.1-mini","gpt-realtime-2.1"),choices.map { it.id })
    }

    @Test fun clientFramesAreMaskedAtShortAndExtendedLengths() {
        for(length in listOf(5,126,70000)) {
            val output=ByteArrayOutputStream()
            ReadingWebSocketFrames(ByteArrayInputStream(byteArrayOf()),output).send("a".repeat(length))
            val bytes=ByteArrayInputStream(output.toByteArray())
            assertEquals(0x81,bytes.read())
            val second=bytes.read();assertTrue(second and 0x80!=0)
            val marker=second and 127
            val size=when(marker) { 126 -> bytes.read()*256+bytes.read();127 -> { var value=0L;repeat(8){value=value*256+bytes.read()};value.toInt() };else -> marker }
            assertEquals(length,size)
            val mask=ByteArray(4);assertEquals(4,bytes.read(mask))
            repeat(length) { assertEquals('a'.code,bytes.read() xor (mask[it%4].toInt() and 255)) }
        }
    }

    @Test fun fragmentedEventsAllowInterleavedPingAndReplyWithMaskedPong() {
        val input=serverFrame(1,"{\"type\":",false)+serverFrame(9,"ping")+serverFrame(0,"\"done\"}")
        val output=ByteArrayOutputStream()
        val result=ReadingWebSocketFrames(ByteArrayInputStream(input),output).receive()
        assertEquals("{\"type\":\"done\"}",result)
        assertEquals(0x8a,output.toByteArray()[0].toInt() and 255)
        assertTrue(output.toByteArray()[1].toInt() and 0x80!=0)
    }

    @Test fun invalidFramesAndUnboundedEventsFailBeforePayloadAllocation() {
        for(bytes in listOf(byteArrayOf(0x81.toByte(),0x80.toByte()),byteArrayOf(0xc1.toByte(),0),
            byteArrayOf(0x80.toByte(),0),byteArrayOf(0x81.toByte(),127,0,0,0,0,1,0,0,0))) {
            assertThrows(IllegalArgumentException::class.java) {
                ReadingWebSocketFrames(ByteArrayInputStream(bytes),ByteArrayOutputStream()).receive()
            }
        }
    }

    @Test fun handshakeAuthenticatesTheUpgradeAndRejectsRedirects() {
        val key="dGhlIHNhbXBsZSBub25jZQ=="
        val response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        RealtimeReading.handshake(ByteArrayInputStream(response.toByteArray()),key)
        assertThrows(IllegalArgumentException::class.java) { RealtimeReading.handshake(ByteArrayInputStream(response.toByteArray()),"wrong") }
        assertThrows(ProviderException::class.java) { RealtimeReading.handshake(ByteArrayInputStream("HTTP/1.1 302 Redirect\r\n\r\n".toByteArray()),key) }
    }

    @Test fun onlyCompleteVerbatimAudioCanBecomeACachedWav() {
        val decoder=RealtimeReadingDecoder("Hello, world!")
        assertThrows(IllegalStateException::class.java) { decoder.audio() }
        decoder.accept(mapOf("type" to "response.output_audio.delta","delta" to Base64.getEncoder().encodeToString(byteArrayOf(1,2,3,4))))
        decoder.accept(mapOf("type" to "response.output_audio_transcript.delta","delta" to "Hello world."))
        assertTrue(decoder.accept(mapOf("type" to "response.done","response" to mapOf("status" to "completed"))))
        val wav=decoder.audio()
        assertEquals("RIFF",wav.take(4).toByteArray().toString(Charsets.US_ASCII));assertEquals(48,wav.size)
        val mismatch=RealtimeReadingDecoder("Read this.")
        mismatch.accept(mapOf("type" to "response.output_audio.delta","delta" to "AQI="))
        mismatch.accept(mapOf("type" to "response.output_audio_transcript.delta","delta" to "Here is an answer."))
        assertThrows(IllegalArgumentException::class.java) { mismatch.accept(mapOf("type" to "response.done","response" to mapOf("status" to "completed"))) }
        val request=RealtimeReading.request("Read this.","marin").obj("response")
        assertEquals("none",request.text("conversation"));assertEquals(listOf("audio"),request["output_modalities"])
        assertEquals("marin",request.obj("audio").obj("output").text("voice"))
    }

    @Test fun cancellingARealtimeRequestClosesItsSocket() {
        val cancel=RequestCancellation();val socket=java.net.Socket()
        cancel.attach(socket);cancel.cancel()
        assertTrue(socket.isClosed)
        assertThrows(java.util.concurrent.CancellationException::class.java) { cancel.throwIfCancelled() }
    }
    private fun serverFrame(opcode:Int,text:String,fin:Boolean=true):ByteArray {
        val bytes=text.toByteArray()
        require(bytes.size<126)
        return byteArrayOf(((if(fin)0x80 else 0) or opcode).toByte(),bytes.size.toByte())+bytes
    }
}
