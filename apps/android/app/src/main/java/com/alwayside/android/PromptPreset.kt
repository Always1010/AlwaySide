package com.alwayside.android

/** A template contains prompt settings only; absent fields identify a legacy three-field template. */
internal data class PromptPreset(
    val instructions: String,
    val defaultQuestion: String,
    val audioQuestion: String,
    val textQuestion: String? = null,
    val transcriptionPrompt: String? = null,
    val audioOutput: String? = null,
) {
    fun mergeInto(current: AppConfig): AppConfig = current.copy(
        instructions = instructions,
        defaultQuestion = defaultQuestion,
        audioQuestion = audioQuestion,
        textQuestion = textQuestion ?: current.textQuestion,
        transcriptionPrompt = transcriptionPrompt ?: current.transcriptionPrompt,
        audioOutput = audioOutput ?: current.audioOutput,
    )

    companion object {
        fun capture(config: AppConfig) = PromptPreset(
            config.instructions, config.defaultQuestion, config.audioQuestion,
            config.textQuestion, config.transcriptionPrompt, config.audioOutput,
        )
    }
}
