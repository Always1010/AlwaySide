package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class HistoryArchiveTest {
    private fun conversation(id: String, updatedAt: Long, text: String = "正文") = StoredConversation(
        id, updatedAt, "保留标题", listOf(ChatMessage("user", "提问"), ChatMessage("assistant", text)), "资料",
    )

    @Test fun completeRecordsAndLegacyOptionalFieldsSurviveMigrationCodec() {
        val answer = "中文\\\"\n😀".repeat(40000)
        val value = conversation("complete", 3, answer).copy(
            transcript = "完整转写".repeat(50000), sourceText = "选文\n".repeat(4000),
            messages = listOf(ChatMessage("user", "提问")) + List(6) {
                ChatMessage("assistant", answer, "原始资料", "<div>搜索建议\\</div>")
            }, favoriteAt = 10, historyEnteredAt = 9, tags = listOf("工作", "翻译"),
        )
        assertEquals(value, HistoryArchive.decodeRecord(HistoryArchive.record(value)))
        val legacy = HistoryArchive.decode(mapOf("version" to 1, "conversations" to listOf(mapOf(
            "id" to "legacy", "updatedAt" to 1L, "title" to "旧标题",
            "messages" to listOf(mapOf("role" to "assistant", "text" to answer)),
        )))).single()
        assertEquals(ChatMessage("assistant", answer), legacy.messages.single())
        assertEquals("", legacy.transcript)
        assertEquals("", legacy.sourceText)
        assertEquals("text", legacy.kind)
        assertEquals("已完成", legacy.status)
        assertFalse(legacy.isFavorite)
        assertEquals(0, legacy.historyEnteredAt)
        assertTrue(legacy.tags.isEmpty())
        assertEquals("", legacy.customTitle)
    }

    @Test fun invalidStructureIsRejectedInsteadOfSilentlyDroppingMessages() {
        val valid = conversation("valid", 1)
        val maximum = valid.copy(messages = List(301) { ChatMessage("assistant", "短消息") })
        assertEquals(maximum, HistoryArchive.decodeRecord(HistoryArchive.record(maximum)))
        val invalid = listOf(
            valid.copy(id = ""), valid.copy(id = "x".repeat(129)),
            valid.copy(messages = listOf(ChatMessage("tool", "未支持角色"))), valid.copy(kind = "unknown"),
            valid.copy(tags = listOf("重复", "重复")), valid.copy(favoriteAt = -1),
            valid.copy(customTitle = " "), valid.copy(customTitle = "多行\n标题"),
            valid.copy(customTitle = "长".repeat(65)))
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { HistoryArchive.record(value) }
        }
        val base = HistoryArchive.record(valid)
        val invalidRecords = listOf(
            base + ("id" to ""), base + ("messages" to listOf(mapOf("role" to "tool", "text" to "正文"))),
            base + ("kind" to "unknown"), base + ("tags" to listOf(3)),
            base + ("customTitle" to 3),
        )
        invalidRecords.forEach { record ->
            assertThrows(RuntimeException::class.java) { HistoryArchive.decodeRecord(record) }
        }
        assertThrows(IllegalStateException::class.java) {
            HistoryArchive.decode(mapOf("version" to 1, "conversations" to listOf(base, base)))
        }
    }
}
