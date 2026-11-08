package com.alwayside.android

/** A conversation may be shown by the app, an overlay, or both during a handoff. */
internal class PlaybackVisibility(var backgroundPlayback: Boolean = false) {
    private val owners = mutableMapOf<Any, String>()
    private val libraries = mutableSetOf<Any>()
    private var handoffDepth = 0

    fun update(owner: Any, conversationId: String?, visible: Boolean) {
        if (visible && conversationId != null) owners[owner] = conversationId else owners.remove(owner)
    }

    fun updateLibrary(owner: Any, visible: Boolean) {
        if (visible) libraries.add(owner) else libraries.remove(owner)
    }

    /** Detach and attach are synchronous, but neither page owns the audio between them. */
    fun handoff(action: () -> Unit) {
        handoffDepth++
        try { action() } finally { handoffDepth-- }
    }

    fun shouldStop(conversationId: String?, allowLibrary: Boolean = true): Boolean =
        handoffDepth == 0 && conversationId != null && !backgroundPlayback &&
            (!allowLibrary || libraries.isEmpty()) && conversationId !in owners.values
}
