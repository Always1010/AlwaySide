package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackQueueTest {
    private fun queue(count: Int = 3) = AudioPlaybackQueue((0 until count).map { AudioQueueItem("$it", "录音 $it") }, "收藏")

    @Test fun naturalCompletionAndManualNavigationRespectRepeatAndBoundaries() {
        val queue = queue()
        assertEquals(0, queue.target(AudioRepeat.ONE, automatic = true))
        assertEquals(1, queue.target(AudioRepeat.ONE))
        assertNull(queue.target(AudioRepeat.NONE, delta = -1))
        queue.select(2)
        assertNull(queue.target(AudioRepeat.NONE, automatic = true))
        assertEquals(0, queue.target(AudioRepeat.ALL, automatic = true))
        assertEquals(2, queue.target(AudioRepeat.ONE, automatic = true))
        assertEquals(1, queue.target(AudioRepeat.ONE, delta = -1))
        assertNull(queue(0).target(AudioRepeat.ALL))
        assertEquals(0, queue(1).target(AudioRepeat.ALL, automatic = true))
    }

    @Test fun missingOrCorruptItemsCannotCreateAnInfiniteListRepeat() {
        val queue = queue()
        queue.rejectCurrent(); queue.select(1); queue.rejectCurrent()
        assertEquals(listOf(2), queue.candidates(0, wrap = true))
        queue.select(2); queue.rejectCurrent()
        assertNull(queue.target(AudioRepeat.ALL, automatic = true))
        assertTrue(queue.candidates(0, wrap = true).isEmpty())
        assertTrue(queue.candidates(2, delta = -1).isEmpty())
    }

    @Test fun snapshotIncludesAllPagesInDisplayOrderAndIgnoresNonRecordingsAndMissingFiles() {
        val values = (0 until 65).map { index ->
            StoredConversation("$index", 1, "录音 $index", emptyList(), "", kind = if (index == 2) "text" else "audio",
                favoriteAt = (index + 1).toLong(), tags = listOf("英语"))
        }
        val browse = HistoryBrowse(favorites = true, tag = "英语")
        val snapshot = AudioPlaybackQueue.recordings(browse.visible(values)) { it != "5" }
        assertEquals(63, snapshot.size)
        assertEquals("64", snapshot.first().id)
        assertEquals("0", snapshot.last().id)
        assertFalse(snapshot.any { it.id in setOf("2", "5") })
        val queue = AudioPlaybackQueue(snapshot + snapshot.first(), "英语")
        browse.showFavorites("其他")
        assertEquals(snapshot, queue.items)
    }
}
