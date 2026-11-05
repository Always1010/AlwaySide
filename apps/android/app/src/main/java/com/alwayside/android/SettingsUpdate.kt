package com.alwayside.android

/** Patches are applied to the latest saved configuration, never a page snapshot. */
internal sealed interface SettingsUpdate {
    fun applyTo(current: AppConfig): AppConfig

    enum class TextField { baseUrl, apiKey, model, transcriptionModel, instructions, defaultQuestion, textQuestion, audioQuestion, transcriptionPrompt, maxOutputTokens, temperature, topP, connectionName, apiVersion, workspaceId;
        fun read(current: AppConfig): String = when (this) {
            baseUrl -> current.baseUrl
            apiKey -> ""
            model -> current.model
            transcriptionModel -> runCatching { ProviderConnections.transcription(current).transcriptionModel }.getOrDefault("")
            connectionName -> current.connectionName
            apiVersion -> current.apiVersion
            workspaceId -> current.workspaceId
            instructions -> current.instructions
            defaultQuestion -> current.defaultQuestion
            textQuestion -> current.textQuestion
            audioQuestion -> current.audioQuestion
            transcriptionPrompt -> current.transcriptionPrompt
            maxOutputTokens -> current.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty()
            temperature -> current.temperature?.toString().orEmpty()
            topP -> current.topP?.toString().orEmpty()
        }
    }
    enum class ChoiceField { reasoning, verbosity, audioOutput }
    data class Text(val field: TextField, val value: String, val replaceSampling: Boolean = false) : SettingsUpdate {
        override fun toString() = "SettingsUpdate.Text(field=$field, value redacted)"
        override fun applyTo(current: AppConfig): AppConfig = when (field) {
            TextField.baseUrl -> ProviderConnections.addressChanged(current, value)
            TextField.apiKey -> current.copy(credentialRevision = current.credentialRevision + 1, apiKey = value.trim().also { require(it.isNotBlank()) { "密钥不能为空；移除请使用移除密钥" } })
            TextField.model -> current.copy(model = value.trim())
            TextField.transcriptionModel -> ProviderConnections.setTranscriptionModel(current, value)
            TextField.connectionName -> current.copy(connectionName = value.trim())
            TextField.apiVersion -> current.copy(apiVersion = value.trim())
            TextField.workspaceId -> current.copy(workspaceId = value.trim())
            TextField.instructions -> current.copy(instructions = value)
            TextField.defaultQuestion -> current.copy(defaultQuestion = value)
            TextField.textQuestion -> current.copy(textQuestion = value)
            TextField.audioQuestion -> current.copy(audioQuestion = value)
            TextField.transcriptionPrompt -> current.copy(transcriptionPrompt = value)
            TextField.maxOutputTokens -> current.copy(maxOutputTokens = value.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: throw IllegalArgumentException("请填写整数或留空") })
            TextField.temperature -> optionalNumber(value).let { number ->
                require(number == null || current.topP == null || replaceSampling) { "请先确认替换另一项采样参数" }
                current.copy(temperature = number, topP = if (number != null && replaceSampling) null else current.topP)
            }
            TextField.topP -> optionalNumber(value).let { number ->
                require(number == null || current.temperature == null || replaceSampling) { "请先确认替换另一项采样参数" }
                current.copy(topP = number, temperature = if (number != null && replaceSampling) null else current.temperature)
            }
        }
    }
    data class Choice(val field: ChoiceField, val value: String) : SettingsUpdate {
        override fun applyTo(current: AppConfig): AppConfig = when (field) {
            ChoiceField.reasoning -> current.copy(reasoning = value)
            ChoiceField.verbosity -> current.copy(verbosity = value)
            ChoiceField.audioOutput -> current.copy(audioOutput = value)
        }
    }
    data class Toggle(val webSearch: Boolean? = null, val autoUpdateImage: Boolean? = null) : SettingsUpdate {
        override fun applyTo(current: AppConfig) = current.copy(webSearch = webSearch ?: current.webSearch,
            autoUpdateImage = autoUpdateImage ?: current.autoUpdateImage)
    }
    data class Prompts(val preset: PromptPreset) : SettingsUpdate {
        override fun applyTo(current: AppConfig) = preset.mergeInto(current)
    }
    companion object {
        fun optionalNumber(value: String): Double? = value.trim().takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw IllegalArgumentException("请填写有效数值或留空")
        }
    }
}

/** A single active editor owns its draft. Credentials always start empty. */
internal data class FieldEditState(val name: String, val initial: String, var draft: String = initial) {
    val changed: Boolean get() = draft != initial
    override fun toString() = "FieldEditState(name=$name, values redacted)"
}
