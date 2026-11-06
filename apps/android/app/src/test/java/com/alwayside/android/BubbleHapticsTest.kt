package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class BubbleHapticsTest {
    @Test fun fallbackRequiresStandardFailureAndPermissionToPlayTouchFeedback() {
        for (standardAccepted in listOf(false, true)) {
            for (touchAllowed in listOf(false, true)) {
                var attempts = 0
                BubbleHaptics.perform({ standardAccepted }, { touchAllowed }, { attempts++ })
                assertEquals(if (!standardAccepted && touchAllowed) 1 else 0, attempts)
            }
        }
        // A denied service or inaccessible preference never escapes into touch handling.
        BubbleHaptics.perform({ throw SecurityException() }, { true }, { fail("Unexpected retry") })
        BubbleHaptics.perform({ false }, { throw SecurityException() }, { fail("Unknown preference") })
        BubbleHaptics.perform({ false }, { true }, { throw SecurityException() })
    }

    @Test fun eachHoldConfirmsOnceWhileEarlyCancellationAndDraggingStaySilent() {
        for (slide in listOf(false, true)) {
            val machine = BubbleGestureStateMachine(slide, 600, 1500, 8f, 24f, false)
            var confirmations = 0
            fun step(now: Long, action: () -> Unit) {
                val previous = machine.phase
                action()
                if (BubbleHaptics.reachedHold(machine, previous, now, confirmations > 0)) confirmations++
            }
            machine.down(0f, 0f, 1000)
            step(1599) { machine.advance(1599) }
            assertEquals(0, confirmations)
            step(1600) { machine.advance(1600) }
            step(1700) { machine.up(1700) }
            step(4000) { machine.advance(4000) }
            assertEquals(1, confirmations)

            confirmations = 0
            machine.down(0f, 0f, 1000)
            step(1500) { machine.up(1500) }
            step(4000) { machine.advance(4000) }
            assertEquals(0, confirmations)

            machine.down(0f, 0f, 1000)
            machine.cancel()
            step(4000) { machine.advance(4000) }
            assertEquals(0, confirmations)

            machine.down(0f, 0f, 1000)
            step(1100) { machine.move(9f, 0f, 1100) }
            step(4000) { machine.advance(4000) }
            step(4100) { machine.up(4100) }
            assertEquals(0, confirmations)

            machine.down(0f, 0f, 1000)
            // Delayed scheduling may jump straight from PRESSING to timeout/release.
            step(4000) { machine.advance(4000) }
            step(4100) { machine.up(4100) }
            assertEquals(1, confirmations)
        }
    }
}
