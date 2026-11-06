package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class PromptPresetTest {
    @Test fun configuredQuestionsAndTemplatesRespectTheSendingBudget() {
        val boundary = "问".repeat(ChatPrompt.MAX_QUESTION_CHARS)
        val preset = PromptPreset("说明".repeat(16000), boundary, boundary, boundary, "提".repeat(32000))
        preset.validate()
        ChatPrompt.validateQuestion(boundary)
        listOf(preset.copy(defaultQuestion = boundary + "问"), preset.copy(audioQuestion = boundary + "问"),
            preset.copy(textQuestion = boundary + "问")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
        // Empty values retain the existing explicit-clear/legacy-template behavior.
        PromptPreset("", "", "", null).validate()
    }

    @Test fun legacyTemplateKeepsNewFieldsFromCurrentDraft() {
        val draft = AppConfig(textQuestion = "draft text", transcriptionPrompt = "draft names", audioOutput = "transcript")
        val merged = PromptPreset("old instructions", "old image", "old audio").mergeInto(draft)
        assertEquals("old instructions", merged.instructions)
        assertEquals("old image", merged.defaultQuestion)
        assertEquals("old audio", merged.audioQuestion)
        assertEquals(draft.textQuestion, merged.textQuestion)
        assertEquals(draft.transcriptionPrompt, merged.transcriptionPrompt)
        assertEquals(draft.audioOutput, merged.audioOutput)
    }

    @Test fun fullTemplateRestoresEveryPromptFieldIncludingExplicitEmptyValues() {
        val source = AppConfig(instructions = "instructions", defaultQuestion = "image", audioQuestion = "audio",
            textQuestion = "text", transcriptionPrompt = "", audioOutput = "translate")
        val merged = PromptPreset.capture(source).mergeInto(AppConfig(transcriptionPrompt = "replace me"))
        assertEquals(source.instructions, merged.instructions)
        assertEquals(source.defaultQuestion, merged.defaultQuestion)
        assertEquals(source.audioQuestion, merged.audioQuestion)
        assertEquals(source.textQuestion, merged.textQuestion)
        assertEquals("", merged.transcriptionPrompt)
        assertEquals(source.audioOutput, merged.audioOutput)
    }

    @Test fun templateDoesNotRestoreCredentialsConnectionModelsOrGenerationOptions() {
        val source = AppConfig(apiKey = "source secret", baseUrl = "https://source.example/v1", model = "source model",
            transcriptionModel = "source audio", temperature = 1.0, webSearch = true)
        val current = AppConfig(apiKey = "current secret", baseUrl = "https://current.example/v1", model = "current model",
            transcriptionModel = "current audio", topP = 0.8)
        val result = PromptPreset.capture(source).mergeInto(current)
        assertEquals(current.apiKey, result.apiKey)
        assertEquals(current.baseUrl, result.baseUrl)
        assertEquals(current.model, result.model)
        assertEquals(current.transcriptionModel, result.transcriptionModel)
        assertEquals(current.temperature, result.temperature)
        assertEquals(current.topP, result.topP)
        assertEquals(current.webSearch, result.webSearch)
        assertFalse(PromptPreset::class.java.declaredFields.any { it.name in setOf("apiKey", "baseUrl", "model", "transcriptionModel") })
    }
}
