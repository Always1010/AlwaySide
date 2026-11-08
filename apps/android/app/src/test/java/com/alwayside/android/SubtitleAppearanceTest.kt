package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleAppearanceTest {
    private val system = SubtitlePalette(1, 2, 3, 4, 5, 6)
    @Test fun systemUsesAppPaletteAndPresetsKeepTheirOwnContrastingText() {
        assertSame(system, SubtitleAppearance.palette(SubtitleOverlayTheme.SYSTEM, 0, system))
        val black = SubtitleAppearance.palette(SubtitleOverlayTheme.BLACK, 0, system)
        val light = SubtitleAppearance.palette(SubtitleOverlayTheme.LIGHT, 0, system)
        assertEquals(0xFF101216.toInt(), black.background)
        assertEquals(0xFFF7F9FC.toInt(), black.text)
        assertEquals(0xFF19212C.toInt(), light.text)
    }
    @Test fun customBackgroundSelectsReadableTextAndDiscardsInputAlpha() {
        val dark = SubtitleAppearance.palette(SubtitleOverlayTheme.CUSTOM, 0x00183B35, system)
        val light = SubtitleAppearance.palette(SubtitleOverlayTheme.CUSTOM, 0x00E3EEDF, system)
        assertEquals(0xFF183B35.toInt(), dark.background)
        assertEquals(0xFFFFFFFF.toInt(), dark.text)
        assertEquals(0xFF101216.toInt(), light.text)
    }
}
