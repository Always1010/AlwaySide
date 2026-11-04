package com.alwayside.android

/** Limits follow the mono PCM WAV format; zero minutes disables the time limit. */
internal object RecordingLimits {
    const val SAMPLE_RATE = 48_000
    const val DEFAULT_MINUTES = 10
    const val MAX_PCM_BYTES = Int.MAX_VALUE - 45 // Even sample count, including WAV overhead.
    const val MAX_MINUTES = MAX_PCM_BYTES / (SAMPLE_RATE * 2 * 60)
    const val UPLOAD_PCM_BYTES = 20 * 1024 * 1024 - 44

    fun pcmBytes(minutes: Int): Int {
        require(minutes in 0..MAX_MINUTES)
        return if (minutes == 0) MAX_PCM_BYTES else minutes * 60 * SAMPLE_RATE * 2
    }
}
