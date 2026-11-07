package com.alwayside.android

/** A template contains prompt settings only; absent fields identify a legacy three-field template. */
internal data class PromptPreset(
    val instructions: String,
    val defaultQuestion: String,
    val audioQuestion: String,
    val textQuestion: String? = null,
    val transcriptionPrompt: String? = null,
    val audioOutput: String? = null,
    val transcriptionLanguage: String? = null,
) {
    fun validate() {
        transcriptionLanguage?.let(TranscriptionSettings::validateLanguage)
        require(instructions.length <= 32000 && (transcriptionPrompt?.length ?: 0) <= 32000) {
            "助手回答方式和转写提示不能超过 32000 字符"
        }
        require(listOfNotNull(defaultQuestion, audioQuestion, textQuestion).all { it.length <= ChatPrompt.MAX_QUESTION_CHARS }) {
            "默认问题不能超过 ${ChatPrompt.MAX_QUESTION_CHARS} 字符"
        }
        require(audioOutput == null || audioOutput in setOf("transcript", "translate", "interpret")) { "音频输出方式无效" }
    }

    fun mergeInto(current: AppConfig): AppConfig = current.copy(
        instructions = instructions,
        defaultQuestion = defaultQuestion,
        audioQuestion = audioQuestion,
        textQuestion = textQuestion ?: current.textQuestion,
        transcriptionPrompt = transcriptionPrompt ?: current.transcriptionPrompt,
        transcriptionLanguage = transcriptionLanguage ?: current.transcriptionLanguage,
        audioOutput = audioOutput ?: current.audioOutput,
    )

    companion object {
        fun capture(config: AppConfig) = PromptPreset(
            config.instructions, config.defaultQuestion, config.audioQuestion,
            config.textQuestion, config.transcriptionPrompt, config.audioOutput, config.transcriptionLanguage,
        )
    }
}
