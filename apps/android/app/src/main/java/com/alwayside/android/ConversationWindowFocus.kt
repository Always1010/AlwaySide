package com.alwayside.android

/** A pinned conversation stays visible without keeping the underlying app's key focus. */
internal class ConversationWindowFocus(pinned: Boolean) {
    var pinned = pinned
        private set
    var focusable = !pinned
        private set

    fun setPinned(value: Boolean) {
        if (pinned == value) return
        pinned = value
        focusable = !value
    }

    fun interact() { focusable = true }

    fun leave() { focusable = !pinned }
}
