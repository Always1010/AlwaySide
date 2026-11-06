package com.alwayside.android

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Consume the native touch path so it cannot also schedule a shorter platform long-click. */
internal class HistoryRowTouch(private val view: View) : View.OnTouchListener, View.OnAttachStateChangeListener {
    private val gesture = HistoryRowGesture(ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(),
        ViewConfiguration.getLongPressTimeout().toLong())
    private val hold = Runnable {
        if (view.isAttachedToWindow && gesture.hold(SystemClock.uptimeMillis())) {
            view.isPressed = false
            view.performLongClick()
        }
    }

    init { view.setOnTouchListener(this); view.addOnAttachStateChangeListener(this) }

    @SuppressLint("ClickableViewAccessibility") // Tap and hold dispatch the standard accessible View actions.
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.down(event.rawX, event.rawY, event.eventTime)
                view.isPressed = true
                view.drawableHotspotChanged(event.x, event.y)
                view.postDelayed(hold, gesture.longPressMillis)
            }
            MotionEvent.ACTION_MOVE -> {
                gesture.move(event.rawX, event.rawY)
                if (gesture.phase != HistoryRowGesture.Phase.PRESS) clearHold()
            }
            MotionEvent.ACTION_UP -> {
                clearHold()
                if (gesture.up()) view.performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { gesture.cancel(); clearHold() }
        }
        return true
    }

    private fun clearHold() { view.removeCallbacks(hold); view.isPressed = false }
    override fun onViewDetachedFromWindow(v: View) { gesture.cancel(); clearHold() }
    override fun onViewAttachedToWindow(v: View) = Unit
}
