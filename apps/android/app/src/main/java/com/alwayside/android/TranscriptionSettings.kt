package com.alwayside.android

/** Recording context is global; service profiles only select the transcription backend. */
internal object TranscriptionSettings {
    private val languageCode = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")

    fun validateLanguage(value: String) {
        require(value.isEmpty() || languageCode.matches(value)) { "音频语言请填写语言代码或选择自动识别" }
    }

    /** Consume legacy card fields once, keeping the active card's effective prompt, including empty. */
    fun migrateLegacyContext(data: Map<String, Any?>): Map<String, Any?> {
        val services = data["services"] as? Map<*, *>
        val profiles = services?.get("profiles") as? List<*>
        val migrated = data + mapOf(
            "transcriptionPrompt" to (activeLegacyProfile(data)?.get("transcriptionPrompt") as? String
                ?: data["transcriptionPrompt"] as? String ?: AppConfig().transcriptionPrompt),
            "transcriptionLanguage" to readLanguage(data))
        if (profiles == null) return migrated
        return migrated + ("services" to (services + ("profiles" to profiles.map { profile ->
            (profile as? Map<*, *>)?.filterKeys { it != "transcriptionPrompt" && it != "language" } ?: profile
        })))
    }

    private fun activeLegacyProfile(data: Map<String, Any?>?): Map<*, *>? {
        val services = data?.get("services") as? Map<*, *>
        val active = services?.get("activeTranscriptionId") as? String ?: return null
        return (services["profiles"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull {
            it["id"] == active && it["purpose"] == "TRANSCRIPTION"
        }
    }

    /** Missing global language imports only the active legacy profile; an explicit empty value wins. */
    fun readLanguage(data: Map<String, Any?>?): String {
        val value = if (data?.containsKey("transcriptionLanguage") == true) data["transcriptionLanguage"] else {
            activeLegacyProfile(data)?.get("language")
        }
        return (value as? String).orEmpty().trim().takeIf { it.isEmpty() || languageCode.matches(it) }.orEmpty()
    }

    fun multipart(config: AppConfig, boundary: String): String {
        validateLanguage(config.transcriptionLanguage)
        return buildString {
            fun part(name: String, value: String) {
                append("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value")
            }
            if (config.transcriptionPrompt.isNotBlank()) part("prompt", config.transcriptionPrompt)
            if (config.transcriptionLanguage.isNotEmpty()) {
                // The documented OpenAI model uses an array; other compatible services retain their contract.
                val name = if (config.providerKind == ProviderKind.OPENAI && config.transcriptionModel == "gpt-transcribe")
                    "languages[]" else "language"
                part(name, config.transcriptionLanguage)
            }
        }
    }
}
