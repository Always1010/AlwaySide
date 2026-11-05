package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class HistoryArchiveTest {
    private fun conversation(id: String, updatedAt: Long, text: String = "正文") = StoredConversation(
        id, updatedAt, "保留标题", listOf(ChatMessage("user", "提问"), ChatMessage("assistant", text)), "资料",
    )

    // Representative already-encoded JSON; production passes bytes from the existing WireJson encoder.
    private fun encodedFixture(value: StoredConversation): ByteArray {
        val escaped = value.messages.last().text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"id\":\"${value.id}\",\"text\":\"$escaped\"}".toByteArray(Charsets.UTF_8)
    }

    @Test fun fullBodiesAndVersionOneOptionalFieldsSurviveCodecRoundtrip() {
        val answer = "中文\\\"\n😀".repeat(40000)
        val value = conversation("complete", 3, answer).copy(
            transcript = "完整转写".repeat(50000), sourceText = "选文\n".repeat(4000),
            messages = listOf(ChatMessage("user", "提问")) + List(6) {
                ChatMessage("assistant", answer, "原始资料", "<div>搜索建议\\</div>")
            },
        )
        val packed = HistoryArchive.save(emptyList(), value, ::encodedFixture)
        assertEquals(value, packed.values.single())
        assertEquals(listOf(value), HistoryArchive.decode(mapOf("version" to 1,
            "conversations" to packed.values.map(HistoryArchive::record))))
        val json = packed.bytes.toString(Charsets.UTF_8)
        assertTrue(json.contains("中文\\\\\\\"\\n😀"))

        val legacy = HistoryArchive.decode(mapOf("version" to 1, "conversations" to listOf(mapOf(
            "id" to "legacy", "updatedAt" to 1L, "title" to "旧标题",
            "messages" to listOf(mapOf("role" to "assistant", "text" to answer)),
        )))).single()
        assertEquals(answer, legacy.messages.single().text)
        assertEquals(ChatMessage("assistant", answer), legacy.messages.single())
        assertEquals("", legacy.transcript)
        assertEquals("", legacy.sourceText)
        assertEquals("text", legacy.kind)
        assertEquals("已完成", legacy.status)
    }

    @Test fun exactEscapedUtf8BudgetEvictsWholeOldRecordsAndProtectsIncomingRecord() {
        val incoming = conversation("new", 0, "中文\\\"\n".repeat(12000))
        val old = conversation("old", 10, "旧正文")
        val pairSize = HistoryArchive.encode(listOf(old, incoming), ::encodedFixture).size
        assertEquals(listOf(old, incoming), HistoryArchive.save(listOf(old), incoming, ::encodedFixture, maxBytes = pairSize).values)
        val packed = HistoryArchive.save(listOf(old), incoming, ::encodedFixture, maxBytes = pairSize - 1)
        assertEquals(listOf(incoming), packed.values)
        assertArrayEquals(HistoryArchive.encode(listOf(incoming), ::encodedFixture), packed.bytes)
        val singleSize = packed.bytes.size
        assertEquals(singleSize, HistoryArchive.save(emptyList(), incoming, ::encodedFixture, maxBytes = singleSize).bytes.size)
        assertThrows(IllegalStateException::class.java) {
            HistoryArchive.save(listOf(old), incoming, ::encodedFixture, maxBytes = singleSize - 1)
        }

        val newer = conversation("newer", 20)
        val middle = conversation("middle", 15)
        val keepTwoBytes = HistoryArchive.encode(listOf(newer, incoming), ::encodedFixture).size
        assertEquals(listOf(newer, incoming),
            HistoryArchive.save(listOf(middle, newer), incoming, ::encodedFixture, maxBytes = keepTwoBytes).values)
        assertEquals(listOf(incoming), HistoryArchive.save(listOf(old), incoming, ::encodedFixture, maxConversations = 1).values)
        val oversizedOld = old.copy(messages = listOf(ChatMessage("assistant", "旧".repeat(singleSize))))
        assertEquals(listOf(incoming), HistoryArchive.save(listOf(oversizedOld), incoming,
            ::encodedFixture, maxBytes = singleSize).values)
    }

    @Test fun archiveRetainsAtMostFiftyCompleteConversationsIncludingAnOlderIncomingRecord() {
        val previous = (1L..50L).map { conversation("old-$it", it, "完整正文 $it") }
        val incoming = conversation("incoming", 0, "新记录虽时间较早仍必须保留")
        val packed = HistoryArchive.save(previous, incoming, ::encodedFixture)
        assertEquals(50, packed.values.size)
        assertTrue(incoming in packed.values)
        assertFalse(packed.values.any { it.id == "old-1" })
        assertEquals(previous.filterNot { it.id == "old-1" }.sortedByDescending { it.updatedAt } + incoming, packed.values)
    }

    @Test fun invalidStructureIsRejectedInsteadOfSilentlyDroppingMessages() {
        val valid = conversation("valid", 1)
        val maximum = valid.copy(messages = List(100) { ChatMessage("assistant", "短消息") })
        assertEquals(listOf(maximum), HistoryArchive.decode(mapOf("version" to 1,
            "conversations" to listOf(HistoryArchive.record(maximum)))))
        val oversized = valid.copy(messages = List(101) { ChatMessage("assistant", "短消息") })
        val invalid = listOf(oversized, valid.copy(id = ""), valid.copy(id = "x".repeat(129)),
            valid.copy(messages = listOf(ChatMessage("tool", "未支持角色"))), valid.copy(kind = "unknown"))
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { HistoryArchive.save(emptyList(), value, ::encodedFixture) }
            assertThrows(IllegalArgumentException::class.java) { HistoryArchive.encode(listOf(value), ::encodedFixture) }
        }
        val base = HistoryArchive.record(valid)
        val invalidRecords = listOf(
            base + ("messages" to List(101) { mapOf("role" to "assistant", "text" to "短消息") }),
            base + ("id" to ""), base + ("messages" to listOf(mapOf("role" to "tool", "text" to "正文"))),
            base + ("kind" to "unknown"),
        )
        invalidRecords.forEach { record ->
            assertThrows(RuntimeException::class.java) {
                HistoryArchive.decode(mapOf("version" to 1, "conversations" to listOf(record)))
            }
        }
    }
}
