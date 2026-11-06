package com.alwayside.android

/** Main-thread, process-local leases. A preview never changes the user's assistant preference. */
internal class OverlayPreviewState {
    private val owners = mutableSetOf<Any>()
    private val listeners = linkedSetOf<() -> Unit>()
    val active: Boolean get() = owners.isNotEmpty()

    fun owns(owner: Any): Boolean = owner in owners

    fun setActive(owner: Any, active: Boolean) {
        val wasActive = this.active
        if (active) owners.add(owner) else owners.remove(owner)
        if (wasActive != this.active) listeners.toList().forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}

/** The host coordinates real overlays; tutorial views retain their capability isolation. */
internal object AssistantPreview {
    val overlays = OverlayPreviewState()
    var finishRecording: (() -> Unit)? = null
}
