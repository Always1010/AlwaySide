package com.alwayside.android

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Consume the native touch path so it cannot also schedule a shorter platform long-click. */
internal class HistoryRowTouch(private val view: View, private val swipe: HistorySwipeRow? = null) : View.OnTouchListener, View.OnAttachStateChangeListener {
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
                swipe?.begin()
                gesture.down(event.rawX, event.rawY, event.eventTime, swipe?.canSwipeFrom(event.rawX) == true)
                view.isPressed = true
                view.drawableHotspotChanged(event.x, event.y)
                view.postDelayed(hold, gesture.longPressMillis)
            }
            MotionEvent.ACTION_MOVE -> {
                val previous = gesture.phase
                gesture.move(event.rawX, event.rawY)
                if (gesture.phase != HistoryRowGesture.Phase.PRESS) clearHold()
                if (previous != gesture.phase && gesture.phase == HistoryRowGesture.Phase.SCROLL) swipe?.close()
                if (gesture.phase == HistoryRowGesture.Phase.SWIPE) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    swipe?.drag(gesture.dx)
                }
            }
            MotionEvent.ACTION_UP -> {
                clearHold()
                gesture.move(event.rawX, event.rawY)
                if (gesture.phase == HistoryRowGesture.Phase.SWIPE) { swipe?.drag(gesture.dx); swipe?.finish() }
                if (gesture.phase == HistoryRowGesture.Phase.SCROLL) swipe?.close()
                if (gesture.up()) view.performClick()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                swipe?.close()
                gesture.cancel(); clearHold()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun clearHold() { view.removeCallbacks(hold); view.isPressed = false }
    override fun onViewDetachedFromWindow(v: View) { gesture.cancel(); clearHold() }
    override fun onViewAttachedToWindow(v: View) = Unit
}
