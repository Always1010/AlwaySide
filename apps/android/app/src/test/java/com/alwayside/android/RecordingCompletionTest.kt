package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class RecordingCompletionTest {
    @Test fun menuManualModeRequiresAQuestionEvenWhenTheLimitPolicyWouldProcessAutomatically() {
        assertEquals(RecordingDisposition.READY, recordingDisposition(false, RecordingLimitAction.PROCESS, true, false, false))
        assertEquals(RecordingDisposition.READY, recordingDisposition(true, RecordingLimitAction.PROCESS, true, false, false))
        assertEquals(RecordingDisposition.DISCARD, recordingDisposition(true, RecordingLimitAction.DISCARD, true, false, false))
        assertEquals(RecordingDisposition.PROMPT, recordingDisposition(true, RecordingLimitAction.PROMPT, true, false, false))
    }

    @Test fun limitsStopOnEitherClockOrAudioSizeAndLatchBeforeLateManualStop() {
        listOf(96000 to 500L, 48000 to 1000L).forEach { (bytes, elapsed) ->
            val completion = RecordingCompletion()
            assertFalse(completion.shouldStop(0, 0, 96000, 1000))
            assertTrue(completion.shouldStop(bytes, elapsed, 96000, 1000))
            completion.stop(false)
            assertEquals(RecordingEndReason.LIMIT, completion.reason)
        }
    }

    @Test fun manualStopBeforeLimitKeepsOriginalProcessingAndCancellationOverridesBoth() {
        val manual = RecordingCompletion()
        manual.stop(false)
        assertTrue(manual.shouldStop(96000, 1000, 96000, 1000))
        assertEquals(RecordingEndReason.MANUAL, manual.reason)
        listOf(manual, RecordingCompletion().apply { shouldStop(96000, 1000, 96000, 1000) }).forEach {
            it.stop(true)
            it.stop(false)
            assertEquals(RecordingEndReason.CANCELLED, it.reason)
        }
    }

    @Test fun unlimitedRecordingDoesNotHitTimeLimit() {
        val completion = RecordingCompletion()
        assertFalse(completion.shouldStop(96000, 600000, RecordingLimits.MAX_PCM_BYTES, Long.MAX_VALUE))
        assertTrue(completion.shouldStop(RecordingLimits.MAX_PCM_BYTES, 600000, RecordingLimits.MAX_PCM_BYTES, Long.MAX_VALUE))
        assertEquals(RecordingEndReason.LIMIT, completion.reason)
    }

    @Test fun missingOrUnrecognizedPreferenceDefaultsToDiscardWithoutAutomaticRequests() {
        assertEquals(RecordingLimitAction.DISCARD, InteractionConfig().recordingLimitAction)
        listOf(null, "", "unknown").forEach { stored ->
            assertEquals(RecordingDisposition.DISCARD, recordingDisposition(true, RecordingLimitAction.from(stored), true, false))
        }
        RecordingLimitAction.entries.forEach { assertEquals(it, RecordingLimitAction.from(it.value)) }
    }

    @Test fun reachingLimitUsesExplicitPolicyRegardlessOfManualAutoProcessingSwitch() {
        listOf(false, true).forEach { auto ->
            listOf(false, true).forEach { manualQuestion ->
                assertEquals(RecordingDisposition.DISCARD, recordingDisposition(true, RecordingLimitAction.DISCARD, auto, manualQuestion))
                assertEquals(RecordingDisposition.PROMPT, recordingDisposition(true, RecordingLimitAction.PROMPT, auto, manualQuestion))
                assertEquals(RecordingDisposition.PROCESS, recordingDisposition(true, RecordingLimitAction.PROCESS, auto, manualQuestion))
            }
        }
    }

    @Test fun manualEndKeepsOriginalBehaviorForEveryLimitPolicy() {
        RecordingLimitAction.entries.forEach { action ->
            assertEquals(RecordingDisposition.PROCESS, recordingDisposition(false, action, true, false))
            assertEquals(RecordingDisposition.READY, recordingDisposition(false, action, false, false))
            assertEquals(RecordingDisposition.READY, recordingDisposition(false, action, true, true))
        }
    }
}
