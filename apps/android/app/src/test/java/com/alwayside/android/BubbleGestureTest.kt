package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class BubbleGestureTest {
    private fun machine(enabled: Boolean = true, left: Boolean = false) =
        BubbleGestureStateMachine(enabled, 600, 8f, 24f, left).apply { down(0f, 0f, 1000) }

    @Test fun releaseConfirmsOnceAndHoldingNeverExecutesAutomatically() {
        val release = machine()
        assertNull(release.advance(1599))
        assertEquals(BubbleGestureStateMachine.Phase.PRESSING, release.phase)
        assertNull(release.advance(1600))
        assertEquals(BubbleGestureStateMachine.Phase.ARMED, release.phase)
        assertEquals(BubbleGesture.HOLD, release.up(1700))
        assertNull(release.advance(5000))
        val waiting = machine()
        assertNull(waiting.advance(100000))
        assertEquals(BubbleGestureStateMachine.Phase.ARMED, waiting.phase)
        assertNull(waiting.nextDeadline)
        assertEquals(1f, waiting.progress(100000))
        assertEquals(BubbleGesture.HOLD, waiting.up(100001))
    }

    @Test fun shortDirectionOnlyPreselectsAndReleaseExecutesExactlyOnce() {
        for ((dx, dy, expected) in listOf(Triple(0f, -24f, BubbleGesture.UP),
            Triple(0f, 24f, BubbleGesture.DOWN), Triple(-24f, 0f, BubbleGesture.INWARD))) {
            val gesture = machine()
            assertNull(gesture.move(dx, dy, 1700))
            assertEquals(expected, gesture.selected)
            assertNull(gesture.advance(4000))
            assertEquals(expected, gesture.up(4100))
            assertNull(gesture.up(4200))
        }
        val mirrored = machine(left = true)
        assertNull(mirrored.move(24f, 0f, 1700))
        assertEquals(BubbleGesture.INWARD, mirrored.up(1800))
    }

    @Test fun selectionCanChangeAndReturningToCenterCancelsInsteadOfTakingScreenshot() {
        val gesture = machine()
        gesture.move(0f, -24f, 1700)
        gesture.move(-30f, 0f, 1800)
        assertEquals(BubbleGesture.INWARD, gesture.selected)
        gesture.move(0f, 0f, 1900)
        assertTrue(gesture.cancelled)
        assertTrue(gesture.returnedToCenter)
        assertNull(gesture.up(2000))
        val reselected = machine()
        reselected.move(0f, -24f, 1700)
        reselected.move(0f, 0f, 1800)
        reselected.move(0f, 24f, 1900)
        assertFalse(reselected.cancelled)
        assertEquals(BubbleGesture.DOWN, reselected.up(2000))
    }

    @Test fun directionAndCenterHaveHysteresisWithoutMakingTheStrokeLonger() {
        val gesture = machine()
        gesture.move(0f, -24f, 1700)
        gesture.move(-25f, -24f, 1750)
        assertEquals(BubbleGesture.UP, gesture.selected)
        gesture.move(-31f, -24f, 1800)
        assertEquals(BubbleGesture.INWARD, gesture.selected)
        gesture.move(-18f, 0f, 1850)
        assertEquals(BubbleGesture.INWARD, gesture.selected)
        gesture.move(-12f, 0f, 1900)
        assertNull(gesture.selected)
        assertTrue(gesture.cancelled)
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
        assertEquals(BubbleGestureStateMachine.Phase.ARMED, outward.phase)
        assertTrue(outward.cancelled)
        assertFalse(outward.returnedToCenter)
        assertNull(outward.up(1800))
    }

    @Test fun classicModeStillFiresAtItsThresholdWithoutWaitingForRelease() {
        val gesture = machine(enabled = false)
        assertEquals(BubbleGesture.HOLD, gesture.advance(1600))
        assertNull(gesture.up(1700))
        assertEquals(BubbleGesture.SINGLE, machine().up(1200))
    }

    @Test fun conflictsAreRejectedAndOnlyIncompatibleOwnersAreReported() {
        val original = GestureBindings.defaults()
        val conflict = GestureBindings.conflicts(original, AssistantActions.CROP, BubbleGesture.DOUBLE, setOf(false))
        assertEquals(listOf(AssistantActions.START_AUDIO), conflict.map { it.action })
        try {
            GestureBindings.assign(original, AssistantActions.CROP, BubbleGesture.DOUBLE, setOf(false))
            fail("An occupied gesture must never replace its owner")
        } catch (_: IllegalArgumentException) { }
        assertEquals(GestureBindings.defaults(), original)
        assertFalse(GestureBindings.valid(original + original.first()))
    }

    @Test fun fixedFunctionRulesAllowCompatiblePairsAndRejectAllConflictingPairs() {
        val cases = listOf(
            Triple(AssistantActions.SCREEN, AssistantActions.START_AUDIO, true),
            Triple(AssistantActions.SCREEN, AssistantActions.CROP, true),
            Triple(AssistantActions.CROP, AssistantActions.START_AUDIO, true),
            Triple(AssistantActions.END_AUDIO, AssistantActions.CANCEL_AUDIO, true),
            Triple(AssistantActions.START_AUDIO, AssistantActions.END_AUDIO, false),
            Triple(AssistantActions.CROP, AssistantActions.CANCEL_AUDIO, false),
            Triple(AssistantActions.SCREEN, AssistantActions.END_AUDIO, false),
        ) + GestureBindings.functions.filter { it.action != AssistantActions.MENU }.map {
            Triple(AssistantActions.MENU, it.action, true)
        }
        for ((first, second, blocked) in cases) for ((owner, target) in listOf(first to second, second to first)) {
            val ownerFunction = GestureBindings.functions.first { it.action == owner }
            val targetFunction = GestureBindings.functions.first { it.action == target }
            val assigned = GestureBindings.assign(emptyList(), owner, BubbleGesture.INWARD, ownerFunction.states)
            assertEquals("$owner / $target", blocked,
                GestureBindings.conflicts(assigned, target, BubbleGesture.INWARD, targetFunction.states).isNotEmpty())
            if (!blocked) assertTrue(GestureBindings.valid(GestureBindings.assign(
                assigned, target, BubbleGesture.INWARD, targetFunction.states)))
        }
    }

    @Test fun editingVisibleChoicesPreservesHiddenDirectionsAndOtherFunctions() {
        assertEquals(listOf(BubbleGesture.SINGLE, BubbleGesture.DOUBLE, BubbleGesture.TRIPLE, BubbleGesture.HOLD),
            GestureBindings.visibleGestures(false))
        val withDirection = GestureBindings.assign(GestureBindings.defaults(), AssistantActions.SCREEN,
            BubbleGesture.INWARD, setOf(false))
        val visible = GestureBindings.visibleGestures(false).toSet()
        val menuRemoved = GestureBindings.updateSelection(withDirection, AssistantActions.MENU, visible, emptySet())
        val updated = GestureBindings.updateSelection(menuRemoved, AssistantActions.SCREEN, visible, setOf(BubbleGesture.SINGLE))
        assertTrue(GestureBindings.valid(updated))
        assertTrue(updated.contains(GestureBinding(BubbleGesture.INWARD, false, AssistantActions.SCREEN)))
        assertTrue(updated.contains(GestureBinding(BubbleGesture.DOUBLE, true, AssistantActions.END_AUDIO)))
        assertFalse(updated.any { it.action == AssistantActions.SCREEN && it.gesture == BubbleGesture.HOLD })
        val config = InteractionConfig(bindings = updated)
        assertEquals(AssistantActions.NONE, config.gestureAction(BubbleGesture.INWARD, false))
        assertEquals(AssistantActions.SCREEN, config.copy(longPressSlide = true).gestureAction(BubbleGesture.INWARD, false))
    }

    @Test fun practiceRequiresDirectionReleaseAndExplicitCancellation() {
        val lesson = LongPressSlideLesson()
        lesson.next(); assertEquals(0, lesson.index)
        assertFalse(lesson.accept(null))
        assertTrue(lesson.accept(BubbleGesture.HOLD)); lesson.next()
        for (direction in listOf(BubbleGesture.UP, BubbleGesture.DOWN, BubbleGesture.INWARD)) {
            assertFalse(lesson.accept(BubbleGesture.HOLD))
            assertTrue(lesson.accept(direction)); lesson.next()
        }
        assertFalse(lesson.accept(BubbleGesture.HOLD))
        assertTrue(lesson.accept(null)); lesson.next()
        assertTrue(lesson.complete)
    }

    @Test fun practiceRestoresItsStepAndPassedStateAfterTheHostIsRecreated() {
        val lesson = LongPressSlideLesson()
        lesson.accept(BubbleGesture.HOLD); lesson.next(); lesson.accept(BubbleGesture.UP)
        val restored = LongPressSlideLesson(lesson.index, lesson.passed)
        assertEquals(LongPressSlideLesson.Step.UP, restored.step)
        assertTrue(restored.passed)
        restored.next()
        assertEquals(LongPressSlideLesson.Step.DOWN, restored.step)
        val complete = LongPressSlideLesson(LongPressSlideLesson.Step.entries.size, true)
        assertTrue(complete.complete)
        assertFalse(complete.passed)
    }
}
