package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class OverlayPreviewStateTest {
    @Test fun previewCanRunWithoutAnAssistantAndALaterServiceObservesTheLease() {
        val state = OverlayPreviewState()
        val owner = Any()
        state.setActive(owner, true)
        val observed = mutableListOf<Boolean>()
        state.addListener { observed += state.active }
        assertEquals(listOf(true), observed)
        state.setActive(owner, false)
        assertEquals(listOf(true, false), observed)
    }

    @Test fun repeatedRenderingAndRecreationKeepOneLeaseWithoutFlashingRealOverlays() {
        val state = OverlayPreviewState()
        val retainedOwner = Any()
        val observed = mutableListOf<Boolean>()
        state.addListener { observed += state.active }
        repeat(4) { state.setActive(retainedOwner, true) }
        assertTrue(state.owns(retainedOwner))
        assertEquals(listOf(false, true), observed)
        state.setActive(retainedOwner, false)
        state.setActive(retainedOwner, false)
        assertEquals(listOf(false, true, false), observed)
        assertFalse(state.owns(retainedOwner))
    }

    @Test fun anotherPreviewCannotRestoreOverlaysUntilTheLastOwnerLeaves() {
        val state = OverlayPreviewState()
        val guide = Any(); val slide = Any()
        state.setActive(guide, true); state.setActive(slide, true)
        state.setActive(guide, false)
        state.setActive(Any(), false)
        assertTrue(state.active)
        state.setActive(slide, false)
        assertFalse(state.active)
    }

    @Test fun stoppingAServiceWhileSuspendedDoesNotCallItsOldListenerOrLoseThePreview() {
        val state = OverlayPreviewState()
        val owner = Any()
        var oldCalls = 0
        val oldListener = { oldCalls++; Unit }
        state.addListener(oldListener)
        state.setActive(owner, true)
        state.removeListener(oldListener)
        var hiddenByNewService = false
        state.addListener { hiddenByNewService = state.active }
        assertTrue(hiddenByNewService)
        state.setActive(owner, false)
        assertFalse(hiddenByNewService)
        assertEquals(2, oldCalls)
    }
}
