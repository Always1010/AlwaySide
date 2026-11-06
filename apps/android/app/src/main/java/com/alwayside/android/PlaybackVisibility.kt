package com.alwayside.android

/** A conversation may be shown by the app, an overlay, or both during a handoff. */
internal class PlaybackVisibility(var backgroundPlayback: Boolean = false) {
    private val owners = mutableMapOf<Any, String>()

    fun update(owner: Any, conversationId: String?, visible: Boolean) {
        if (visible && conversationId != null) owners[owner] = conversationId else owners.remove(owner)
    }

    fun shouldStop(conversationId: String?): Boolean =
        conversationId != null && !backgroundPlayback && conversationId !in owners.values
}
