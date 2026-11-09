package com.alwayside.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ConversationAudioArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recording = Wav.header(320, 16000) + ByteArray(320) { it.toByte() }

    @Test fun recordingSurvivesReopeningAndIsRemovedWithItsConversation() {
        val directory = temporary.newFolder()
        val archive = ConversationAudioArchive(directory)
        assertNull(archive.load("first"))
        recording.inputStream().use { archive.save("first", it, recording.size.toLong()) }
        recording.inputStream().use { archive.save("second", it, recording.size.toLong()) }
        val reopened = ConversationAudioArchive(directory)
        assertArrayEquals(recording, reopened.load("first")!!.readBytes())
        reopened.delete("first")
        assertNull(archive.load("first"))
        assertNotNull(archive.load("second"))
        reopened.clear()
        assertNull(archive.load("second"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun additionalRecordingsDoNotEvictEarlierAudio() {
        val directory = temporary.newFolder()
        val archive = ConversationAudioArchive(directory)
        recording.inputStream().use { archive.save("first", it, recording.size.toLong()) }
        recording.inputStream().use { archive.save("second", it, recording.size.toLong()) }
        assertArrayEquals(recording, archive.load("first")!!.readBytes())
        assertNotNull(archive.load("second"))
        archive.delete("first")
        recording.inputStream().use { archive.save("second", it, recording.size.toLong()) }
        assertNotNull(archive.load("second"))
    }

    @Test fun interruptedOrChangedCopyLeavesNoPartialAudioAndKeepsPreviousRecording() {
        val directory = temporary.newFolder()
        val archive = ConversationAudioArchive(directory)
        recording.inputStream().use { archive.save("first", it, recording.size.toLong()) }
        val broken = object : InputStream() {
            private val source = ByteArrayInputStream(recording)
            override fun read(): Int = source.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (source.available() < recording.size) throw IOException("read failed")
                return source.read(buffer, offset, minOf(50, length))
            }
        }
        assertThrows(IOException::class.java) { broken.use { archive.save("first", it, recording.size.toLong()) } }
        assertThrows(IllegalStateException::class.java) {
            recording.inputStream().use { archive.save("truncated", it, recording.size.toLong() + 2) }
        }
        assertThrows(IllegalStateException::class.java) {
            recording.inputStream().use { archive.save("grown", it, recording.size.toLong() - 2) }
        }
        assertArrayEquals(recording, archive.load("first")!!.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }
}
