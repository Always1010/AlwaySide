package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SettingsRetentionTest {
    @Test fun pendingSettingsGuardUnconfirmedKeysAndDraftsParkedBehindApiManagement() {
        val secret = "sensitive-draft-value"
        val service = serviceState(FieldEditState("Key", "", secret))
        val pending = settingsState(service)
        assertTrue(pending.hasUnsavedChanges)
        assertTrue(pending.copy(service = service.copy(field = service.field?.copy())).hasUnsavedChanges)
        assertFalse(pending.toString().contains(secret))
        assertFalse(service.toString().contains(secret))

        val confirmedKey = service.copy(field = null, key = service.key!!.copy(secret = secret))
        assertTrue(settingsState(confirmedKey).hasUnsavedChanges)
        assertFalse(confirmedKey.key.toString().contains(secret))

        val parkedCard = service.copy(field = null, card = service.card!!.copy(model = "changed-model"))
        assertTrue(settingsState(parkedCard).hasUnsavedChanges)
    }

    @Test fun revertedFieldsAndServiceFormsDoNotRequireDiscardConfirmation() {
        val unchanged = serviceState(FieldEditState("Key", "", ""))
        assertFalse(settingsState(unchanged).hasUnsavedChanges)
        val changed = unchanged.copy(key = unchanged.key!!.copy(name = "renamed"))
        assertTrue(settingsState(changed).hasUnsavedChanges)
        assertFalse(settingsState(changed.copy(key = changed.keyInitial)).hasUnsavedChanges)

        val prompt = SettingsScreen.RetainedState("PROMPTS", null,
            FieldEditState("instructions", "saved", "draft"), "connection", null)
        assertTrue(prompt.hasUnsavedChanges)
        assertFalse(prompt.copy(field = prompt.field!!.copy(draft = "saved")).hasUnsavedChanges)
    }

    private fun settingsState(service: ServiceSettingsUi.RetainedState) =
        SettingsScreen.RetainedState("AI", service, null, null, null)

    private fun serviceState(field: FieldEditState?): ServiceSettingsUi.RetainedState {
        val key = ServiceSettingsUi.KeyForm(name = "saved-key")
        val card = ModelProfile("profile", "service", ModelPurpose.ANSWER, model = "saved-model")
        return ServiceSettingsUi.RetainedState(
            page = ServiceSettingsUi.Page.KEY,
            history = listOf(ServiceSettingsUi.Page.ROOT, ServiceSettingsUi.Page.CARD, ServiceSettingsUi.Page.API),
            purpose = ModelPurpose.ANSWER, card = card, cardInitial = card, modelDefaultPending = false,
            key = key, keyInitial = key, expected = null, connectionExpanded = true, optionsExpanded = true,
            field = field, status = "", verification = emptyMap(),
        )
    }
}
