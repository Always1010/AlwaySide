package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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

    @Test fun searchRespectsEveryListScopeAndBackRestoresTheParentCollection() {
        val value = record("title", 1, listOf("开发"), "Kotlin cancellation")
        val ordinary = record("ordinary", text = "Kotlin cancellation")
        val browse = HistoryBrowse().apply { query = "CANCELLATION" }
        assertEquals(listOf(ordinary, value), browse.visible(listOf(ordinary, value)))
        browse.showFavorites(); browse.query = "CANCELLATION"
        assertEquals(listOf(value), browse.visible(listOf(ordinary, value)))
        browse.showFavorites(untagged = true); browse.query = "CANCELLATION"
        assertTrue(browse.visible(listOf(ordinary, value)).isEmpty())
        browse.showFavorites("开发"); browse.query = "CANCELLATION"
        assertEquals(listOf(value), browse.visible(listOf(ordinary, value)))
        assertTrue(browse.back()); assertEquals("开发", browse.tag); assertEquals("", browse.query)
        browse.query = "开发"
        assertEquals(listOf(value), browse.visible(listOf(value)))
        browse.back(); browse.back()
        assertTrue(browse.favorites); assertNull(browse.tag)
        assertTrue(browse.back()); assertFalse(browse.canGoBack)
        assertFalse(browse.back())
    }

    @Test fun dateFilterIncludesWholeLocalDaysAcrossTimeZonesAndDaylightSavingChanges() {
        listOf("Asia/Shanghai", "America/New_York").forEach { zoneName ->
            val zone = ZoneId.of(zoneName)
            listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)).forEach { date ->
                val range = HistoryDateRange(date, date)
                val first = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                assertFalse(range.contains(first - 1, zone))
                assertTrue(range.contains(first, zone))
                assertTrue(range.contains(next - 1, zone))
                assertFalse(range.contains(next, zone))
            }
        }
    }

    @Test fun dateFilterCombinesWithSearchAndEveryCollectionUsingTheDisplayedUpdateTime() {
        val date = LocalDate.of(2026, 10, 20)
        val range = HistoryDateRange(date, date.plusDays(2))
        val inside = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val outside = date.plusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ordinary = record("ordinary", text = "needle").copy(updatedAt = inside)
        val shared = record("shared", 3, listOf("工作"), "needle").copy(updatedAt = inside)
        val untagged = record("untagged", 2, text = "needle").copy(updatedAt = inside)
        val old = record("old", 5, listOf("工作"), "needle").copy(updatedAt = outside)
        val unrelated = record("unrelated", 4, listOf("工作"), "other").copy(updatedAt = inside)
        val all = listOf(ordinary, shared, untagged, old, unrelated)
        val browse = HistoryBrowse(query = "needle", dateRange = range)
        assertEquals(listOf(ordinary, shared, untagged), browse.visible(all))
        browse.showFavorites(); browse.query = "needle"; browse.dateRange = range
        assertEquals(listOf(shared, untagged), browse.visible(all))
        browse.showFavorites("工作"); browse.query = "needle"; browse.dateRange = range
        assertEquals(listOf(shared), browse.visible(all))
        browse.showFavorites(untagged = true); browse.query = "needle"; browse.dateRange = range
        assertEquals(listOf(untagged), browse.visible(all))
        browse.dateRange = null
        assertEquals("needle", browse.query)
    }

    @Test fun backAndScopeChangesClearDateFiltersWithoutLeavingTheCollectionPrematurely() {
        val date = LocalDate.of(2026, 10, 20)
        val range = HistoryDateRange(date, date.plusDays(4))
        val browse = HistoryBrowse(favorites = true, tag = "工作", query = "needle", dateRange = range)
        assertTrue(browse.back()); assertEquals("", browse.query); assertEquals(range, browse.dateRange)
        assertTrue(browse.back()); assertNull(browse.dateRange); assertEquals("工作", browse.tag)
        assertTrue(browse.back()); assertTrue(browse.favorites); assertNull(browse.tag)
        browse.dateRange = range
        browse.showAll(); assertNull(browse.dateRange); assertFalse(browse.canGoBack)
        browse.dateRange = range
        assertTrue(browse.canGoBack)
        assertTrue(browse.back()); assertFalse(browse.canGoBack)
        browse.dateRange = range
        browse.showFavorites("工作"); assertNull(browse.dateRange)
    }

    @Test fun appliedDateRangesRestoreExactlyAndInvalidSavedRangesAreIgnored() {
        val range = HistoryDateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 6))
        assertEquals(range, HistoryDateRange.restore(range.start.toString(), range.end.toString()))
        assertNull(HistoryDateRange.restore(null, null))
        assertNull(HistoryDateRange.restore("invalid", range.end.toString()))
        assertNull(HistoryDateRange.restore(range.start.toString(), null))
        assertNull(HistoryDateRange.restore(range.end.toString(), range.start.toString()))
        assertThrows(IllegalArgumentException::class.java) { HistoryDateRange(range.end, range.start) }
    }
    @Test fun backgroundSearchReadsOnlyScopedBodiesAndKeepsOnlySummariesIncludingDeepTextMatches() {
        val deep = record("deep", 3, listOf("工作"), "文".repeat(500) + "needle")
        val title = record("needle title", 2, listOf("工作"))
        val outside = record("outside", text = "needle")
        val values = listOf(deep, title, outside)
        val summaries = values.map(HistorySummary::of)
        val reads = mutableListOf<String>()
        val browse = HistoryBrowse(favorites = true, tag = "工作", query = "NEEDLE")
        val found = HistorySearch.run(browse, summaries, { id ->
            reads.add(id); values.find { it.id == id }
        })
        assertEquals(listOf("deep"), reads)
        assertEquals(listOf("deep", "needle title"), found.map { it.id })
        assertTrue(found.all { it.messages.isEmpty() && it.transcript.isEmpty() && it.sourceText.isEmpty() })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            HistorySearch.run(browse, summaries, { error("An obsolete query must not read a body") }, { true })
        }
    }

}
