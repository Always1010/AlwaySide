package com.alwayside.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** Host the unchanged production overlay view on the private capture stage, without overlay permission. */
internal class DocSubtitleOverlay(
    activity: Activity,
    stage: FrameLayout,
    private val owner: String,
    private val playback: ChatAudioPlayback,
    private val onOpen: () -> Unit,
) {
    private val state = SubtitleOverlayState()
    private val preferences = SubtitleOverlayPreferences(activity).apply {
        saveMode(SubtitleDisplay.BILINGUAL)
        saveTheme(SubtitleOverlayTheme.BLACK)
        saveOpacity(85)
        resetWindow()
    }
    private val host = object : WindowManager by activity.getSystemService(WindowManager::class.java) {
        override fun getMaximumWindowMetrics() = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics
        override fun getCurrentWindowMetrics() = activity.getSystemService(WindowManager::class.java).currentWindowMetrics
        private fun frame(params: ViewGroup.LayoutParams): FrameLayout.LayoutParams {
            val layout = params as WindowManager.LayoutParams
            return FrameLayout.LayoutParams(layout.width, layout.height).apply {
                gravity = layout.gravity
                leftMargin = layout.x
                topMargin = layout.y
            }
        }
        override fun addView(view: View, params: ViewGroup.LayoutParams) { stage.addView(view, frame(params)) }
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) { view.layoutParams = frame(params) }
        override fun removeView(view: View) { stage.removeView(view) }
        override fun removeViewImmediate(view: View) { stage.removeView(view) }
    }
    private val context = object : ContextWrapper(activity) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) host else super.getSystemService(name)
    }
    private var window: SubtitleOverlayWindow? = null

    fun panelVisible(visible: Boolean) {
        state.panel(this, owner, visible)
        refresh()
    }

    fun refresh() {
        val bundle = DocReplayFixtures.bundle ?: return
        state.playback(1L, playback.currentState.playing)
        val visible = state.shouldShow(owner, enabled = true, hasSubtitles = true,
            originalAudio = playback.isActive(owner), screenAvailable = true)
        if (!visible) { close(); return }
        if (window == null) {
            window = SubtitleOverlayWindow(context, preferences,
                onClose = { state.dismiss(); refresh() }, onOpen = onOpen,
                onPlayback = { playback.toggle() }, onChanged = { refresh() },
                onUnavailable = { error("Documentation subtitle view unavailable") }).also { it.show() }
        }
        window!!.render(bundle, SubtitleOverlayState.displayIndex(bundle.cues, playback.position.toLong(),
            playback.currentState.playing), preferences.mode(), playback.currentState.playing,
            playback.position, playback.duration)
    }

    fun close() { window?.close(); window = null }
}
