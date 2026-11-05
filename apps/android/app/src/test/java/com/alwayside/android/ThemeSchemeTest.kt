package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ThemeSchemeTest {
    @Test fun defaultSchemeIsBlueAndExplicitBlackRemainsAvailable() {
        assertEquals(ThemeScheme.BLUE,ThemeScheme.parse(null))
        assertEquals(ThemeScheme.BLUE,ThemeScheme.parse("obsolete"))
        assertEquals(ThemeScheme.BLACK,ThemeScheme.parse("black"))
        assertEquals(ThemeScheme.BLUE,ThemeScheme.parse("blue"))
        assertEquals(0xFF191C20.toInt(),ThemeScheme.BLACK.accent(false))
        assertEquals(0xFFFFFFFF.toInt(),ThemeScheme.BLACK.onAccent(false))
        assertNotEquals(ThemeScheme.BLACK.accent(false),ThemeScheme.BLACK.accent(true))
    }
    @Test fun bothSchemesHaveSufficientNormalActionContrast() {
        fun luminance(color: Int): Double {
            val channels=listOf(16,8,0).map { ((color ushr it) and 255)/255.0 }
                .map { if(it<=0.04045)it/12.92 else Math.pow((it+0.055)/1.055,2.4) }
            return channels[0]*0.2126+channels[1]*0.7152+channels[2]*0.0722
        }
        ThemeScheme.entries.forEach { scheme -> listOf(false,true).forEach { night ->
            val values=listOf(luminance(scheme.accent(night)),luminance(scheme.onAccent(night))).sorted()
            assertTrue("$scheme/$night",(values[1]+0.05)/(values[0]+0.05)>=4.5)
        } }
    }
}
