package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** A stationary remaining-time ring, erased clockwise from twelve o'clock. */
internal class AudioPlaybackIcon(context: Context) : Drawable() {
    private val size = context.dp(32)
    private val play = context.prototypeIcon(R.drawable.ic_line_play)
    private val pause = context.prototypeIcon(R.drawable.ic_line_pause)
    private val ring = RectF(1.5f, 1.5f, 30.5f, 30.5f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND
    }
    private var tint: ColorStateList? = null
    private var playback = ChatAudioPlayback.State()

    init { updateTint(state) }

    fun update(next: ChatAudioPlayback.State) {
        if (playback == next) return
        playback = next
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 32f, bounds.height() / 32f)
        val remaining = playback.remaining
        if (remaining != null && remaining > 0f) {
            if (remaining >= 1f) canvas.drawCircle(16f, 16f, 14.5f, paint)
            else canvas.drawArc(ring, -90f + 360f * (1f - remaining), 360f * remaining, false, paint)
        }
        val icon = if (playback.playing) pause else play
        icon.setBounds(4, 4, 28, 28)
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha; play.alpha = alpha; pause.alpha = alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        play.colorFilter = colorFilter; pause.colorFilter = colorFilter
        invalidateSelf()
    }
    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateTint(state)
    }
    override fun isStateful(): Boolean = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean = updateTint(state)

    private fun updateTint(state: IntArray): Boolean {
        val color = tint?.getColorForState(state, tint?.defaultColor ?: INK) ?: INK
        if (paint.color == color) return false
        paint.color = color; play.setTint(color); pause.setTint(color)
        invalidateSelf()
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
