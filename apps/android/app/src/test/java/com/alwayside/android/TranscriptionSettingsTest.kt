package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class TranscriptionSettingsTest {
    private val legacy = mapOf<String, Any?>("services" to mapOf(
        "activeTranscriptionId" to "active",
        "profiles" to listOf(
            mapOf("id" to "inactive", "purpose" to "TRANSCRIPTION", "language" to "ja"),
            mapOf("id" to "active", "purpose" to "TRANSCRIPTION", "language" to " en "))))

    @Test fun migrationUsesOnlyTheActiveLegacyLanguageAndExplicitGlobalsAlwaysWin() {
        assertEquals("en", TranscriptionSettings.readLanguage(legacy))
        assertEquals("zh", TranscriptionSettings.readLanguage(legacy + ("transcriptionLanguage" to "zh")))
        val automatic = legacy + ("transcriptionLanguage" to "")
        assertEquals("", TranscriptionSettings.readLanguage(automatic))
        assertEquals("", TranscriptionSettings.readLanguage(SettingsRecovery.reset(automatic)))
        assertEquals("en", SettingsRecovery.reset(legacy)["transcriptionLanguage"])
        assertEquals("", TranscriptionSettings.readLanguage(mapOf("services" to mapOf("profiles" to listOf(
            mapOf("id" to "inactive", "purpose" to "TRANSCRIPTION", "language" to "ja"))))))
    }

    @Test fun contextUsesDocumentedMultipartLanguageFieldsAndAutomaticOmitsTheHint() {
        val config = AppConfig(providerKind = ProviderKind.OPENAI, transcriptionModel = "gpt-transcribe",
            transcriptionPrompt = "Project names: Codex", transcriptionLanguage = "zh")
        val parts = TranscriptionSettings.multipart(config, "test-boundary")
        assertTrue(parts.contains("name=\"prompt\"\r\n\r\nProject names: Codex"))
        assertTrue(parts.contains("name=\"languages[]\"\r\n\r\nzh"))
        assertFalse(parts.contains("name=\"language\""))
        listOf(config.copy(transcriptionModel = "gpt-4o-transcribe"), config.copy(providerKind = ProviderKind.CUSTOM)).forEach {
            val compatible = TranscriptionSettings.multipart(it, "test-boundary")
            assertTrue(compatible.contains("name=\"language\"\r\n\r\nzh"))
            assertFalse(compatible.contains("name=\"languages[]\""))
        }
        assertEquals("", TranscriptionSettings.multipart(config.copy(transcriptionPrompt = "", transcriptionLanguage = ""), "test-boundary"))
    }

    @Test fun migrationPreservesTheActivePromptEvenAfterLanguageWasMigratedAndRunsOnlyOnce() {
        val old = legacy + mapOf("transcriptionPrompt" to "default for new cards", "transcriptionLanguage" to "en")
        val services = old["services"].wireObject()
        val saved = old + ("services" to (services + ("profiles" to services.items("profiles").map { item ->
            item.wireObject() + ("transcriptionPrompt" to if (item.wireObject()["id"] == "active") "Names: AlwaySide" else "inactive hint")
        })))
        val migrated = TranscriptionSettings.migrateLegacyContext(saved)
        assertEquals("Names: AlwaySide", migrated["transcriptionPrompt"])
        assertEquals("en", migrated["transcriptionLanguage"])
        migrated["services"].wireObject().items("profiles").forEach {
            assertFalse(it.wireObject().containsKey("transcriptionPrompt"))
            assertFalse(it.wireObject().containsKey("language"))
        }
        assertEquals(migrated, TranscriptionSettings.migrateLegacyContext(migrated))
        val cleared = migrated + ("transcriptionPrompt" to "")
        assertEquals(cleared, TranscriptionSettings.migrateLegacyContext(cleared))
        assertEquals("Names: AlwaySide", SettingsRecovery.reset(saved)["transcriptionPrompt"])
    }

    @Test fun migrationPreservesAnExplicitlyEmptyCardPromptAndFallsBackWithoutAnActiveCard() {
        val services = legacy["services"].wireObject()
        val saved = legacy + ("transcriptionPrompt" to "default hint") +
            ("services" to (services + ("profiles" to services.items("profiles").map { item ->
                item.wireObject() + ("transcriptionPrompt" to "")
            })))
        assertEquals("", TranscriptionSettings.migrateLegacyContext(saved)["transcriptionPrompt"])
        assertEquals("", SettingsRecovery.reset(saved)["transcriptionPrompt"])
        assertEquals("default hint", TranscriptionSettings.migrateLegacyContext(
            saved + ("services" to (saved["services"].wireObject() + ("activeTranscriptionId" to null))))["transcriptionPrompt"])
        assertEquals("default hint", TranscriptionSettings.migrateLegacyContext(
            legacy + ("transcriptionPrompt" to "default hint"))["transcriptionPrompt"])
    }

    @Test fun languageValidationRejectsInvalidCodesBeforeSendingOrSavingTemplates() {
        listOf("", "zh", "en", "yue", "zh-cn").forEach(TranscriptionSettings::validateLanguage)
        listOf("English", "zh,en", "en\r\ninjected").forEach { code ->
            assertThrows(IllegalArgumentException::class.java) {
                TranscriptionSettings.multipart(AppConfig(transcriptionLanguage = code), "test-boundary")
            }
            assertThrows(IllegalArgumentException::class.java) {
                PromptPreset.capture(AppConfig(transcriptionLanguage = code)).validate()
            }
        }
    }
}
