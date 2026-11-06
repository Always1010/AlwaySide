package com.alwayside.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

internal fun android.content.Context.bubbleSlideThreshold(config: InteractionConfig): Float =
    maxOf(dp(config.slideDistanceDp).toFloat(), ViewConfiguration.get(this).scaledTouchSlop * 1.5f)

/** Owns one pointer stream. Both the real bubble and practice use these exact decisions. */
internal class BubbleTouchController(
    private val view: View,
    private val visual: AssistantBubbleView,
    private val config: () -> InteractionConfig,
    private val onLeft: () -> Boolean,
    private val onGesture: (BubbleGesture) -> Unit,
    private val onMove: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    private val onMoveEnd: () -> Unit = {},
    private val onArmedChanged: (Boolean) -> Unit = {},
    private val onPreview: (BubbleGestureStateMachine.Preview) -> Unit = {},
    private val canSelect: (BubbleGesture) -> Boolean = { true },
    private val onCenterCancelled: () -> Unit = {},
    private val onCancel: () -> Unit = {},
    private val hapticFeedback: () -> Unit = { BubbleHaptics.longPress(view) },
) : View.OnTouchListener, View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var machine: BubbleGestureStateMachine? = null
    private var tracking = false
    private var disposed = false
    private var armed = false
    private var hapticSent = false
    private var framePending = false
    private var pointerId = -1

    /** The down-time snapshot remains available while onGesture is dispatched. */
    var touchConfig: InteractionConfig? = null
        private set

    private val deadline = Runnable { advance(SystemClock.uptimeMillis()) }
    private val frame = object : Runnable {
        override fun run() {
            framePending = false
            if (tracking && !disposed) advance(SystemClock.uptimeMillis())
        }
    }

    init {
        view.setOnTouchListener(this)
        view.addOnAttachStateChangeListener(this)
    }

    // SINGLE joins the owner's double/triple-tap sequence instead of invoking a second click.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (disposed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (event.pointerCount != 1) return true
                tracking = true; pointerId = event.getPointerId(0); hapticSent = false
                view.parent?.requestDisallowInterceptTouchEvent(true)
                val snapshot = config().also { touchConfig = it }
                val systemHold = ViewConfiguration.getLongPressTimeout().toLong()
                machine = BubbleGestureStateMachine(snapshot.longPressSlide,
                    if (snapshot.longPressSlide) maxOf(snapshot.longPressMillis.toLong(), systemHold) else systemHold,
                    ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(),
                    view.context.bubbleSlideThreshold(snapshot), onLeft()).apply {
                    down(event.rawX, event.rawY, event.eventTime)
                }
                view.isPressed = true
                updateFeedback(event.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancel()
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (event.pointerCount != 1 || event.getPointerId(0) != pointerId) { cancel(); return true }
                val state = machine ?: return true
                // Historical samples decide whether movement preceded the hold deadline even
                // if the UI thread receives this event after a batch of input has accumulated.
                for (index in 0 until event.historySize) {
                    if (!tracking) break
                    val rawX = event.getHistoricalX(index) + event.rawX - event.x
                    val rawY = event.getHistoricalY(index) + event.rawY - event.y
                    processMove(state, rawX, rawY, event.getHistoricalEventTime(index))
                }
                if (tracking) processMove(state, event.rawX, event.rawY, event.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                val state = machine ?: return true
                // A device may report the final displacement only with UP. Resolve it before
                // release so a last-sample slide cannot turn into the default hold action.
                processMove(state, event.rawX, event.rawY, event.eventTime)
                if (!tracking) return true
                val moved = state.phase == BubbleGestureStateMachine.Phase.MOVING
                val cancelledRelease = state.phase == BubbleGestureStateMachine.Phase.ARMED && state.returnedToCenter
                val previous = state.phase
                val gesture = state.up(event.eventTime)
                notifyReachedArm(state, previous, event.eventTime)
                finish()
                if (moved) onMoveEnd()
                else gesture?.let(onGesture)
                if (cancelledRelease) onCenterCancelled()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> cancel()
        }
        return true
    }

    private fun processMove(state: BubbleGestureStateMachine, x: Float, y: Float, now: Long) {
        val previous = state.phase
        val previousSelection = state.selected
        val gesture = state.move(x, y, now)
        notifyReachedArm(state, previous, now)
        if (state.phase == BubbleGestureStateMachine.Phase.MOVING) {
            clearCallbacks(); setArmed(false); visual.resetGestureFeedback(); view.isPressed = false
            onMove(state.dx, state.dy)
        } else {
            updateFeedback(now)
            if (tracking && state.phase == BubbleGestureStateMachine.Phase.ARMED) {
                onPreview(state.preview)
                state.selected?.takeIf { it != previousSelection && canSelect(it) }?.let {
                    BubbleHaptics.selection(view)
                }
            }
        }
        if (state.phase == BubbleGestureStateMachine.Phase.FINISHED) {
            finish()
            gesture?.let(onGesture)
        }
    }

    private fun advance(now: Long) {
        if (!tracking || disposed) return
        val state = machine ?: return
        val previous = state.phase
        val gesture = state.advance(now)
        notifyReachedArm(state, previous, now)
        if (state.phase == BubbleGestureStateMachine.Phase.FINISHED) {
            finish()
            gesture?.let(onGesture)
        } else updateFeedback(now)
    }

    private fun updateFeedback(now: Long) {
        val state = machine ?: return
        if (state.phase == BubbleGestureStateMachine.Phase.ARMED && !armed) {
            setArmed(true)
        }
        visual.setGestureFeedback(state.phase, state.progress(now), state.dx, state.dy, state.slideEnabled, state.cancelled)
        handler.removeCallbacks(deadline)
        state.nextDeadline?.let { handler.postAtTime(deadline, it) }
        if (ValueAnimator.areAnimatorsEnabled() && !framePending &&
            (state.phase == BubbleGestureStateMachine.Phase.PRESSING ||
                (state.phase == BubbleGestureStateMachine.Phase.ARMED && now < state.armedAt + 180))) {
            framePending = true
            view.postOnAnimation(frame)
        }
    }

    private fun notifyReachedArm(state: BubbleGestureStateMachine,
        previous: BubbleGestureStateMachine.Phase, now: Long) {
        if (BubbleHaptics.reachedHold(state, previous, now, hapticSent)) {
            hapticSent = true
            hapticFeedback()
        }
    }

    private fun setArmed(value: Boolean) {
        if (armed != value) { armed = value; onArmedChanged(value) }
    }

    private fun clearCallbacks() {
        handler.removeCallbacks(deadline); view.removeCallbacks(frame); framePending = false
    }

    private fun finish() {
        tracking = false; pointerId = -1
        view.parent?.requestDisallowInterceptTouchEvent(false)
        clearCallbacks(); setArmed(false); visual.resetGestureFeedback(); view.isPressed = false
    }

    fun cancel() {
        val wasTracking = tracking
        machine?.cancel()
        finish()
        if (wasTracking) onCancel()
    }

    fun dispose() {
        if (disposed) return
        cancel(); disposed = true
        view.setOnTouchListener(null); view.removeOnAttachStateChangeListener(this)
    }

    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) = cancel()
}
