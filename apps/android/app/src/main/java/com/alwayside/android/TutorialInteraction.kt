package com.alwayside.android

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Uses the same tap-burst resolver and long-press timeout as the real bubble, with local callbacks only. */
internal class TutorialBubbleTouch(private val view: View,private val onTap: (Int) -> Unit,private val onHold: () -> Unit) {
    private val taps=TapSequence()
    private var held=false
    private var dragged=false
    private var x=0f
    private var y=0f
    private val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    private val settle=Runnable {
        val count=taps.settle()
        if(view.isAttachedToWindow) {
            if(count==1)view.performClick() else if(count>1)onTap(count)
        }
    }
    private val hold=Runnable {
        if(view.isAttachedToWindow && !dragged) {
            held=true;taps.cancel();view.removeCallbacks(settle);view.isPressed=false;view.performLongClick()
        }
    }
    init {
        view.setOnClickListener { onTap(1) }
        view.setOnLongClickListener { onHold();true }
        view.setOnTouchListener { _,event ->
            if(!view.isAttachedToWindow)return@setOnTouchListener false
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held=false;dragged=false;x=event.rawX;y=event.rawY;view.isPressed=true
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    view.removeCallbacks(hold);view.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if(abs(event.rawX-x)>slop || abs(event.rawY-y)>slop) {
                    dragged=true;cancel();view.isPressed=false
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(hold);view.isPressed=false;view.parent.requestDisallowInterceptTouchEvent(false)
                    if(!held && !dragged) {
                        view.removeCallbacks(settle)
                        if(taps.tap(event.eventTime)==3)settle.run() else view.postDelayed(settle,taps.gapMillis)
                    }
                }
                MotionEvent.ACTION_CANCEL -> { cancel();view.isPressed=false;view.parent.requestDisallowInterceptTouchEvent(false) }
            }
            true
        }
        view.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View)=Unit
            override fun onViewDetachedFromWindow(v: View) { cancel() }
        })
    }
    private fun cancel() { view.removeCallbacks(hold);view.removeCallbacks(settle);taps.cancel() }
}

/** Invalid or off-screen targets must never produce a full-screen gray mask. */
internal class TutorialHighlightBounds {
    var left=0f;private set
    var top=0f;private set
    var right=0f;private set
    var bottom=0f;private set
    fun update(x: Float,y: Float,width: Float,height: Float,sceneWidth: Float,sceneHeight: Float,gap: Float): Boolean {
        if(!x.isFinite() || !y.isFinite() || width<=0 || height<=0 || sceneWidth<=0 || sceneHeight<=0 ||
            x>=sceneWidth || y>=sceneHeight || x+width<=0 || y+height<=0)return false
        left=(x-gap).coerceAtLeast(0f);top=(y-gap).coerceAtLeast(0f)
        right=(x+width+gap).coerceAtMost(sceneWidth);bottom=(y+height+gap).coerceAtMost(sceneHeight)
        return right>left && bottom>top
    }
}
