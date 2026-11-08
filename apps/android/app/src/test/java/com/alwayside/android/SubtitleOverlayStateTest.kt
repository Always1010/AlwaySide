package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SubtitleOverlayStateTest {
    @Test fun automaticCaptionsWaitForPlaybackAndStayVisibleWhenPaused() {
        val state = SubtitleOverlayState()
        state.playback(1, playing = false)
        assertFalse(state.visible())
        state.restore(activate = false) // Enabling the setting does not start a paused preview.
        assertFalse(state.visible())
        state.playback(1, playing = true)
        assertTrue(state.visible())
        state.playback(1, playing = false)
        assertTrue(state.visible())
        state.dismiss()
        state.playback(1, playing = true)
        assertFalse(state.visible())
        state.playback(2, playing = false)
        assertFalse(state.visible())
        state.restore() // Explicitly opening floating captions also works before playback.
        assertTrue(state.visible())
    }

    @Test fun openingPausedCaptionsPreviewsTheFirstCueWithoutChangingPlaybackTiming() {
        val cues = listOf(SubtitleCue(1, 1500, 2500, "First"), SubtitleCue(2, 6000, 7000, "Next"))
        assertEquals(0, SubtitleOverlayState.displayIndex(cues, 0, playing = false))
        assertEquals(-1, SubtitleOverlayState.displayIndex(cues, 0, playing = true))
        assertEquals(-1, SubtitleOverlayState.displayIndex(cues, 1000, playing = false))
        assertEquals(0, SubtitleOverlayState.displayIndex(cues, 4000, playing = false))
        assertEquals(-1, SubtitleOverlayState.displayIndex(cues, 4000, playing = true))
        assertEquals(1, SubtitleOverlayState.displayIndex(cues, 6000, playing = true))
        assertEquals(-1, SubtitleOverlayState.displayIndex(emptyList(), 0, playing = false))
    }

    private fun SubtitleOverlayState.visible(owner: String? = "audio", enabled: Boolean = true,
        captions: Boolean = true, original: Boolean = true, screen: Boolean = true) =
        shouldShow(owner, enabled, captions, original, screen)

    @Test fun closeOnlySuppressesThisPlaybackSessionAndReplayingTheSameRecordingRestoresCaptions() {
        val state = SubtitleOverlayState()
        state.playback(1, playing = true)
        assertTrue(state.visible())
        state.dismiss()
        // Position updates, pause/resume and a panel handoff retain the same session.
        repeat(3) { state.playback(1, playing = it % 2 == 0); assertFalse(state.visible()) }
        val panel = Any()
        state.panel(panel, "audio", true); state.panel(panel, "audio", false)
        assertFalse(state.visible())
        state.playback(2, playing = true)
        assertTrue(state.visible())
        state.dismiss(); state.restore()
        assertTrue(state.visible())
    }

    @Test fun missingCaptionsDisabledPreferenceReadingAudioAndLockScreenNeverShowAWindow() {
        val state = SubtitleOverlayState()
        state.playback(1, playing = true)
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
        state.playback(1, playing = true)
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
