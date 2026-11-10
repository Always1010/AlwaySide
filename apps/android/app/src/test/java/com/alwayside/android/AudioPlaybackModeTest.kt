package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackModeTest {
    @Test fun singleAudioCannotRestoreListRepeatAndInvalidPreferencesStayStoppedAtEnd() {
        assertEquals(listOf(AudioRepeat.NONE, AudioRepeat.ONE), AudioPlaybackMode.options(false))
        assertEquals(AudioRepeat.NONE, AudioPlaybackMode.restore("ALL", false))
        assertEquals(AudioRepeat.NONE, AudioPlaybackMode.restore("invalid", true))
        assertEquals(AudioRepeat.ONE, AudioPlaybackMode.restore("ONE", false))
        assertEquals(AudioRepeat.ALL, AudioPlaybackMode.restore("ALL", true))
    }
}
