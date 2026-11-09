package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class StorageInventoryTest {
    @Test fun sharedFilesCountOnceAndAreOnlyReleasedAfterEveryOwnerIsSelected() {
        val shared = StorageEntry("shared", "reading", 100, 1, setOf("a", "b"))
        val inventory = StorageInventory(listOf(shared, shared, StorageEntry("own", "audio", 50, 1, setOf("a"))))
        assertEquals(150L, inventory.bytes)
        assertEquals(50L, inventory.releasable(setOf("a"), setOf("audio", "reading")))
        assertEquals(150L, inventory.releasable(setOf("a", "b"), setOf("audio", "reading")))
    }
    @Test fun activeUnknownAndRetainedMarkersAreExcludedFromTheReleaseEstimate() {
        val inventory = StorageInventory(listOf(
            StorageEntry("active", "audio", 100, 1, setOf("a"), inUse = true),
            StorageEntry("unknown", "audio", 100, 1, setOf("a"), unknown = true),
            StorageEntry("marks", "reading_marks", 20, 1, setOf("a")),
            StorageEntry("refs", "reading_refs", 5, 1, setOf("a"))))
        assertEquals(5L, inventory.releasable(setOf("a"), setOf("audio", "reading")))
        assertFalse(inventory.entries.first().safeCleanup)
        assertFalse(inventory.entries[1].optionalCleanup)
    }
}
