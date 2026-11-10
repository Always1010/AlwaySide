package com.alwayside.android

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.util.Locale

internal object AudioPlaybackMenus {
    fun modeIcon(playback: ChatAudioPlayback) = when (playback.repeatMode) {
        AudioRepeat.NONE -> R.drawable.ic_line_play_once
        AudioRepeat.ONE -> R.drawable.ic_line_repeat_one
        AudioRepeat.ALL -> R.drawable.ic_line_repeat
    }

    fun mode(anchor: View, playback: ChatAudioPlayback) {
        PopupMenu(anchor.context, anchor).apply {
            AudioPlaybackMode.options(playback.listPlayback).forEach { value ->
                menu.add(AudioPlaybackMode.title(value, playback.listPlayback)).apply {
                    isCheckable = true; isChecked = playback.repeatMode == value
                    setOnMenuItemClickListener { playback.setRepeatMode(value); true }
                }
            }
            show()
        }
    }

    fun speed(anchor: View, playback: ChatAudioPlayback) {
        PopupMenu(anchor.context, anchor).apply {
            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                menu.add("${value}×").apply {
                    isCheckable = true; isChecked = playback.originalSpeed == value
                    setOnMenuItemClickListener { playback.setOriginalSpeed(value); true }
                }
            }
            show()
        }
    }

    fun time(ms: Int): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
}

/** Original-recording controls are available even when subtitle features are disabled. */
internal class AudioPlaybackPanel(private val activity: Activity) {
    private val playback = ChatAudioPlayback.get(activity)
    private val dialog = BottomSheetDialog(activity)
    private val title = activity.label("", 18f).apply { maxLines = 2 }
    private val source = activity.label("", 12f, true)
    private val clock = activity.label("", 12f, true)
    private val total = activity.label("", 12f, true)
    private val progress = SeekBar(activity)
    private val play = activity.iconButton(R.drawable.ic_line_play, "播放", { playback.toggle() })
    private val mode = activity.iconButton(R.drawable.ic_line_play_once, "播放模式", {})
    private val speed = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        applyActionStyle(); minimumWidth = 0; minWidth = 0
        setOnClickListener { AudioPlaybackMenus.speed(this, playback) }
    }
    private val subtitles = activity.iconButton(R.drawable.ic_line_subtitles, "音频字幕", { openCurrent(true) })
    private var dragging = false
    private val changed: () -> Unit = { render() }

    fun show(): BottomSheetDialog {
        val body = activity.column().apply {
            setBackgroundColor(BACKGROUND)
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(activity.label("音频播放", 20f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(activity.iconButton(R.drawable.ic_line_close, "收起播放面板", { dialog.dismiss() }))
            })
            addView(title.apply {
                setOnClickListener { openCurrent(false) }; isFocusable = true
                accessible("打开当前录音会话")
            })
            addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
            addView(LinearLayout(activity).apply {
                setPadding(0, activity.dp(20), 0, 0)
                addView(clock, LinearLayout.LayoutParams(0, -2, 1f)); addView(total)
            })
            addView(progress, LinearLayout.LayoutParams(-1, activity.dp(40)))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(activity.iconButton(R.drawable.ic_line_rewind, "后退 5 秒", { seekRelative(-5000) }))
                play.applyIconActionStyle(primary = true); play.cornerRadius = activity.dp(24)
                addView(play)
                addView(activity.iconButton(R.drawable.ic_line_rewind, "前进 5 秒", { seekRelative(5000) }).apply { scaleX = -1f })
            })
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(speed, LinearLayout.LayoutParams(activity.dp(64), activity.dp(48)))
                mode.setOnClickListener { AudioPlaybackMenus.mode(mode, playback) }
                addView(mode); addView(subtitles)
                addView(activity.iconButton(R.drawable.ic_line_stop, "停止播放", { playback.stop() }))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        }
        progress.accessible("音频播放进度")
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) clock.text = AudioPlaybackMenus.time(value)
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                seek(bar.progress)
            }
        })
        dialog.setContentView(body)
        dialog.setOnDismissListener { playback.removeObserver(changed) }
        dialog.show()
        playback.addObserver(changed)
        return dialog
    }

    private fun render() {
        if (playback.conversationId == null || playback.readingKey.isNotEmpty()) { dialog.dismiss(); return }
        val state = playback.currentState
        title.text = playback.mediaTitle
        source.text = playback.sourceTitle + when {
            state.completed -> " · 已播完"
            state.preparing -> " · 正在读取…"
            else -> ""
        }
        total.text = AudioPlaybackMenus.time(playback.duration)
        if (!dragging) {
            progress.max = playback.duration.coerceAtLeast(1); progress.progress = playback.position
            clock.text = AudioPlaybackMenus.time(playback.position)
        }
        progress.isEnabled = !state.preparing && playback.duration > 0
        play.isEnabled = !state.preparing
        play.icon = activity.prototypeIcon(if (state.playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
        play.accessible(if (state.playing) "暂停播放" else if (state.completed) "重新播放" else "继续播放")
        speed.text = "${playback.originalSpeed}×"; speed.accessible("播放速度：${speed.text}")
        mode.icon = activity.prototypeIcon(AudioPlaybackMenus.modeIcon(playback))
        mode.accessible("播放模式：${AudioPlaybackMode.title(playback.repeatMode, playback.listPlayback)}")
        subtitles.visibility = if (SubtitlePreferences(activity).anyEnabled() ||
            InteractionPreferences(activity).load().backgroundPlaybackSubtitles ||
            !SubtitleController.get(activity).record(requireNotNull(playback.conversationId), SubtitleMethod.WHISPER).playable?.cues.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun seekRelative(delta: Int) = seek((playback.position + delta).coerceAtLeast(0))
    private fun seek(value: Int) {
        val id = playback.conversationId ?: return
        val file = playback.currentFile ?: return
        playback.seekTo(id, file, value.toLong())
    }

    private fun openCurrent(subtitles: Boolean) {
        val id = playback.conversationId ?: return
        dialog.dismiss()
        activity.startActivity(AppEntry.intent(activity, AppPage.CHAT, id, openSubtitles = subtitles))
    }
}
