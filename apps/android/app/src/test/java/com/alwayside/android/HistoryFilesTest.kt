package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** The Android JSON/AtomicFile boundary is injected; failures model an interrupted atomic write. */
class HistoryFilesTest {
    private class Disk : HistoryFileAccess {
        val values = linkedMapOf<String, ByteArray>()
        var reads = 0
        var rejectWrite: (String) -> Boolean = { false }
        var rejectDelete: (String) -> Boolean = { false }
        override fun read(name: String, maxBytes: Int?): ByteArray? {
            reads++
            return values[name]?.also { check(maxBytes == null || it.size <= maxBytes) { "Too large" } }
        }
        override fun write(name: String, bytes: ByteArray) {
            if (rejectWrite(name)) throw IOException("disk full")
            values[name] = bytes
        }
        override fun delete(name: String) {
            if (rejectDelete(name)) throw IOException("delete failed")
            values.remove(name)
        }
        override fun records() = values.keys.filter { HistoryFiles.RECORD_NAME.matches(it) }
    }

    private fun encode(value: Map<String, Any?>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        bytes.toByteArray()
    }
    @Suppress("UNCHECKED_CAST")
    private fun decode(bytes: ByteArray): Map<String, Any?> =
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Map<String, Any?> }
    private fun repository(disk: Disk) = HistoryFiles(disk, ::encode, ::decode)
    private fun value(id: String, time: Long) = StoredConversation(id, time, "旧标题",
        listOf(ChatMessage("assistant", "完整中文正文\\\"\n😀 $id", "资料", "建议")), "资料",
        transcript = "完整转写", sourceText = "完整选文")
    private fun legacy(disk: Disk, values: List<StoredConversation>) {
        disk.values[HistoryFiles.LEGACY] = encode(mapOf("version" to 1,
            "conversations" to values.map { HistoryArchive.record(it) - "favoriteAt" - "historyEnteredAt" - "tags" }))
    }

    @Test fun migratesAllLegacyBodiesWithoutApplyingNewRetentionAndPreservesSettingsOnReload() {
        val disk = Disk()
        val legacy = (1L..501L).map { value("old-$it", it) }
        legacy(disk, legacy)
        val files = repository(disk)
        val state = files.load()
        assertEquals(501, state.conversations.size)
        assertEquals(legacy.map { it.copy(historyEnteredAt = it.updatedAt) }.sortedByDescending { it.updatedAt }, state.conversations)
        assertFalse(disk.values.containsKey(HistoryFiles.LEGACY))
        val tagged = HistoryCollection.setTags(HistoryCollection.createTag(state, "甲"), "old-1", listOf("甲"))
        val next = HistoryCollection.favorite(tagged.copy(historyLimit = 1000), "old-1", true, 600)
        files.commit(state, next)
        assertEquals(next, repository(disk).load())
    }

    @Test fun failedMigrationLeavesLegacyIntactAndRetryImportsEveryConversation() {
        val disk = Disk()
        legacy(disk, listOf(value("first", 1), value("second", 2)))
        val original = disk.values.getValue(HistoryFiles.LEGACY).clone()
        disk.rejectWrite = { it == HistoryFiles.MANIFEST }
        assertThrows(IOException::class.java) { repository(disk).load() }
        assertArrayEquals(original, disk.values[HistoryFiles.LEGACY])
        assertFalse(disk.values.containsKey(HistoryFiles.MANIFEST))
        disk.rejectWrite = { false }
        val state = repository(disk).load()
        assertEquals(setOf("first", "second"), state.conversations.map { it.id }.toSet())
        assertEquals(2, disk.records().size) // Orphans from the failed transaction are removed only after commit.
    }

    @Test fun failedRecordOrManifestWriteLeavesPreviousCollectionAndTagsConsistent() {
        val disk = Disk()
        val files = repository(disk)
        val empty = files.load()
        val initial = HistoryState(listOf(value("a", 1).copy(tags = listOf("甲")), value("b", 2)), listOf("甲"))
        files.commit(empty, initial)
        val next = HistoryCollection.renameTag(initial, "甲", "新甲")
        disk.rejectWrite = { it != HistoryFiles.MANIFEST }
        assertThrows(IOException::class.java) { files.commit(initial, next) }
        assertEquals(initial.copy(conversations = initial.conversations.sortedByDescending { it.updatedAt }), repository(disk).load())
        disk.rejectWrite = { it == HistoryFiles.MANIFEST }
        assertThrows(IOException::class.java) { files.commit(initial, next) }
        assertEquals(initial.copy(conversations = initial.conversations.sortedByDescending { it.updatedAt }), repository(disk).load())
        disk.rejectWrite = { false }
        files.commit(initial, next)
        assertEquals(next.copy(conversations = next.conversations.sortedByDescending { it.updatedAt }), repository(disk).load())
        assertEquals(2, disk.records().size)
    }

    @Test fun failedLegacyDeletionNeverReimportsAfterClearAndMissingCommittedRecordIsVisibleFailure() {
        val disk = Disk()
        legacy(disk, listOf(value("legacy", 1)))
        disk.rejectDelete = { it == HistoryFiles.LEGACY }
        assertThrows(IOException::class.java) { repository(disk).load() }
        val files = repository(disk)
        val state = files.load()
        assertEquals(1, state.conversations.size)
        files.commit(state, state.copy(conversations = emptyList()))
        assertTrue(repository(disk).load().conversations.isEmpty())
        assertTrue(disk.values.containsKey(HistoryFiles.LEGACY))
        files.commit(HistoryState(), HistoryState(listOf(value("new", 2))))
        val committedManifest = disk.values.getValue(HistoryFiles.MANIFEST).clone()
        disk.values.remove(disk.records().single())
        assertThrows(IllegalStateException::class.java) { repository(disk).load() }
        assertArrayEquals(committedManifest, disk.values[HistoryFiles.MANIFEST])
    }

    @Test fun recordsBeyondThirtyTwoMiBRemainCompleteWithoutEvictingOtherConversations() {
        val disk = Disk()
        var recordBytes = 17 * 1024 * 1024
        val files = HistoryFiles(disk, { map ->
            val bytes = encode(map)
            if (map.containsKey("messages")) bytes.copyOf(recordBytes) else bytes
        }, ::decode)
        val empty = files.load()
        val next = HistoryState(listOf(value("favorite", 1).copy(favoriteAt = 1), value("ordinary", 2)))
        files.commit(empty, next)
        assertTrue(disk.records().sumOf { disk.values.getValue(it).size.toLong() } > 32 * 1024 * 1024)
        assertEquals(next.conversations.sortedByDescending { it.updatedAt }, files.load().conversations)
        recordBytes = 32 * 1024 * 1024 + 1
        val longConversation = value("long", 3).copy(messages = List(302) { ChatMessage("assistant", "消息 $it") })
        files.commit(next, next.copy(conversations = next.conversations + longConversation))
        val reloaded = repository(disk).load()
        assertEquals(3, reloaded.conversations.size)
        assertEquals(longConversation, reloaded.conversations.first())
    }

    @Test fun repeatedReadsUseCommittedSnapshotAndFailedWritesCannotReplaceIt() {
        val disk = Disk()
        val files = repository(disk)
        val empty = files.load()
        val initial = HistoryState(listOf(value("a", 1)))
        files.commit(empty, initial)
        val readCount = disk.reads
        repeat(10) { assertEquals(initial, files.load()) }
        assertEquals(readCount, disk.reads)
        val next = initial.copy(historyLimit = 600)
        disk.rejectWrite = { it == HistoryFiles.MANIFEST }
        assertThrows(IOException::class.java) { files.commit(initial, next) }
        assertEquals(initial, files.load())
        disk.rejectWrite = { false }
        files.commit(initial, next)
        assertEquals(next, files.load())
        assertEquals(next, repository(disk).load())
        assertTrue(disk.reads > readCount)
    }

    @Test fun explicitClearRecoversMissingRecordKeepsReadableMetadataAndCleansMediaAfterCommit() {
        val disk = Disk()
        val files = repository(disk)
        val empty = files.load()
        val initial = HistoryState(listOf(value("a", 1)), listOf("工作", "翻译"), 750)
        files.commit(empty, initial)
        disk.values.remove(disk.records().single())
        legacy(disk, listOf(value("stale-legacy", 2)))
        val recovery = repository(disk)
        assertThrows(IllegalStateException::class.java) { recovery.load() }
        var mediaClears = 0
        disk.rejectWrite = { it == HistoryFiles.MANIFEST }
        assertThrows(IOException::class.java) { recovery.clear { mediaClears++ } }
        assertEquals(0, mediaClears)
        disk.rejectWrite = { false }
        recovery.clear {
            assertTrue(repository(disk).load().conversations.isEmpty())
            mediaClears++
        }
        val cleared = HistoryState(tags = listOf("工作", "翻译"), historyLimit = 750)
        assertEquals(cleared, repository(disk).load())
        assertEquals(cleared, recovery.load())
        assertFalse(disk.values.containsKey(HistoryFiles.LEGACY))
        assertEquals(1, mediaClears)
    }

    @Test fun explicitClearRecoversCorruptManifestAndLegacyWithoutResurrectionIfLegacyDeletionFails() {
        val disk = Disk()
        disk.values[HistoryFiles.MANIFEST] = byteArrayOf(1, 2, 3)
        disk.values[HistoryFiles.LEGACY] = byteArrayOf(4, 5, 6)
        disk.rejectDelete = { it == HistoryFiles.LEGACY }
        val files = repository(disk)
        var mediaCleared = false
        assertThrows(IOException::class.java) { files.clear { mediaCleared = true } }
        assertTrue(mediaCleared)
        assertEquals(HistoryState(), repository(disk).load())
        assertEquals(HistoryState(), files.load())
        disk.rejectDelete = { false }
        files.clear()
        assertFalse(disk.values.containsKey(HistoryFiles.LEGACY))
    }

    @Test fun postCommitOrphanDeletionFailureDoesNotFailSaveOrHideAuthoritativeState() {
        val disk = Disk()
        val files = repository(disk)
        val empty = files.load()
        val initial = HistoryState(listOf(value("a", 1)))
        files.commit(empty, initial)
        disk.rejectDelete = { true }
        files.commit(initial, empty)
        assertEquals(empty, files.load())
        assertEquals(empty, repository(disk).load())
        assertEquals(1, disk.records().size)
        disk.rejectDelete = { false }
        files.clear()
        assertTrue(disk.records().isEmpty())
    }
}
