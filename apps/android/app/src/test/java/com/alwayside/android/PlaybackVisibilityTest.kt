package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackVisibilityTest {
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
