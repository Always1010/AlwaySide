package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class BubbleGestureTest {
    private fun machine(enabled: Boolean = true, left: Boolean = false) =
        BubbleGestureStateMachine(enabled, 600, 1500, 8f, 24f, left).apply { down(0f, 0f, 1000) }

    @Test fun releaseAndTimeoutConfirmOnlyOnceAfterArming() {
        val release = machine()
        assertNull(release.advance(1599))
        assertEquals(BubbleGestureStateMachine.Phase.PRESSING, release.phase)
        assertNull(release.advance(1600))
        assertEquals(BubbleGestureStateMachine.Phase.ARMED, release.phase)
        assertEquals(BubbleGesture.HOLD, release.up(1700))
        assertNull(release.advance(5000))
        val timeout = machine()
        assertNull(timeout.advance(3099))
        assertEquals(BubbleGesture.HOLD, timeout.advance(3100))
        assertNull(timeout.move(0f, -40f, 3200))
        assertNull(timeout.up(3300))
    }

    @Test fun directionExecutesImmediatelyAndCancelsLongPressTimeout() {
        for ((dx, dy, expected) in listOf(Triple(0f, -24f, BubbleGesture.UP),
            Triple(0f, 24f, BubbleGesture.DOWN), Triple(-24f, 0f, BubbleGesture.INWARD))) {
            val gesture = machine()
            assertEquals(expected, gesture.move(dx, dy, 1700))
            assertNull(gesture.advance(4000))
            assertNull(gesture.up(4100))
        }
        assertEquals(BubbleGesture.INWARD, machine(left = true).move(24f, 0f, 1700))
    }

    @Test fun ordinaryDragStaysDragEvenAfterStoppingAndHolding() {
        val gesture = machine()
        assertNull(gesture.move(9f, 0f, 1100))
        assertEquals(BubbleGestureStateMachine.Phase.MOVING, gesture.phase)
        assertNull(gesture.move(0f, 0f, 4000))
        assertNull(gesture.advance(5000))
        assertNull(gesture.up(6000))
        val congested = machine()
        congested.advance(1700)
        assertNull(congested.move(10f, 0f, 1500))
        assertEquals(BubbleGestureStateMachine.Phase.MOVING, congested.phase)
        assertNull(congested.up(1800))
    }

    @Test fun jitterCancellationAndOutwardMotionNeverSendAnExtraAction() {
        val jitter = machine()
        assertNull(jitter.move(5f, -3f, 1700))
        assertEquals(BubbleGesture.HOLD, jitter.up(1800))
        val cancelled = machine()
        cancelled.advance(1700); cancelled.cancel()
        assertNull(cancelled.advance(4000)); assertNull(cancelled.up(4100))
        val outward = machine()
        assertNull(outward.move(30f, 0f, 1700))
        assertEquals(BubbleGestureStateMachine.Phase.FINISHED, outward.phase)
        assertNull(outward.up(1800))
    }

    @Test fun classicModeStillFiresAtItsThresholdWithoutWaitingForRelease() {
        val gesture = machine(enabled = false)
        assertEquals(BubbleGesture.HOLD, gesture.advance(1600))
        assertNull(gesture.up(1700))
        assertEquals(BubbleGesture.SINGLE, machine().up(1200))
    }

    @Test fun legacyCustomBindingsAndSeparateRecordingActionsArePreserved() {
        val original = InteractionConfig(singleAction = AssistantActions.HISTORY,
            recordingSingleAction = AssistantActions.SETTINGS, tripleAction = AssistantActions.NONE)
        val migrated = original.copy(bindings = GestureBindings.legacy(original))
        assertEquals(AssistantActions.HISTORY, migrated.action(1, false))
        assertEquals(AssistantActions.SETTINGS, migrated.action(1, true))
        assertEquals(AssistantActions.START_AUDIO, migrated.action(2, false))
        assertEquals(AssistantActions.END_AUDIO, migrated.action(2, true))
        assertEquals(AssistantActions.NONE, migrated.action(3, false))
        assertEquals(AssistantActions.NONE, migrated.gestureAction(BubbleGesture.DOWN, false))
        assertEquals(AssistantActions.START_AUDIO, migrated.copy(longPressSlide = true).gestureAction(BubbleGesture.DOWN, false))
    }

    @Test fun conflictReplacementOnlyChangesOverlappingStatesAndSupportsMultipleGestures() {
        val original = GestureBindings.legacy(InteractionConfig())
        val conflict = GestureBindings.conflicts(original, AssistantActions.CROP, BubbleGesture.DOUBLE, setOf(false))
        assertEquals(listOf(AssistantActions.START_AUDIO), conflict.map { it.action })
        val changed = GestureBindings.assign(original, AssistantActions.CROP, BubbleGesture.DOUBLE, setOf(false))
        assertTrue(GestureBindings.valid(changed))
        assertTrue(changed.contains(GestureBinding(BubbleGesture.DOUBLE, true, AssistantActions.END_AUDIO)))
        assertTrue(changed.contains(GestureBinding(BubbleGesture.TRIPLE, false, AssistantActions.CROP)))
        assertTrue(changed.contains(GestureBinding(BubbleGesture.DOUBLE, false, AssistantActions.CROP)))
        assertFalse(GestureBindings.valid(changed + changed.first()))
    }

}
