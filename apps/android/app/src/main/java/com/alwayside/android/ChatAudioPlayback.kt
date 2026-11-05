package com.alwayside.android

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/** Playback belongs to the chat, independently of its messages and toolbar rendering. */
internal class ChatAudioPlayback(
    private val context: Context,
    private val onChanged: (State) -> Unit,
) {
    internal data class State(
        val preparing: Boolean = false,
        val playing: Boolean = false,
        val remaining: Float? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var duration = 0
    private var state = State()
    val isActive: Boolean get() = player != null

    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!state.playing) return
            try {
                updatePosition(current, playing = true)
                handler.postDelayed(this, 50)
            } catch (_: Exception) { failed() }
        }
    }

    fun play(file: File) {
        stop()
        try {
            val next = MediaPlayer()
            player = next
            publish(State(preparing = true, remaining = 1f))
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { current ->
                if (player === current) {
                    try {
                        duration = current.duration
                        check(duration > 0)
                        current.start()
                        publish(State(playing = true, remaining = 1f))
                        handler.post(ticker)
                    } catch (_: Exception) { failed() }
                }
            }
            next.setOnCompletionListener { if (player === it) stop() }
            next.setOnErrorListener { current, _, _ ->
                if (player === current) failed()
                true
            }
            next.prepareAsync()
        } catch (_: Exception) { failed() }
    }

    fun toggle() {
        val current = player ?: return
        if (state.preparing) return
        try {
            if (state.playing) {
                current.pause()
                handler.removeCallbacks(ticker)
                updatePosition(current, playing = false)
            } else {
                current.start()
                updatePosition(current, playing = true)
                handler.post(ticker)
            }
        } catch (_: Exception) { failed() }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        val previous = player
        player = null // Ignore late callbacks from the released player.
        duration = 0
        runCatching { previous?.release() }
        publish(State())
    }

    private fun updatePosition(current: MediaPlayer, playing: Boolean) {
        val remaining = 1f - current.currentPosition.coerceIn(0, duration).toFloat() / duration
        publish(State(playing = playing, remaining = remaining))
    }

    private fun publish(next: State) {
        if (state == next) return
        state = next
        onChanged(next)
    }

    private fun failed() {
        stop()
        Toast.makeText(context, "无法回听，音频可能已被删除或暂不可读", Toast.LENGTH_LONG).show()
    }
}
