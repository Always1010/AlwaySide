package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class FieldEditStateTest {
    @Test fun pristineAndRevertedDraftsDoNotRequireDiscardConfirmation() {
        val state = FieldEditState("instructions", "saved")
        assertFalse(state.changed)
        state.draft = "changed"
        assertTrue(state.changed)
        state.draft = "saved"
        assertFalse(state.changed)
    }

    @Test fun credentialEditorStartsEmptyAndDoesNotExposeDraftInDiagnostics() {
        val state = FieldEditState("apiKey", "")
        assertFalse(state.changed)
        state.draft = "private-key"
        assertTrue(state.changed)
        assertFalse(state.toString().contains("private-key"))
    }
}
