package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class HistoryCollectionTest {
    @Test fun activeConversationsMayExceedTheLimitAndConvergeWhenReleased() {
        val values = listOf(value("old-active", 1), value("new-active", 2), value("idle", 3))
        val state = HistoryState(values, historyLimit = 1)
        val protected = HistoryCollection.retain(state, protectedIds = setOf("old-active", "new-active"))
        assertEquals(setOf("old-active", "new-active"), protected.conversations.map { it.id }.toSet())
        assertEquals(listOf("new-active"), HistoryCollection.retain(protected).conversations.map { it.id })
        val incoming = HistoryCollection.save(state, value("incoming", 4), 4, setOf("old-active"))
        assertEquals(setOf("old-active", "incoming"), incoming.conversations.map { it.id }.toSet())
    }
    private fun value(id: String, time: Long) = StoredConversation(id, time, "标题",
        listOf(ChatMessage("assistant", "完整正文 $id")), "资料", historyEnteredAt = time)

    @Test fun defaultKeepsFiveHundredOrdinaryRecordsAndAllFavorites() {
        val favorite = value("favorite", 1).copy(favoriteAt = 2)
        val old = (1L..500L).map { value("old-$it", it) }
        val state = HistoryState(old + favorite)
        val next = HistoryCollection.save(state, value("incoming", 0), 0)
        assertEquals(500, next.conversations.count { !it.isFavorite })
        assertEquals(favorite, next.conversations.find { it.id == favorite.id })
        assertTrue(next.conversations.any { it.id == "incoming" })
        assertFalse(next.conversations.any { it.id == "old-1" })
        assertEquals(501, next.conversations.find { it.id == "incoming" }!!.historyEnteredAt)
    }

    @Test fun unfavoriteJoinsNewestQueueKeepsActualTimestampAndTagsAndSurvivesStaleSave() {
        val snapshot = value("saved", 1)
        var state = HistoryState(listOf(snapshot, value("recent", 100)), listOf("甲", "乙"), 1)
        state = HistoryCollection.favorite(state, "saved", true, 200)
        state = HistoryCollection.setTags(state, "saved", listOf("甲", "乙"))
        state = HistoryCollection.favorite(state, "saved", false, 300)
        val unfavorited = state.conversations.single()
        assertEquals(1, unfavorited.updatedAt)
        assertEquals(300, unfavorited.historyEnteredAt)
        assertEquals(listOf("甲", "乙"), unfavorited.tags)
        assertFalse(unfavorited.isFavorite)
        state = HistoryCollection.save(state, snapshot, 400)
        assertEquals(unfavorited, state.conversations.single())
        state = HistoryCollection.favorite(state, "saved", true, 500)
        state = HistoryCollection.renameTag(state, "甲", "新甲")
        state = HistoryCollection.deleteTag(state, "乙")
        state = HistoryCollection.save(state, snapshot.copy(updatedAt = 2), 600)
        assertTrue(state.conversations.single().isFavorite)
        assertEquals(listOf("新甲"), state.conversations.single().tags)
        assertEquals(2, state.conversations.single().updatedAt)
        assertEquals(600, state.conversations.single().historyEnteredAt)
    }

    @Test fun newContentAdvancesRetentionWhileMetadataAndStaleContentDoNotChangeActualTime() {
        val old = value("a", 10)
        var state = HistoryState(listOf(old, value("b", 20)), listOf("标签"), 2)
        state = HistoryCollection.setTags(state, "a", listOf("标签", "标签"))
        assertEquals(10, state.conversations.find { it.id == "a" }!!.updatedAt)
        assertEquals(10, state.conversations.find { it.id == "a" }!!.historyEnteredAt)
        state = HistoryCollection.save(state, old.copy(updatedAt = 11), 30)
        assertEquals(state, HistoryCollection.save(state, old, 40))
        state = HistoryCollection.save(state, value("c", 1), 50)
        assertEquals(setOf("a", "c"), state.conversations.map { it.id }.toSet())
        assertEquals(listOf("标签"), state.conversations.find { it.id == "a" }!!.tags)
    }

    @Test fun tagRenameAndDeletionAreGlobalAndNeverDeleteConversations() {
        val first = value("first", 1).copy(tags = listOf("甲", "乙"), favoriteAt = 5)
        val second = value("second", 2).copy(tags = listOf("甲"))
        var state = HistoryState(listOf(first, second), listOf("甲", "乙"))
        state = HistoryCollection.renameTag(state, "甲", "新甲")
        assertEquals(listOf("新甲", "乙"), state.tags)
        assertEquals(listOf("新甲", "乙"), state.conversations.first().tags)
        state = HistoryCollection.deleteTag(state, "新甲")
        assertEquals(listOf(first.copy(tags = listOf("乙")), second.copy(tags = emptyList())), state.conversations)
        assertThrows(IllegalArgumentException::class.java) { HistoryCollection.createTag(state, "乙") }
        assertThrows(IllegalArgumentException::class.java) { HistoryCollection.createTag(state, " ") }
        assertThrows(IllegalArgumentException::class.java) { HistoryCollection.setTags(state, "first", listOf("不存在")) }
    }

    @Test fun decreasingLimitEvictsOnlyOldestOrdinaryAndAcceptsAnyPositiveInt() {
        val favorite = value("favorite", 1).copy(favoriteAt = 10)
        val state = HistoryState(listOf(favorite, value("old", 2), value("new", 3)))
        assertEquals(listOf(value("new", 3), favorite), HistoryCollection.retain(state.copy(historyLimit = 1)).conversations)
        assertEquals(3, HistoryCollection.retain(state.copy(historyLimit = Int.MAX_VALUE)).conversations.size)
        assertThrows(IllegalArgumentException::class.java) { HistoryCollection.retain(state.copy(historyLimit = 0)) }
    }

    @Test fun jointTagEditCollectsMissingFavoritesAndPreservesUnchangedMetadata() {
        val first = value("first", 1).copy(tags = listOf("甲"), favoriteAt = 5)
        val second = value("second", 2).copy(tags = listOf("乙"))
        val untouched = value("other", 3)
        val state = HistoryState(listOf(first, second, untouched), listOf("甲", "乙", "丙"))
        val next = HistoryCollection.editTags(state, listOf("first", "second"), mapOf("丙" to true), true, 100)
        assertEquals(first.copy(tags = listOf("甲", "丙")), next.conversations[0])
        assertEquals(second.copy(tags = listOf("乙", "丙"), favoriteAt = 100), next.conversations[1])
        assertEquals(untouched, next.conversations[2])
        val removed = HistoryCollection.editTags(next, listOf("first", "second"), mapOf("甲" to false), false, 200)
        assertEquals(listOf("丙"), removed.conversations[0].tags)
        assertEquals(next.conversations[1], removed.conversations[1])
        assertThrows(IllegalStateException::class.java) {
            HistoryCollection.editTags(state, listOf("first", "second"), emptyMap(), false, 100)
        }
        assertThrows(IllegalStateException::class.java) {
            HistoryCollection.editTags(state, listOf("first", "gone"), emptyMap(), true, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryCollection.editTags(state, listOf("second"), mapOf("gone" to true), true, 100)
        }
        assertFalse(state.conversations[1].isFavorite)
    }
}
