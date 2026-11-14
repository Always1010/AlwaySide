package com.alwayside.android

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

/** Keep the existing content, but give its TextViews a real Window/DecorView for ActionMode. */
@SuppressLint("ViewConstructor")
internal class AssistantConversationWindow(context: Context, pinned: Boolean, private val outside: () -> Unit) : FrameLayout(context) {
    private val focus = ConversationWindowFocus(pinned)
    private var windowLayout: WindowManager.LayoutParams? = null
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
            requestInputFocus()
        }

        override fun onActionModeFinished(mode: ActionMode) {
            super.onActionModeFinished(mode)
            if (textMode === mode) textMode = null
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // Handles and the toolbar are higher subwindows; touching them does not
                // send ACTION_OUTSIDE here. A real outside touch must also end selection
                // in pinned windows, while the owner decides whether to hide the window.
                textMode?.finish()
                releaseInputFocus()
                outside()
                return false
            }
            // Acquire before child dispatch so native editors and selection see a
            // focusable window. Outside touches hand focus back without hiding it.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) requestInputFocus()
            return super.dispatchTouchEvent(event)
        }

        override fun cancel() {
            releaseInputFocus()
            outside()
        }
    }.apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setContentView(this@AssistantConversationWindow, ViewGroup.LayoutParams(-1, -1))
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // Only the content clips its rounded corners. An empty decor outline must
            // never suppress the entire window while its first frame is being built.
            setClipToOutline(false)
            decorView.setPadding(0, 0, 0, 0)
            decorView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // Drawable bounds are assigned during drawing, after the parent
                    // outline is queried. Use the decor's size for its shadow instead.
                    val radius = (this@AssistantConversationWindow.background as? GradientDrawable)?.cornerRadius ?: 0f
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
    }

    fun attach(layout: WindowManager.LayoutParams) {
        if (elevation != 0f) { shadowElevation = elevation; elevation = 0f }
        if (!focus.focusable) requestFocus()
        update(layout)
        host.show()
    }

    fun update(layout: WindowManager.LayoutParams) {
        windowLayout = layout
        // Keep the owner's geometry snapshot in sync, so drag/resize and restores
        // cannot accidentally make an inactive pinned window focusable again.
        layout.flags = if (focus.focusable) layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        host.window!!.attributes = layout
    }

    fun setPinned(pinned: Boolean) {
        if (focus.pinned == pinned) return
        focus.setPinned(pinned)
        if (focus.focusable) windowLayout?.let(::update) else releaseInputFocus()
    }

    fun requestInputFocus() {
        if (focus.focusable) return
        focus.interact()
        windowLayout?.let(::update)
    }

    fun releaseInputFocus() {
        focus.leave()
        if (!focus.focusable) {
            textMode?.finish()
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
            requestFocus()
            windowLayout?.let(::update)
        }
    }

    /** Dismiss removes the decor, toolbar and handle surfaces synchronously before capture. */
    fun detach() {
        textMode?.finish()
        releaseInputFocus()
        host.dismiss()
    }
}
