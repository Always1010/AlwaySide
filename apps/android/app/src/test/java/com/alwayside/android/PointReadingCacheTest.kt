package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PointReadingCacheTest {
    @Test fun realtimeWavAndLegacyMp3ShareQuotaAndSurviveReopeningWithoutChangingLegacyKeys() = withCache { root ->
        val cache=PointReadingCache(root,8)
        val legacy=ReadingVoice("service","tts-1","alloy")
        assertEquals(readingDigest(listOf("speech-v1","service","tts-1","alloy","mp3","Hi").joinToString("\u0000")),legacy.key("Hi"))
        val current=ReadingVoice("service",ReadingPresets.DEFAULT_MODEL,ReadingPresets.DEFAULT_VOICE)
        assertEquals("wav",current.format)
        cache.put(legacy.key("Hi"),"old",cache.ticket("old"),byteArrayOf(1,2),emptySet())
        cache.put(current.key("Hi"),"new",cache.ticket("new"),byteArrayOf(3,4),emptySet(),format=current.format)
        val reopened=PointReadingCache(root,8)
        assertEquals("mp3",reopened.get(legacy.key("Hi"),"old",reopened.ticket("old"))!!.extension)
        assertEquals("wav",reopened.get(current.key("Hi"),"new",reopened.ticket("new"))!!.extension)
        assertEquals(4,reopened.bytes().toInt())
        reopened.clear();assertEquals(0,reopened.bytes().toInt())
    }
    @Test fun cachedAudioSurvivesReopeningAndSharedOwnersAreIndependent() = withCache { root ->
        val cache = PointReadingCache(root)
        val key = ReadingVoice("service", "model", "voice").key("Hello.")
        val bytes = byteArrayOf(1, 2, 3)
        assertNotNull(cache.put(key, "a", cache.ticket("a"), bytes, emptySet()))
        assertNotNull(cache.get(key, "b", cache.ticket("b")))
        cache.removeOwner("a")
        val reopened = PointReadingCache(root)
        assertArrayEquals(bytes, reopened.get(key, "b", reopened.ticket("b"))!!.readBytes())
        reopened.removeOwner("b")
        assertNull(reopened.get(key, "b", reopened.ticket("b")))
    }

    @Test fun deletionAndClearRejectLateWritesAndFavoritesSurviveEviction() = withCache { root ->
        val cache = PointReadingCache(root, 6)
        val voice = ReadingVoice("service", "model", "voice")
        val oldTicket = cache.ticket("deleted")
        cache.removeOwner("deleted")
        assertNull(cache.put(voice.key("late"), "deleted", oldTicket, byteArrayOf(1), emptySet()))
        cache.put(voice.key("favorite"), "fav", cache.ticket("fav"), byteArrayOf(1, 2, 3, 4), emptySet())
        assertNull(cache.put(voice.key("other"), "other", cache.ticket("other"), byteArrayOf(5, 6, 7), setOf("fav")))
        val beforeClear = cache.ticket("other")
        cache.clear(setOf("fav"))
        assertNull(cache.put(voice.key("late"), "other", beforeClear, byteArrayOf(1), emptySet()))
        assertNotNull(cache.get(voice.key("favorite"), "fav", cache.ticket("fav")))
        assertEquals(4, cache.bytes().toInt())
    }

    @Test fun cacheIdentityPreservesPronunciationAndIgnoresIncidentalWhitespace() {
        val voice = ReadingVoice("service", "model", "voice")
        assertEquals(voice.key(" Hello.\r\n"), voice.key("Hello."))
        assertNotEquals(voice.key("US"), voice.key("us"))
        assertNotEquals(voice.key("Hello."), voice.copy(voice = "other").key("Hello."))
        assertNotEquals(voice.key("Hello."), voice.copy(connection = "other").key("Hello."))
        assertFalse(ReadingParagraphs.split("Hello.\n你好。\nhttps://example.com").last().english)
        assertEquals(listOf(true, false), ReadingParagraphs.split("I'm down.\n我愿意。").map { it.english })
        val labeled=ReadingParagraphs.split("英文原文：I'm down.\n中文翻译：我愿意。")
        assertTrue(labeled.first().english)
        assertEquals("英文原文：I'm down.",labeled.first().text)
        assertEquals("I'm down.",labeled.first().speech)
        assertFalse(labeled.last().english)
    }
    @Test fun inactiveStoreCreatesNothingAndEvictionKeepsTheActiveFile() = withCache { root ->
        val directory=java.io.File(root,"not-created")
        val cache=PointReadingCache(directory,4)
        assertFalse(directory.exists())
        val voice=ReadingVoice("service","model","voice")
        val current=cache.put(voice.key("active"),"a",cache.ticket("a"),byteArrayOf(1,2,3),emptySet())
        assertNull(cache.put(voice.key("other"),"b",cache.ticket("b"),byteArrayOf(1,2),emptySet(),current))
        assertTrue(current!!.exists())
    }
    private fun withCache(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("point-reading-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
