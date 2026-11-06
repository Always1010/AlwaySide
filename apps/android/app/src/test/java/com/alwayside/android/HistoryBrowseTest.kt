package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class HistoryBrowseTest {
    private fun record(id: String, favoriteAt: Long = 0, tags: List<String> = emptyList(), text: String = "正文") =
        StoredConversation(id, 1, id, listOf(ChatMessage("assistant", text)), "", favoriteAt = favoriteAt, tags = tags)

    @Test fun collectionViewsShareOriginalRecordsAcrossMultipleTagsAndKeepUntaggedFavorites() {
        val shared = record("shared", 2, listOf("开发", "学习"))
        val untagged = record("untagged", 3)
        val ordinary = record("ordinary", tags = listOf("开发"))
        val all = listOf(ordinary, shared, untagged)
        val browse = HistoryBrowse()
        assertEquals(all, browse.visible(all))
        browse.showFavorites()
        assertEquals(listOf(untagged, shared), browse.visible(all))
        listOf("开发", "学习").forEach { tag ->
            browse.showFavorites(tag)
            assertEquals(listOf(shared), browse.visible(all))
        }
        browse.showFavorites(untagged = true)
        assertEquals(listOf(untagged), browse.visible(all))
    }

    @Test fun searchMatchesFavoriteContentAndTagsAndBackRestoresTheParentCollection() {
        val value = record("title", 1, listOf("开发"), "Kotlin cancellation")
        val ordinary = record("ordinary", text = "Kotlin cancellation")
        val browse = HistoryBrowse().apply { showFavorites("开发"); query = "CANCELLATION" }
        assertEquals(listOf(value), browse.visible(listOf(ordinary, value)))
        assertTrue(browse.back()); assertEquals("开发", browse.tag); assertEquals("", browse.query)
        browse.query = "开发"
        assertEquals(listOf(value), browse.visible(listOf(value)))
        browse.back(); browse.back()
        assertTrue(browse.favorites); assertNull(browse.tag)
        assertTrue(browse.back()); assertFalse(browse.canGoBack)
        assertFalse(browse.back())
    }
}
