package com.alwayside.android

import kotlin.math.abs

/** One touch can become a tap, a hold, or a drag, never more than one. */
internal class HistoryRowGesture(private val slop: Float, systemLongPressMillis: Long) {
    enum class Phase { IDLE, PRESS, SCROLL, SWIPE, HOLD, CANCEL }
    val longPressMillis = maxOf(700L, systemLongPressMillis)
    var phase = Phase.IDLE
        private set
    var dx = 0f
        private set
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var allowSwipe = false

    fun down(x: Float, y: Float, time: Long, swipe: Boolean = false) {
        downX = x; downY = y; downAt = time; dx = 0f
        allowSwipe = swipe; phase = Phase.PRESS
    }

    fun move(x: Float, y: Float) {
        dx = x - downX
        if (phase != Phase.PRESS) return
        val dy = y - downY
        if (maxOf(abs(dx), abs(dy)) <= slop) return
        phase = if (allowSwipe && abs(dx) > abs(dy) * 1.35f) Phase.SWIPE else Phase.SCROLL
    }

    fun hold(time: Long): Boolean {
        if (phase != Phase.PRESS || time - downAt < longPressMillis) return false
        phase = Phase.HOLD
        return true
    }

    fun up(): Boolean = (phase == Phase.PRESS).also { phase = Phase.IDLE }
    fun cancel() { phase = Phase.CANCEL }
}
