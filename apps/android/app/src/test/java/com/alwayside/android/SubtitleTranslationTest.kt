package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleTranslationTest {
    private val cues = listOf(SubtitleCue(1, 1000, 2000, "Hello."), SubtitleCue(2, 3000, 4000, "Goodbye."))

    @Test fun translationIdsMustMatchSourceWithoutDuplicatesOrMissingEntries() {
        fun payload(vararg rows: Pair<Number, String>) = mapOf("translations" to rows.map {
            mapOf("id" to it.first, "text" to it.second)
        })
        assertEquals(mapOf(2 to "再见。", 1 to "你好。"),
            SubtitleTranslation.parse(payload(2 to "再见。", 1 to "你好。"), cues))
        listOf(payload(1 to "你好。"), payload(1 to "你好。", 1 to "重复"),
            payload(1 to "你好。", 3 to "多余"), payload(1 to "你好。", 2 to " "),
            payload(1.5 to "错号", 2 to "再见。"), payload(1 to "你好。", 2 to "中".repeat(4001))).forEach { root ->
            assertThrows(IllegalArgumentException::class.java) { SubtitleTranslation.parse(root, cues) }
        }
    }

    @Test fun retriesOnlyTranslateMissingIdsAndContextDoesNotBecomeExtraEntries() {
        val all = (1..40).map { SubtitleCue(it, it * 1000L, it * 1000L + 500, "Sentence $it") }
        val saved = (1..8).associateWith { "已翻译 $it" }
        val batch = SubtitleTranslation.nextBatch(all, saved)
        assertEquals((9..24).toList(), batch.map { it.id })
        val request = SubtitleTranslation.request(all, batch)
        assertEquals(listOf("Sentence 7", "Sentence 8", "Sentence 25", "Sentence 26"), request["context"])
        assertEquals(16, (request["entries"] as List<*>).size)
        assertTrue(SubtitleTranslation.nextBatch(cues, mapOf(1 to "你好", 2 to "再见")).isEmpty())
    }
}
