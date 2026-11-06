package com.alwayside.android

import kotlin.math.abs

/** Event-time decisions shared by the overlay and practice, without Android or side effects. */
internal class BubbleGestureStateMachine(
    val slideEnabled: Boolean,
    val holdMillis: Long,
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
    var selected: BubbleGesture? = null
        private set
    private var leftCenter = false
    private var hadSelection = false
    val cancelled get() = leftCenter && selected == null
    val returnedToCenter get() = hadSelection && cancelled && maxOf(abs(dx), abs(dy)) <= slideSlop * .5f
    data class Preview(val direction: BubbleGesture?, val cancelled: Boolean, val dx: Float, val dy: Float)
    val preview get() = Preview(selected, cancelled, dx, dy)
    val armedAt get() = startedAt + holdMillis
    val nextDeadline: Long? get() = when (phase) {
        Phase.PRESSING -> armedAt
        else -> null
    }

    fun down(x: Float, y: Float, now: Long) {
        this.x = x; this.y = y; dx = 0f; dy = 0f; startedAt = now; phase = Phase.PRESSING
        selected = null; leftCenter = false; hadSelection = false
    }

    fun advance(now: Long): BubbleGesture? {
        if (phase == Phase.PRESSING && now >= armedAt) {
            if (!slideEnabled) return finish(BubbleGesture.HOLD)
            phase = Phase.ARMED
        }
        return null
    }

    fun move(x: Float, y: Float, now: Long): BubbleGesture? {
        if (phase == Phase.FINISHED || phase == Phase.IDLE) return null
        dx = x - this.x; dy = y - this.y
        if ((phase == Phase.PRESSING || phase == Phase.ARMED) && now < armedAt &&
            (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            phase = Phase.MOVING
            selected = null; leftCenter = false; hadSelection = false
        }
        advance(now)?.let { return it }
        if (phase == Phase.ARMED) {
            val distance = maxOf(abs(dx), abs(dy))
            if (distance <= slideSlop * .5f) {
                selected = null
                return null
            }
            if (distance < slideSlop) return null
            leftCenter = true
            val next = if (abs(dy) >= abs(dx)) {
                if (dy < 0) BubbleGesture.UP else BubbleGesture.DOWN
            } else if ((ballOnLeft && dx > 0) || (!ballOnLeft && dx < 0)) BubbleGesture.INWARD else null
            val previousAmount = when (selected) {
                BubbleGesture.UP -> -dy
                BubbleGesture.DOWN -> dy
                BubbleGesture.INWARD -> if (ballOnLeft) dx else -dx
                else -> 0f
            }
            val nextAmount = if (next == BubbleGesture.INWARD) abs(dx) else abs(dy)
            // Keep the selected sector stable around diagonals, but let outward motion cancel.
            if (next == null || selected == null || previousAmount <= 0 || nextAmount >= previousAmount * 1.25f) {
                selected = next
                if (next != null) hadSelection = true
            }
        }
        return null
    }

    fun up(now: Long): BubbleGesture? {
        advance(now)?.let { return it }
        return when (phase) {
            Phase.PRESSING -> finish(BubbleGesture.SINGLE)
            Phase.ARMED -> finish(selected ?: if (leftCenter) null else BubbleGesture.HOLD)
            else -> { phase = Phase.FINISHED; null }
        }
    }

    fun progress(now: Long): Float = when (phase) {
        Phase.PRESSING -> ((now - startedAt).toFloat() / holdMillis.coerceAtLeast(1)).coerceIn(0f, 1f)
        Phase.ARMED -> 1f
        else -> 0f
    }

    fun cancel() { phase = Phase.FINISHED }
    private fun finish(gesture: BubbleGesture?): BubbleGesture? { phase = Phase.FINISHED; return gesture }
}
