package com.alwayside.android

/** Offline choices, not account access. Checked against OpenAI audio docs on 2026-11-07. */
internal object ReadingPresets {
    const val DEFAULT_MODEL = "gpt-realtime-2.1-mini"
    const val DEFAULT_VOICE = "marin"
    val currentModels = listOf(
        ModelPreset(DEFAULT_MODEL, "GPT-Realtime 2.1 Mini", "默认 · 性价比"),
        ModelPreset("gpt-realtime-2.1", "GPT-Realtime 2.1", "效果优先"),
    )
    val legacyModels = listOf(
        ModelPreset("gpt-4o-mini-tts", "GPT-4o Mini TTS", "旧 Speech 接口"),
        ModelPreset("tts-1", "TTS-1", "旧 Speech 接口 · 速度优先"),
        ModelPreset("tts-1-hd", "TTS-1 HD", "旧 Speech 接口 · 质量优先"),
    )
    private val realtimeVoices = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")
    private val speechVoices = realtimeVoices + listOf("fable", "nova", "onyx")
    private val oldVoices = listOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer")
    private fun name(model: String) = model.substringAfterLast('/').lowercase()
    fun realtime(model: String) = Regex("gpt-realtime-2\\.1(?:-mini)?(?:-\\d{4}-\\d{2}-\\d{2})?").matches(name(model))
    fun knownSpeech(model: String) = Regex("(?:gpt-4o-mini-tts|tts-1(?:-hd)?)(?:-\\d{4}-\\d{2}-\\d{2})?").matches(name(model))
    fun defaultModel(kind: ProviderKind) = if (kind in setOf(ProviderKind.OPENAI, ProviderKind.CUSTOM)) DEFAULT_MODEL else ""
    fun voices(model: String): List<String> = when {
        realtime(model) -> realtimeVoices
        name(model).startsWith("gpt-4o-mini-tts") -> speechVoices
        Regex("tts-1(?:-hd)?(?:-\\d{4}-\\d{2}-\\d{2})?").matches(name(model)) -> oldVoices
        else -> emptyList()
    }
    fun defaultVoice(model: String) = if (DEFAULT_VOICE in voices(model)) DEFAULT_VOICE else "alloy"
    fun compatibleVoice(model: String, voice: String): String {
        val supported = voices(model)
        return if (supported.isNotEmpty() && voice in speechVoices && voice !in supported) defaultVoice(model) else voice
    }
    fun supportedModels(models: List<RemoteModel>) = models.filter {
        (realtime(it.id) || knownSpeech(it.id)) && (it.outputModalities == null || "audio" in it.outputModalities)
    }.distinctBy { it.id }.sortedWith(compareBy<RemoteModel> {
        when (it.id) { DEFAULT_MODEL -> 0; "gpt-realtime-2.1" -> 1; else -> 2 }
    }.thenBy { it.id })
}
