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
        val batch = SubtitleTranslation.nextBatch(all, saved, batched = true)
        assertEquals((9..24).toList(), batch.map { it.id })
        val request = SubtitleTranslation.request(all, batch, batched = true)
        assertEquals(listOf("Sentence 7", "Sentence 8", "Sentence 25", "Sentence 26"), request["context"])
        assertEquals(16, (request["entries"] as List<*>).size)
        assertTrue(SubtitleTranslation.nextBatch(cues, mapOf(1 to "你好", 2 to "再见")).isEmpty())
    }

    @Test fun wholeTranslationSeesEveryEntryAndResumesWithoutRetranslatingSavedIds() {
        val all = (1..60).map { SubtitleCue(it, it * 1000L, it * 1000L + 500, "Sentence $it " + "x".repeat(100)) }
        assertEquals(all, SubtitleTranslation.nextBatch(all, emptyMap()))
        val whole = SubtitleTranslation.request(all, all)
        assertEquals(60, (whole["entries"] as List<*>).size)
        assertEquals(emptyList<String>(), whole["context"])
        val saved = mapOf(1 to "已有译文", 30 to "已有译文")
        val pending = SubtitleTranslation.nextBatch(all, saved)
        assertEquals(all.filter { it.id !in saved }, pending)
        assertEquals(listOf(all[0].text, all[29].text), SubtitleTranslation.request(all, pending)["context"])
    }

    @Test fun wholeTranslationCanReceiveLongAnswersWithoutRelaxingNormalChatLimits() {
        val text = "中".repeat(300_000)
        val config = AppConfig()
        val event = mapOf<String, Any?>("delta" to text)
        assertThrows(ProviderException::class.java) { ResponsesAdapter.decoder(config).accept("response.output_text.delta", event, {}) }
        val responses = ResponsesAdapter.decoder(config, SubtitleTranslation.MAX_TEXT_CHARS)
        responses.accept("response.output_text.delta", event, {})
        responses.accept("response.completed", mapOf("response" to mapOf("status" to "completed", "output" to listOf(
            mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to text)))))), {})
        assertEquals(text, responses.finish({}))
        val chatEvent = mapOf<String, Any?>("choices" to listOf(mapOf("index" to 0,
            "delta" to mapOf("content" to text), "finish_reason" to "stop")))
        assertThrows(ProviderException::class.java) { ChatCompletionsAdapter.decoder(config).accept("message", chatEvent, {}) }
        val chat = ChatCompletionsAdapter.decoder(config, SubtitleTranslation.MAX_TEXT_CHARS)
        chat.accept("message", chatEvent, {})
        assertEquals(text, chat.finish({}))
    }
}
