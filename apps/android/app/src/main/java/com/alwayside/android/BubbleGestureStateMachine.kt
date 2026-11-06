package com.alwayside.android

import kotlin.math.abs

/** Event-time decisions shared by the overlay and practice, without Android or side effects. */
internal class BubbleGestureStateMachine(
    val slideEnabled: Boolean,
    val holdMillis: Long,
    val windowMillis: Long,
    private val touchSlop: Float,
    private val slideSlop: Float,
    private val ballOnLeft: Boolean,
) {
    enum class Phase { IDLE, PRESSING, MOVING, ARMED, FINISHED }
    var phase = Phase.IDLE
        private set
    var startedAt = 0L
        private set
    var dx = 0f
        private set
    var dy = 0f
        private set
    private var x = 0f
    private var y = 0f
    val armedAt get() = startedAt + holdMillis
    val nextDeadline: Long? get() = when (phase) {
        Phase.PRESSING -> armedAt
        Phase.ARMED -> armedAt + windowMillis
        else -> null
    }

    fun down(x: Float, y: Float, now: Long) {
        this.x = x; this.y = y; dx = 0f; dy = 0f; startedAt = now; phase = Phase.PRESSING
    }

    fun advance(now: Long): BubbleGesture? {
        if (phase == Phase.PRESSING && now >= armedAt) {
            if (!slideEnabled) return finish(BubbleGesture.HOLD)
            phase = Phase.ARMED
        }
        if (phase == Phase.ARMED && now >= armedAt + windowMillis) return finish(BubbleGesture.HOLD)
        return null
    }

    fun move(x: Float, y: Float, now: Long): BubbleGesture? {
        if (phase == Phase.FINISHED || phase == Phase.IDLE) return null
        dx = x - this.x; dy = y - this.y
        if ((phase == Phase.PRESSING || phase == Phase.ARMED) && now < armedAt &&
            (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            phase = Phase.MOVING
        }
        advance(now)?.let { return it }
        if (phase == Phase.ARMED && maxOf(abs(dx), abs(dy)) >= slideSlop) {
            val direction = if (abs(dy) >= abs(dx)) {
                if (dy < 0) BubbleGesture.UP else BubbleGesture.DOWN
            } else if ((ballOnLeft && dx > 0) || (!ballOnLeft && dx < 0)) BubbleGesture.INWARD else null
            // Outward movement consumes the touch too, never falling back to a long press.
            return finish(direction)
        }
        return null
    }

    fun up(now: Long): BubbleGesture? {
        advance(now)?.let { return it }
        return when (phase) {
            Phase.PRESSING -> finish(BubbleGesture.SINGLE)
            Phase.ARMED -> finish(BubbleGesture.HOLD)
            else -> { phase = Phase.FINISHED; null }
        }
    }

    fun progress(now: Long): Float = when (phase) {
        Phase.PRESSING -> ((now - startedAt).toFloat() / holdMillis.coerceAtLeast(1)).coerceIn(0f, 1f)
        Phase.ARMED -> (1f - (now - armedAt).toFloat() / windowMillis.coerceAtLeast(1)).coerceIn(0f, 1f)
        else -> 0f
    }

    fun cancel() { phase = Phase.FINISHED }
    private fun finish(gesture: BubbleGesture?): BubbleGesture? { phase = Phase.FINISHED; return gesture }
}
