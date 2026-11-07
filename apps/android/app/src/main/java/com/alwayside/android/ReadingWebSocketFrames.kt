package com.alwayside.android

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/** RFC 6455 client framing; no extensions, no binary messages, bounded JSON messages only. */
internal class ReadingWebSocketFrames(private val input: InputStream, private val output: OutputStream,
    private val check: () -> Unit = {}) {
    private val random = SecureRandom()
    private var total = 0L
    private var frames = 0
    fun send(text: String) = sendFrame(1, text.toByteArray(Charsets.UTF_8))
    fun close() = sendFrame(8, byteArrayOf(3, 232.toByte())) // Normal closure, code 1000.
    private fun sendFrame(opcode: Int, bytes: ByteArray) {
        require(bytes.size <= MAX_MESSAGE) { "Realtime 请求过大" }
        check()
        output.write(0x80 or opcode)
        when {
            bytes.size < 126 -> output.write(0x80 or bytes.size)
            bytes.size <= 65535 -> { output.write(0x80 or 126); output.write(bytes.size ushr 8); output.write(bytes.size and 255) }
            else -> {
                output.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) output.write((bytes.size.toLong() ushr shift).toInt() and 255)
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(mask)
        val masked = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
        output.write(masked); output.flush()
    }
    fun receive(): String {
        val message = ByteArrayOutputStream()
        var fragmented = false
        while (true) {
            check()
            require(++frames <= 10000) { "Realtime 事件过多" }
            val first = byte(); val second = byte()
            require(first and 0x70 == 0 && second and 0x80 == 0) { "Realtime WebSocket 帧无效" }
            val finished = first and 0x80 != 0
            val opcode = first and 15
            var length = (second and 127).toLong()
            if (length == 126L) length = (byte() * 256 + byte()).toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { require(length <= MAX_MESSAGE) { "Realtime 事件过大" }; length = length * 256 + byte() }
            }
            require(length <= MAX_MESSAGE) { "Realtime 事件过大" }
            total += length
            require(total <= 32L * 1024 * 1024) { "Realtime 返回内容过大" }
            if (opcode >= 8) require(finished && length <= 125) { "Realtime 控制帧无效" }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                check()
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("Realtime 连接中断")
                offset += count
            }
            when (opcode) {
                8 -> { if (bytes.size != 1) runCatching { sendFrame(8, bytes) }; throw EOFException("Realtime 连接已关闭") }
                9 -> { sendFrame(10, bytes); continue }
                10 -> continue
                1 -> require(!fragmented) { "Realtime 分片顺序无效" }
                0 -> require(fragmented) { "Realtime 分片顺序无效" }
                else -> throw IllegalArgumentException("Realtime 返回了非文字事件")
            }
            require(message.size() + bytes.size <= MAX_MESSAGE) { "Realtime 事件过大" }
            message.write(bytes)
            if (finished) return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(message.toByteArray())).toString()
            fragmented = true
        }
    }
    private fun byte(): Int = input.read().also { if (it < 0) throw EOFException("Realtime 连接中断") }
    companion object { const val MAX_MESSAGE = 2 * 1024 * 1024 }
}
