package com.alwayside.android

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

/** Keep the existing content, but give its TextViews a real Window/DecorView for ActionMode. */
@SuppressLint("ViewConstructor")
internal class AssistantConversationWindow(context: Context, private val outside: () -> Unit) : FrameLayout(context) {
    private var textMode: ActionMode? = null
    val hasTextActionMode: Boolean get() = textMode != null
    private var shadowElevation = 0f
    private val host = object : Dialog(context, R.style.ThemeOverlay_AlwaySide_ConversationWindow) {
        override fun onStart() {
            super.onStart()
            window!!.setElevation(shadowElevation)
        }

        override fun onActionModeStarted(mode: ActionMode) {
            super.onActionModeStarted(mode)
            textMode = mode
        }

        override fun onActionModeFinished(mode: ActionMode) {
            super.onActionModeFinished(mode)
            if (textMode === mode) textMode = null
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // The selection handles and floating toolbar can extend beyond the window.
                // Their touches must not trigger the conversation's outside-to-hide action.
                if (textMode == null) outside()
                return false
            }
            return super.dispatchTouchEvent(event)
        }

        override fun cancel() { outside() }
    }.apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setContentView(this@AssistantConversationWindow, ViewGroup.LayoutParams(-1, -1))
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            decorView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    this@AssistantConversationWindow.background?.getOutline(outline)
                }
            }
        }
    }

    fun attach(layout: WindowManager.LayoutParams) {
        if (elevation != 0f) { shadowElevation = elevation; elevation = 0f }
        host.window!!.attributes = layout
        host.show()
    }

    fun update(layout: WindowManager.LayoutParams) {
        host.window!!.attributes = layout
    }

    /** Dismiss removes the decor, toolbar and handle surfaces synchronously before capture. */
    fun detach() {
        textMode?.finish()
        host.dismiss()
    }
}
