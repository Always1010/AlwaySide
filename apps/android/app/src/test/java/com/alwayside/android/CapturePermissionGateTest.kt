package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class CapturePermissionGateTest {
    @Test fun unrelatedAndDuplicateRepliesCannotConsumeTheCurrentContinuation() {
        val gate = CapturePermissionGate()
        var resumed = 0
        val id = gate.begin { resumed++ }
        assertNull(gate.consume(null))
        assertNull(gate.consume("unrelated"))
        assertTrue(gate.isPending)
        assertEquals(0, resumed)
        val reply = gate.consume(id)!!
        reply.continuation?.invoke()
        assertEquals(1, resumed)
        assertFalse(gate.isPending)
        assertNull(gate.consume(id))
    }

    @Test fun matchingCancellationReleasesTheGateWithoutExecutingTheContinuation() {
        val gate = CapturePermissionGate()
        var resumed = false
        val id = gate.begin { resumed = true }
        assertNotNull(gate.consume(id))
        assertFalse(gate.isPending)
        assertFalse(resumed)
        assertNotEquals(id, gate.begin())
    }

    @Test fun oldRepliesCannotResumeANewRequestOrRecreatedService() {
        val gate = CapturePermissionGate()
        val oldId = gate.begin()
        gate.cancel()
        val currentId = gate.begin()
        assertNotEquals(oldId, currentId)
        assertNull(gate.consume(oldId))
        assertTrue(gate.isPending)
        val recreated = CapturePermissionGate()
        val recreatedId = recreated.begin()
        assertNotEquals(oldId, recreatedId)
        assertNull(recreated.consume(oldId))
        assertTrue(recreated.isPending)
    }
}
