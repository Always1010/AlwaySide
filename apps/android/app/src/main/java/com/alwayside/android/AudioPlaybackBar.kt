package com.alwayside.android

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.bottomsheet.BottomSheetDialog

/** A bounded, attached-only observer; the transport never retains a screen. */
internal class AudioPlaybackBar(private val activity: Activity) : LinearLayout(activity) {
    private val playback = ChatAudioPlayback.get(activity)
    private val title = activity.label("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val source = activity.label("", 11f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val play = activity.iconButton(R.drawable.ic_line_play, "播放", { playback.toggle() })
    private val next = activity.iconButton(R.drawable.ic_line_skip_next, "下一段", { playback.next() })
    private var queuePanel: BottomSheetDialog? = null
    private val queue = activity.iconButton(R.drawable.ic_line_queue, "播放队列", {
        queuePanel?.dismiss(); queuePanel = AudioQueuePanel(activity).show()
    })
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var panel: BottomSheetDialog? = null
    private var renderedPlaying: Boolean? = null
    private var renderedCompleted: Boolean? = null
    private val changed: () -> Unit = { render() }

    init {
        orientation = VERTICAL; setBackgroundColor(SURFACE); visibility = GONE
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(activity.dp(16), activity.dp(4), activity.dp(8), activity.dp(4))
            addView(LinearLayout(activity).apply {
                orientation = VERTICAL; minimumHeight = activity.dp(48); gravity = Gravity.CENTER_VERTICAL
                addView(title); addView(source, LayoutParams(-1, -2).apply { topMargin = activity.dp(3) })
                isFocusable = true; accessible("展开音频播放面板")
                background = activity.touchFeedback()
                setOnClickListener { panel?.dismiss(); panel = AudioPlaybackPanel(activity).show() }
            }, LayoutParams(0, -2, 1f))
            addView(play); addView(next); addView(queue)
        }, LayoutParams(-1, -2))
        addView(progress, LayoutParams(-1, activity.dp(3)))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); playback.addObserver(changed) }
    override fun onDetachedFromWindow() {
        panel?.dismiss(); panel = null; queuePanel?.dismiss(); queuePanel = null
        playback.removeObserver(changed); super.onDetachedFromWindow()
    }

    private fun render() {
        visibility = if (playback.conversationId != null && playback.readingKey.isEmpty()) VISIBLE else GONE
        if (visibility != VISIBLE) return
        val state = playback.currentState
        title.playbackText(playback.mediaTitle)
        source.playbackText(if (state.completed) "${playback.sourceTitle} · 已播完" else playback.sourceTitle)
        play.isEnabled = !state.preparing
        next.visibility = if (playback.listPlayback) VISIBLE else GONE
        queue.visibility = next.visibility; next.isEnabled = playback.canGoNext
        if (renderedPlaying != state.playing || renderedCompleted != state.completed) {
            renderedPlaying = state.playing; renderedCompleted = state.completed
            play.icon = activity.prototypeIcon(if (state.playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
            play.accessible(if (state.playing) "暂停播放" else if (state.completed) "重新播放" else "继续播放")
        }
        progress.progress = ((1f - (state.remaining ?: 1f)) * 1000).toInt()
    }
}
