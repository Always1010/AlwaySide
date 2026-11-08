package com.alwayside.android

import kotlin.math.pow

internal enum class SubtitleOverlayTheme(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), BLACK("深黑"), WARM("暖纸"), BLUE("雾蓝"), CUSTOM("自定义");
}

internal data class SubtitlePalette(val background: Int, val text: Int, val muted: Int,
    val border: Int, val accent: Int, val selected: Int)

internal object SubtitleAppearance {
    const val DEFAULT_CUSTOM = 0xFF183B35.toInt()

    fun palette(theme: SubtitleOverlayTheme, custom: Int, system: SubtitlePalette): SubtitlePalette = when (theme) {
        SubtitleOverlayTheme.SYSTEM -> system
        SubtitleOverlayTheme.LIGHT -> colors(0xFFF7F9FC, 0xFF19212C, 0xFF535F70, 0xFFDBE1EA, 0xFF245DC1, 0xFFE5EDFF)
        SubtitleOverlayTheme.BLACK -> colors(0xFF101216, 0xFFF7F9FC, 0xFFC1C9D4, 0xFF424956, 0xFFB1CEFF, 0xFF293C59)
        SubtitleOverlayTheme.WARM -> colors(0xFFF5EBD7, 0xFF30281F, 0xFF625646, 0xFFD7C9AE, 0xFF77501F, 0xFFE8D9BB)
        SubtitleOverlayTheme.BLUE -> colors(0xFF203B55, 0xFFF5FAFF, 0xFFCCDFED, 0xFF637F98, 0xFFC0E2FF, 0xFF365874)
        SubtitleOverlayTheme.CUSTOM -> {
            val background = custom or 0xFF000000.toInt()
            val dark = luminance(background) < .179
            colors(background.toLong(), if (dark) 0xFFFFFFFF else 0xFF101216,
                if (dark) 0xFFE4E8EE else 0xFF252D38, if (dark) 0xFF68717C else 0xFF7C8792,
                if (dark) 0xFFFFFFFF else 0xFF101216, if (dark) 0xFF42504D else 0xFFD6DDD8)
        }
    }

    private fun colors(background: Long, text: Long, muted: Long, border: Long, accent: Long, selected: Long) =
        SubtitlePalette(background.toInt(), text.toInt(), muted.toInt(), border.toInt(), accent.toInt(), selected.toInt())

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
}
