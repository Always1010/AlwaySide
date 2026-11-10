package com.alwayside.android

internal enum class AudioRepeat { NONE, ONE, ALL }

/** Shared by transport and menus; point-reading never participates in original-audio repeat. */
internal object AudioPlaybackMode {
    fun options(list: Boolean) = if (list) listOf(AudioRepeat.NONE, AudioRepeat.ALL, AudioRepeat.ONE)
        else listOf(AudioRepeat.NONE, AudioRepeat.ONE)

    fun title(mode: AudioRepeat, list: Boolean): String = when (mode) {
        AudioRepeat.NONE -> if (list) "顺序播放" else "播完停止"
        AudioRepeat.ONE -> "单曲循环"
        AudioRepeat.ALL -> "列表循环"
    }

    fun restore(value: String?, list: Boolean): AudioRepeat =
        options(list).find { it.name == value } ?: AudioRepeat.NONE
}
