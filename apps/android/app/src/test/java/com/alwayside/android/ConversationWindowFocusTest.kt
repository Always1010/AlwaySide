package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ConversationWindowFocusTest {
    @Test fun pinnedReadingLeavesBackNavigationToTheUnderlyingApp() {
        val focus = ConversationWindowFocus(pinned = true)
        assertFalse(focus.focusable)
        focus.interact()
        assertTrue(focus.focusable)
        focus.leave()
        assertTrue(focus.pinned)
        assertFalse(focus.focusable)
        // Further outside touches must not reclaim focus.
        focus.leave()
        assertFalse(focus.focusable)
    }

    @Test fun inputAndSelectionCanAcquireFocusAgainAfterReturningToTheUnderlyingApp() {
        val focus = ConversationWindowFocus(pinned = true)
        repeat(3) {
            focus.interact()
            focus.setPinned(true) // Redrawing the pin must not interrupt editing.
            assertTrue(focus.focusable)
            focus.leave()
            assertFalse(focus.focusable)
        }
    }

    @Test fun pinningAnOpenWindowReleasesFocusAndUnpinningRestoresNormalDismissal() {
        val focus = ConversationWindowFocus(pinned = false)
        assertTrue(focus.focusable)
        focus.setPinned(true)
        assertFalse(focus.focusable)
        focus.setPinned(false)
        assertTrue(focus.focusable)
        focus.leave()
        assertTrue(focus.focusable)
        assertFalse(focus.pinned)
    }
}
