package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackVisibilityTest {
    @Test fun chatPlaybackBarHostsTheNextOriginalRecordingWithoutExtendingPointReading() {
        val visibility = PlaybackVisibility()
        val chat = Any()
        visibility.update(chat, "first", true)
        visibility.updatePlaybackHost(chat, true)
        assertFalse(visibility.shouldStop("second"))
        assertTrue(visibility.shouldStop("second", allowLibrary = false))
        visibility.update(chat, null, false)
        visibility.updatePlaybackHost(chat, false)
        assertTrue(visibility.shouldStop("second"))
        visibility.backgroundPlayback = true
        assertFalse(visibility.shouldStop("second"))
    }
    @Test fun foregroundLibraryHostsPlaybackUntilItLeavesTheScreen() {
        val visibility = PlaybackVisibility()
        val library = Any()
        visibility.updateLibrary(library, true)
        assertFalse(visibility.shouldStop("first"))
        assertFalse(visibility.shouldStop("second"))
        // Browsing recordings does not keep a hidden conversation's point-reading task alive.
        assertTrue(visibility.shouldStop("second", allowLibrary = false))
        visibility.updateLibrary(library, false)
        assertTrue(visibility.shouldStop("second"))
        visibility.backgroundPlayback = true
        assertFalse(visibility.shouldStop("second"))
    }

    @Test fun pageHandoffSurvivesDetachGapAndOnlyKeepsAudioForItsDestination() {
        val visibility = PlaybackVisibility()
        val chat = Any(); val library = Any()
        visibility.update(chat, "audio", true)
        visibility.handoff {
            visibility.update(chat, null, false)
            assertFalse(visibility.shouldStop("audio"))
            visibility.updateLibrary(library, true)
        }
        assertFalse(visibility.shouldStop("audio"))
        visibility.handoff {
            visibility.updateLibrary(library, false)
            assertFalse(visibility.shouldStop("audio"))
            visibility.update(chat, "audio", true)
        }
        assertFalse(visibility.shouldStop("audio"))
        visibility.handoff { visibility.update(chat, null, false) }
        assertTrue(visibility.shouldStop("audio"))
    }

    @Test fun failedHandoffDoesNotLeavePlaybackAllowedInBackground() {
        val visibility = PlaybackVisibility()
        runCatching { visibility.handoff { throw IllegalStateException("page failed") } }
        assertTrue(visibility.shouldStop("audio"))
    }

    @Test fun defaultStopsWhenThePlayingConversationIsLeft() {
        val visibility = PlaybackVisibility()
        val chat = Any()
        visibility.update(chat, "audio", true)
        assertFalse(visibility.shouldStop("audio"))
        visibility.update(chat, null, false)
        assertTrue(visibility.shouldStop("audio"))
        assertFalse(visibility.shouldStop(null))
    }

    @Test fun switchingToAnotherChatDoesNotKeepTheOldAudioAlive() {
        val visibility = PlaybackVisibility()
        val chat = Any()
        visibility.update(chat, "first", true)
        visibility.update(chat, "second", true)
        assertTrue(visibility.shouldStop("first"))
        assertFalse(visibility.shouldStop("second"))
    }

    @Test fun backgroundPlaybackSurvivesAllViewsClosingAndStopsWhenDisabled() {
        val visibility = PlaybackVisibility(backgroundPlayback = true)
        val chat = Any()
        visibility.update(chat, "audio", true)
        visibility.update(chat, null, false)
        assertFalse(visibility.shouldStop("audio"))
        visibility.backgroundPlayback = false
        assertTrue(visibility.shouldStop("audio"))
    }

    @Test fun closingAnUnrelatedOverlayDoesNotStopTheVisibleChat() {
        val visibility = PlaybackVisibility()
        val chat = Any()
        val overlay = Any()
        visibility.update(chat, "audio", true)
        visibility.update(overlay, "photo", true)
        visibility.update(overlay, null, false)
        assertFalse(visibility.shouldStop("audio"))
    }

    @Test fun handoffKeepsPlaybackWhileEitherOwnerShowsTheConversation() {
        val visibility = PlaybackVisibility()
        val chat = Any()
        val overlay = Any()
        visibility.update(chat, "audio", true)
        visibility.update(overlay, "audio", true)
        visibility.update(chat, null, false)
        assertFalse(visibility.shouldStop("audio"))
        visibility.update(overlay, null, false)
        assertTrue(visibility.shouldStop("audio"))
    }
}
