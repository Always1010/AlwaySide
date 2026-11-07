package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SettingsUpdateTest {
    private val saved = AppConfig(apiKey = "saved-key", baseUrl = "https://provider.example/v1",
        model = "image", transcriptionModel = "audio", instructions = "saved instructions", topP = 0.7)

    @Test fun singleTextPatchPreservesConnectionCredentialsAndOtherFields() {
        val updated = SettingsUpdate.Text(SettingsUpdate.TextField.defaultQuestion, "new question").applyTo(saved)
        assertEquals(saved.copy(defaultQuestion = "new question"), updated)
    }

    @Test fun patchesComposeAgainstLatestSavedState() {
        val patch = SettingsUpdate.Text(SettingsUpdate.TextField.model, "new model")
        val latest = saved.copy(apiKey = "new-key", instructions = "new instructions")
        assertEquals(latest.copy(model = "new model"), patch.applyTo(latest))
    }

    @Test fun optionalEmptyInputClearsOnlyThatValue() {
        val current = saved.copy(topP = null, temperature = 0.8)
        assertEquals(current.copy(temperature = null), SettingsUpdate.Text(SettingsUpdate.TextField.temperature, " ").applyTo(current))
        assertEquals(saved.copy(maxOutputTokens = 0), SettingsUpdate.Text(SettingsUpdate.TextField.maxOutputTokens, "").applyTo(saved))
        assertEquals(saved.copy(transcriptionPrompt = ""), SettingsUpdate.Text(SettingsUpdate.TextField.transcriptionPrompt, "").applyTo(saved))
        val language = SettingsUpdate.Text(SettingsUpdate.TextField.transcriptionLanguage, " en ").applyTo(saved)
        assertEquals(saved.copy(transcriptionLanguage = "en"), language)
        assertEquals(saved, SettingsUpdate.Text(SettingsUpdate.TextField.transcriptionLanguage, " ").applyTo(language))
    }

    @Test fun emptyCredentialCannotEraseSavedKey() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsUpdate.Text(SettingsUpdate.TextField.apiKey, " ").applyTo(saved)
        }
        assertEquals("saved-key", saved.apiKey)
        assertEquals("", SettingsUpdate.TextField.apiKey.read(saved))
    }

    @Test fun samplingReplacementRequiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsUpdate.Text(SettingsUpdate.TextField.temperature, "0.4").applyTo(saved)
        }
        val result = SettingsUpdate.Text(SettingsUpdate.TextField.temperature, "0.4", replaceSampling = true).applyTo(saved)
        assertEquals(saved.copy(temperature = 0.4, topP = null), result)
        val reverse = SettingsUpdate.Text(SettingsUpdate.TextField.topP, "0.8", replaceSampling = true).applyTo(result)
        assertEquals(result.copy(temperature = null, topP = 0.8), reverse)
    }

    @Test fun clearingSamplingDoesNotClearOtherParameter() {
        assertEquals(saved, SettingsUpdate.Text(SettingsUpdate.TextField.temperature, "", replaceSampling = true).applyTo(saved))
    }

    @Test fun invalidNumbersCannotBecomeNullSilently() {
        listOf("hello", "NaN", "Infinity").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SettingsUpdate.Text(SettingsUpdate.TextField.temperature, value).applyTo(saved)
            }
        }
    }

    @Test fun immediateChoiceAndToggleKeepUnrelatedSavedFields() {
        assertEquals(saved.copy(reasoning = "high"), SettingsUpdate.Choice(SettingsUpdate.ChoiceField.reasoning, "high").applyTo(saved))
        assertEquals(saved.copy(webSearch = true), SettingsUpdate.Toggle(webSearch = true).applyTo(saved))
    }

    @Test fun presetPatchOnlyTouchesPromptFieldsAndKeepsLegacyMissingFields() {
        val current = saved.copy(textQuestion = "new text", transcriptionPrompt = "names", audioOutput = "transcript")
        val result = SettingsUpdate.Prompts(PromptPreset("instructions", "image", "audio")).applyTo(current)
        assertEquals(current.copy(instructions = "instructions", defaultQuestion = "image", audioQuestion = "audio"), result)
    }

    @Test fun diagnosticsDoNotExposeCredentialDraft() {
        assertFalse(SettingsUpdate.Text(SettingsUpdate.TextField.apiKey, "private-key").toString().contains("private-key"))
    }
}
