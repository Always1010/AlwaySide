package com.alwayside.android

internal enum class SubtitleMethod(val id: String, val title: String, val model: String, val dollarsPerMinute: Double) {
    WHISPER("whisper", "Whisper", "whisper-1", 0.006);
}

internal enum class SubtitleDisplay(val title: String) {
    BILINGUAL("双语"), CHINESE("中文"), ENGLISH("英文"), OFF("关闭");
}

internal data class SubtitleCue(val id: Int, val startMs: Long, val endMs: Long, val text: String)

/** Only non-secret reproducibility metadata is persisted. Times always refer to the original recording. */
internal data class SubtitleBundle(
    val audioHash: String,
    val method: SubtitleMethod,
    val durationMs: Long,
    val requestSignature: String,
    val cues: List<SubtitleCue> = emptyList(),
    val translations: Map<Int, String> = emptyMap(),
    val completedParts: Int = 0,
    val totalParts: Int = 0,
    val transcriptionComplete: Boolean = false,
    val translationModel: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val bilingualComplete: Boolean get() = transcriptionComplete && cues.isNotEmpty() &&
        cues.all { !translations[it.id].isNullOrBlank() }
}

/** PCM16 mono frames at RecordingLimits.SAMPLE_RATE, excluding the 44-byte WAV header. */
internal data class SubtitleAudioPart(val startFrame: Long, val endFrame: Long) {
    val startMs: Long get() = startFrame * 1000 / RecordingLimits.SAMPLE_RATE
    val endMs: Long get() = endFrame * 1000 / RecordingLimits.SAMPLE_RATE
}
