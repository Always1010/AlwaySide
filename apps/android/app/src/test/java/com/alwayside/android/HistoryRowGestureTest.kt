package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class HistoryRowGestureTest {
    @Test fun holdWaitsAtLeastSevenHundredMillisAndHonorsLongerSystemDelay() {
        val gesture = HistoryRowGesture(8f, 500)
        gesture.down(0f, 0f, 100)
        assertFalse(gesture.hold(799))
        assertTrue(gesture.hold(800))
        assertFalse(gesture.up())
        val accessible = HistoryRowGesture(8f, 1200)
        accessible.down(0f, 0f, 0)
        assertFalse(accessible.hold(700))
        assertTrue(accessible.hold(1200))
    }

    @Test fun scrollingAndMultitouchCancellationCannotBecomeHoldOrTap() {
        val gesture = HistoryRowGesture(8f, 500)
        gesture.down(10f, 10f, 0)
        gesture.move(12f, 30f)
        assertEquals(HistoryRowGesture.Phase.SCROLL, gesture.phase)
        assertFalse(gesture.hold(1000))
        assertFalse(gesture.up())
        gesture.down(0f, 0f, 0)
        gesture.cancel()
        assertFalse(gesture.hold(1000))
        assertFalse(gesture.up())
        gesture.down(0f, 0f, 0)
        gesture.move(2f, 3f)
        assertTrue(gesture.up())
    }

    @Test fun horizontalSwipeLocksDirectionAndCannotClickOrHold() {
        val gesture = HistoryRowGesture(8f, 500)
        gesture.down(0f, 0f, 0, swipe = true)
        gesture.move(30f, 3f)
        assertEquals(HistoryRowGesture.Phase.SWIPE, gesture.phase)
        gesture.move(-40f, 100f)
        assertEquals(HistoryRowGesture.Phase.SWIPE, gesture.phase)
        assertEquals(-40f, gesture.dx)
        assertFalse(gesture.hold(1000))
        assertFalse(gesture.up())
        gesture.down(0f, 0f, 0, swipe = true)
        gesture.move(10f, 12f)
        gesture.move(100f, 12f)
        assertEquals(HistoryRowGesture.Phase.SCROLL, gesture.phase)
    }

    @Test fun selectionAndEdgeGesturesDisableSwipeAndHoldingCannotTurnIntoSwipe() {
        val gesture = HistoryRowGesture(8f, 500)
        gesture.down(0f, 0f, 0, swipe = false)
        gesture.move(40f, 1f)
        assertEquals(HistoryRowGesture.Phase.SCROLL, gesture.phase)
        assertFalse(gesture.up())
        gesture.down(0f, 0f, 0, swipe = true)
        assertTrue(gesture.hold(700))
        gesture.move(100f, 0f)
        assertEquals(HistoryRowGesture.Phase.HOLD, gesture.phase)
        assertFalse(gesture.up())
    }
}
