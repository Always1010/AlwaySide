package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class InteractionTest {
    @Test fun multiTapBurstEmitsOnlyItsFinalGesture() {
        val taps = TapSequence()
        assertEquals(1, taps.tap(1000))
        assertEquals(2, taps.tap(1100))
        assertEquals(3, taps.tap(1200))
        assertEquals(3, taps.settle())
        assertEquals(0, taps.settle())
        assertEquals(1, taps.tap(2000))
        assertEquals(1, taps.tap(2400))
        assertEquals(1, taps.settle())
    }
    @Test fun draggingOrLongPressDiscardsPendingTaps() {
        val taps = TapSequence()
        taps.tap(1); taps.tap(20); taps.cancel()
        assertEquals(0, taps.settle())
        assertEquals(1, taps.tap(200))
        assertEquals(1, taps.settle())
    }
    @Test fun cancellingRecordingRejectsLateCompletionAndDoesNotCancelNextRecording() {
        val gate = RecordingGate()
        val old = gate.begin()
        gate.cancel()
        val next = gate.begin()
        assertFalse(gate.complete(old))
        assertTrue(gate.complete(next))
        assertFalse(gate.complete(next))
    }
    @Test fun recordingGesturesHaveSeparateDefaultsAndCanBeRemapped() {
        val cfg = InteractionConfig()
        assertEquals(AssistantActions.SCREEN, cfg.action(1, false))
        assertEquals(AssistantActions.CANCEL_AUDIO, cfg.action(1, true))
        assertEquals(AssistantActions.AUDIO, cfg.action(2, true))
        assertEquals(AssistantActions.NONE, cfg.action(3, false))
        assertEquals(AssistantActions.CANCEL_AUDIO, cfg.action(3, true))
        assertEquals(AssistantActions.MENU, cfg.action(4, true))
        assertEquals(AssistantActions.MENU, cfg.copy(recordingSingleAction = AssistantActions.MENU).action(1, true))
    }
}
