package com.alwayside.android

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** A local player whose lifetime is bound to its dialog and owning chat view. */
internal class AudioPlaybackDialog(private val context: Context, private val file: File) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var seeking = false
    private val time = context.label("正在读取音频…", 12f, true)
    private val seek = SeekBar(context).apply { isEnabled = false; contentDescription = "回听进度" }
    private val play = context.iconButton(R.drawable.ic_line_play, "播放音频") { toggle() }.apply { isEnabled = false }
    private val dialog = MaterialAlertDialogBuilder(context).setTitle("回听原始音频")
        .setView(context.column().apply {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(play)
                addView(time, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(seek, LinearLayout.LayoutParams(-1, -2))
        }).setPositiveButton("返回对话", null).create()
    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (prepared) {
                if (!seeking) seek.progress = current.currentPosition
                updateTime(if (seeking) seek.progress else current.currentPosition, current.duration)
            }
            handler.postDelayed(this, 250)
        }
    }

    fun show() {
        dialog.setOnDismissListener { release() }
        dialog.show()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                if (prepared) player?.seekTo(bar.progress)
                seeking = false
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && prepared) updateTime(progress, player?.duration ?: 0)
            }
        })
        try {
            val next = MediaPlayer()
            player = next
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (player === it) {
                    prepared = true; seek.max = it.duration; seek.isEnabled = true; play.isEnabled = true
                    it.start(); updatePlay(); handler.post(ticker)
                }
            }
            next.setOnCompletionListener { if (player === it) { seek.progress = it.duration; updatePlay() } }
            next.setOnErrorListener { _, _, _ -> failed(); true }
            next.prepareAsync()
        } catch (_: Exception) { failed() }
    }

    fun dismiss() { dialog.dismiss(); release() }

    private fun toggle() {
        val current = player ?: return
        if (!prepared) return
        try {
            if (current.isPlaying) current.pause() else {
                if (current.currentPosition >= current.duration) current.seekTo(0)
                current.start()
            }
            updatePlay()
        } catch (_: Exception) { failed() }
    }

    private fun updatePlay() {
        val playing = player?.isPlaying == true
        play.icon = context.prototypeIcon(if (playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
        play.accessible(if (playing) "暂停回听" else "播放音频")
    }

    private fun updateTime(position: Int, duration: Int) {
        fun formatted(ms: Int): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        time.text = "${formatted(position)} / ${formatted(duration)}"
    }

    private fun failed() {
        Toast.makeText(context, "无法回听，音频可能已被删除或暂不可读", Toast.LENGTH_LONG).show()
        dismiss()
    }

    private fun release() {
        handler.removeCallbacks(ticker)
        prepared = false
        player?.release(); player = null
    }
}
