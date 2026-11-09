package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PointReadingMarksTest {
    private val voice = ReadingVoice("service", "tts-1", "alloy")
    private fun anchor(text: String, start: Int = 0, end: Int = text.length, block: String = ReadingAnchor.message(0, "assistant")) =
        requireNotNull(ReadingAnchor.create(block, text, start, end))
    private fun mark(anchor: ReadingAnchor, voice: ReadingVoice = this.voice) = ReadingMark(anchor, voice.key(anchor.quote), voice)

    @Test fun displayedRangesTrimWhitespaceWithoutChangingUnicodeOrInteriorText() {
        val text = "前文  Hello, 世界 👋!  后文"
        val selected = anchor(text, 2, text.indexOf("后文"))
        assertEquals("Hello, 世界 👋!", selected.quote)
        assertEquals(selected.quote, text.substring(selected.locate(text)!!))
        assertNull(ReadingAnchor.create("source", text, -1, 3))
        assertNull(ReadingAnchor.create("source", "   ", 0, 3))
    }

    @Test fun restoredRangesUseExactContextAndRejectAmbiguousOrChangedText() {
        val fragment = "Before Hello, world! After"
        val selected = anchor(fragment, 7, 20)
        val expanded = "An earlier paragraph.\n$fragment\nLater."
        assertEquals("Hello, world!", expanded.substring(selected.locate(expanded)!!))
        assertNull(selected.locate("$fragment\n$fragment"))
        assertNull(selected.locate(fragment.replace("world", "there")))
        assertNull(selected.locate("Other Hello, world! Context"))
    }

    @Test fun recordsSurviveReopeningKeepOverlappingRangesAndRetainTheirOriginalVoice() = withDirectory { directory ->
        val store = PointReadingMarks(directory)
        val sentence = anchor("Could you say that again?")
        val word = anchor(sentence.quote, 19, 24)
        store.put("chat-a", mark(sentence)); store.put("chat-a", mark(word))
        val reopened = PointReadingMarks(directory)
        assertEquals(2, reopened.list("chat-a", sentence.block).size)
        assertEquals("again", reopened.list("chat-a").last().anchor.quote)
        val alternate = voice.copy(voice = "nova")
        reopened.put("chat-a", mark(sentence, alternate))
        val values = PointReadingMarks(directory).list("chat-a")
        assertEquals(2, values.size)
        assertEquals(voice, values.first { it.anchor == word }.voice)
        assertEquals(alternate, values.first { it.anchor == sentence }.voice)
    }

    @Test fun repeatedWordsWithIdenticalContextRemainIndependentPositions() = withDirectory { directory ->
        val store = PointReadingMarks(directory)
        val text = "a".repeat(180)
        store.put("chat", mark(anchor(text, 40, 41)))
        store.put("chat", mark(anchor(text, 100, 101)))
        assertEquals(2, PointReadingMarks(directory).list("chat").size)
        assertTrue(PointReadingMarks(directory).list("other-chat").isEmpty())
    }

    @Test fun sharedAudioHasIndependentPositionsAndClearingAudioPreservesUnavailableMarkers() = withDirectory { directory ->
        val cache = PointReadingCache(directory)
        val selected = anchor("Hello")
        val entry = mark(selected)
        val ticketA = cache.ticket("a")
        cache.put(entry.key, "a", ticketA, byteArrayOf(1, 2), emptySet())
        assertTrue(cache.remember("a", ticketA, entry))
        val ticketB = cache.ticket("b")
        assertNotNull(cache.get(entry.key, "b", ticketB))
        assertTrue(cache.remember("b", ticketB, entry))
        cache.removeOwner("a")
        assertTrue(cache.contains(entry.key))
        assertTrue(cache.marks.list("a").isEmpty())
        assertEquals(listOf(entry), cache.marks.list("b"))
        assertFalse(cache.remember("a", ticketA, entry))
        cache.clear()
        assertFalse(cache.contains(entry.key))
        assertEquals(listOf(entry), PointReadingMarks(java.io.File(directory, "marks")).list("b"))
        assertFalse(cache.remember("b", ticketB, entry))
    }

    @Test fun removingOneMarkerDoesNotRemoveOtherPositionsOrTheSharedAudio() = withDirectory { directory ->
        val cache = PointReadingCache(directory)
        val first = mark(anchor("Hello"))
        val second = mark(anchor("Hello", block = ReadingAnchor.message(1, "assistant")))
        val ticket = cache.ticket("chat")
        cache.put(first.key, "chat", ticket, byteArrayOf(1), emptySet())
        cache.remember("chat", ticket, first); cache.remember("chat", ticket, second)
        cache.removeMark("chat", first)
        assertEquals(listOf(second), cache.marks.list("chat"))
        assertTrue(cache.contains(first.key))
        assertThrows(IllegalArgumentException::class.java) { cache.marks.put("chat", first.copy(key = second.voice.key("Other"))) }
    }

    @Test fun replacingLastVoiceKeepsOldAudioIdleUntilTheUserCleansIt() = withDirectory { directory ->
        val cache = PointReadingCache(directory)
        val old = mark(anchor("Hello"))
        val fresh = mark(old.anchor, voice.copy(voice = "nova"))
        val ticket = cache.ticket("chat")
        cache.put(old.key, "chat", ticket, byteArrayOf(1), emptySet())
        cache.remember("chat", ticket, old)
        cache.put(fresh.key, "chat", ticket, byteArrayOf(2), emptySet())
        cache.remember("chat", ticket, fresh)
        assertTrue(cache.contains(old.key))
        assertEquals(listOf(old.key), cache.idleFiles().map { it.nameWithoutExtension })
        cache.clearIdle()
        assertFalse(cache.contains(old.key)); assertTrue(cache.contains(fresh.key))
        assertEquals(listOf(fresh), cache.marks.list("chat"))
    }

    private fun withDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("reading-marks-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
