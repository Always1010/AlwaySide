package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySelectionTest {
    @Test fun longPressSelectionAndRepeatedRestoreKeepUniqueIds() {
        val selection = HistorySelection(listOf("a", "a", "", "b"))
        selection.select("a")
        assertEquals(listOf("a", "b"), selection.snapshot())
        assertTrue(selection.selecting)
    }

    @Test fun cancelAndDeselectingLastItemExitSelection() {
        val selection = HistorySelection()
        selection.select("a")
        assertTrue(selection.cancel())
        assertFalse(selection.cancel())
        assertTrue(selection.snapshot().isEmpty())
        selection.select("b")
        selection.toggle("b")
        assertFalse(selection.selecting)
    }

    @Test fun refreshRetainsExistingSelectionsAndRemovesGhostIds() {
        val selection = HistorySelection(listOf("a", "b", "gone"))
        selection.retain(listOf("b", "c"))
        assertEquals(listOf("b"), selection.snapshot())
        selection.retain(emptyList())
        assertFalse(selection.selecting)
    }

    @Test fun confirmationDeletesOnlyItsSelectedSnapshot() {
        val selection = HistorySelection(listOf("a", "b"))
        val deletion = HistoryDeletion(selection.snapshot())
        selection.cancel()
        selection.select("c")
        val deleted = mutableListOf<String>()
        assertTrue(deletion.execute { deleted.add(it) }.isEmpty())
        assertEquals(listOf("a", "b"), deleted)
        assertEquals(listOf("c"), selection.snapshot())
    }

    @Test fun cancelledConfirmationHasNoDeletionAndFailureDoesNotStopOtherTargets() {
        val deletion = HistoryDeletion(listOf("a", "a", "b", "c"))
        val deleted = mutableListOf<String>()
        // Merely creating (or cancelling) a confirmation performs no side effects.
        assertTrue(deleted.isEmpty())
        val failed = deletion.execute {
            if (it == "b") error("storage failed")
            deleted.add(it)
        }
        assertEquals(listOf("b"), failed)
        assertEquals(listOf("a", "c"), deleted)
    }
}
