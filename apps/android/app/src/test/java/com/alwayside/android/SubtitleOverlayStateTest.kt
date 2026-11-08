package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleOverlayStateTest {
    private fun SubtitleOverlayState.visible(owner: String? = "audio", enabled: Boolean = true,
        captions: Boolean = true, original: Boolean = true, screen: Boolean = true) =
        shouldShow(owner, enabled, captions, original, screen)

    @Test fun closeOnlySuppressesThisPlaybackSessionAndReplayingTheSameRecordingRestoresCaptions() {
        val state = SubtitleOverlayState()
        state.playback(1)
        assertTrue(state.visible())
        state.dismiss()
        // Position updates, pause/resume and a panel handoff retain the same session.
        repeat(3) { state.playback(1); assertFalse(state.visible()) }
        val panel = Any()
        state.panel(panel, "audio", true); state.panel(panel, "audio", false)
        assertFalse(state.visible())
        state.playback(2)
        assertTrue(state.visible())
        state.dismiss(); state.restore()
        assertTrue(state.visible())
    }

    @Test fun missingCaptionsDisabledPreferenceReadingAudioAndLockScreenNeverShowAWindow() {
        val state = SubtitleOverlayState()
        state.playback(1)
        assertFalse(state.visible(owner = null))
        assertFalse(state.visible(enabled = false))
        assertFalse(state.visible(captions = false))
        assertFalse(state.visible(original = false))
        assertFalse(state.visible(screen = false))
        // Unlocking or enabling the setting restores presentation without a new playback.
        assertTrue(state.visible())
    }

    @Test fun onlyTheMatchingVisibleFullPanelSuppressesTheFloatingCaptions() {
        val state = SubtitleOverlayState()
        val first = Any(); val second = Any()
        state.panel(first, "other", true)
        assertTrue(state.visible())
        state.panel(second, "audio", true)
        assertFalse(state.visible())
        // Backgrounding the full panel, or dismissing it, exposes the floating captions.
        state.panel(second, null, false)
        assertTrue(state.visible())
        state.panel(second, "audio", true)
        assertFalse(state.visible())
    }

    @Test fun languagesCanBeCombinedButTheLastSelectionCannotBeRemovedAndGapsClearTheText() {
        val both = SubtitleDisplay.BILINGUAL
        val chinese = SubtitleDisplay.CHINESE
        val english = SubtitleDisplay.ENGLISH
        assertEquals(english, SubtitleOverlayState.toggleLanguage(both, chinese))
        assertEquals(chinese, SubtitleOverlayState.toggleLanguage(both, english))
        assertEquals(chinese, SubtitleOverlayState.toggleLanguage(chinese, chinese))
        assertEquals(english, SubtitleOverlayState.toggleLanguage(english, english))
        assertEquals(both, SubtitleOverlayState.toggleLanguage(chinese, english))
        assertEquals(both, SubtitleOverlayState.toggleLanguage(english, chinese))
        val bundle = SubtitleBundle("a".repeat(64), SubtitleMethod.WHISPER, 2000, "b".repeat(64),
            cues = listOf(SubtitleCue(1, 100, 1000, "Hello")), translations = mapOf(1 to "你好"))
        assertEquals("你好", SubtitleOverlayState.text(bundle, 0, chinese))
        assertEquals("Hello", SubtitleOverlayState.text(bundle, 0, english))
        assertEquals("你好\nHello", SubtitleOverlayState.text(bundle, 0, both))
        assertEquals("", SubtitleOverlayState.text(bundle, -1, both))
        assertEquals("", SubtitleOverlayState.text(bundle, 1, both))
    }
}
