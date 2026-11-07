package com.alwayside.android

/** Recording context is global; service profiles only select the transcription backend. */
internal object TranscriptionSettings {
    private val languageCode = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")

    fun validateLanguage(value: String) {
        require(value.isEmpty() || languageCode.matches(value)) { "音频语言请填写语言代码或选择自动识别" }
    }

    /** Missing global language imports only the active legacy profile; an explicit empty value wins. */
    fun readLanguage(data: Map<String, Any?>?): String {
        val value = if (data?.containsKey("transcriptionLanguage") == true) data["transcriptionLanguage"] else {
            val services = data?.get("services") as? Map<*, *>
            val active = services?.get("activeTranscriptionId") as? String
            val profiles = services?.get("profiles") as? List<*>
            profiles?.mapNotNull { it as? Map<*, *> }?.firstOrNull {
                active != null && it["id"] == active && it["purpose"] == "TRANSCRIPTION"
            }?.get("language")
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
