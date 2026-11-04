package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AppearanceModeTest {
    @Test fun systemAndMissingPreferencesFollowEachSystemChangeWithoutAnOverride() {
        listOf("system", null, "unsupported").forEach { stored ->
            val mode = AppearanceMode.parse(stored)
            assertNull(mode.forcedNight)
            assertFalse(mode.isNight(systemNight = false))
            assertTrue(mode.isNight(systemNight = true))
            assertFalse(mode.isNight(systemNight = false))
        }
    }

    @Test fun forcedLightRemainsLightAcrossSystemChanges() {
        val mode = AppearanceMode.parse("light")
        assertEquals(false, mode.forcedNight)
        assertFalse(mode.isNight(systemNight = false))
        assertFalse(mode.isNight(systemNight = true))
    }

    @Test fun forcedDarkRemainsDarkAcrossSystemChanges() {
        val mode = AppearanceMode.parse("dark")
        assertEquals(true, mode.forcedNight)
        assertTrue(mode.isNight(systemNight = true))
        assertTrue(mode.isNight(systemNight = false))
    }
}
