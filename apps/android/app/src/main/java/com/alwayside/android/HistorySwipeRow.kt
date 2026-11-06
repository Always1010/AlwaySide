package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/** Sliding reveals buttons only. Releasing a gesture never executes a conversation action. */
internal class HistorySwipeRow(
    context: Context,
    private val content: View,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    private val onOpened: (HistorySwipeRow) -> Unit,
    private val onClosed: (HistorySwipeRow) -> Unit,
) : FrameLayout(context) {
    private val actionWidth = context.dp(64)
    private val left = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val right = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private var origin = 0f

    init {
        background = card(SURFACE, context.dp(16).toFloat())
        clipToOutline = true
        fun button(parent: LinearLayout, icon: Int, label: String, destructive: Boolean = false, action: () -> Unit) {
            parent.addView(context.iconButton(icon, label, action).apply {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                iconTint = ColorStateList.valueOf(if (destructive) com.google.android.material.color.MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorError) else ACCENT)
            }, LinearLayout.LayoutParams(actionWidth, context.dp(56)))
        }
        left.setBackgroundColor(TONAL_SURFACE)
        button(left, if (favorite) R.drawable.ic_bookmark_filled else R.drawable.ic_line_bookmark,
            if (favorite) "取消收藏" else "收藏", action = onFavorite)
        button(right, R.drawable.ic_line_more, "更多会话操作", action = onMore)
        button(right, R.drawable.ic_line_trash, "删除会话", destructive = true, action = onDelete)
        left.visibility = View.INVISIBLE; right.visibility = View.INVISIBLE
        addView(left, LayoutParams(actionWidth, -1, Gravity.LEFT))
        addView(right, LayoutParams(actionWidth * 2, -1, Gravity.RIGHT))
        addView(content, LayoutParams(-1, -2))
    }

    @Suppress("DEPRECATION") // systemGestureInsets is available on every supported API (29+).
    fun canSwipeFrom(rawX: Float): Boolean {
        val location = IntArray(2)
        rootView.getLocationOnScreen(location)
        val x = rawX - location[0]
        val insets = rootWindowInsets?.systemGestureInsets
        return x > maxOf(context.dp(24), insets?.left ?: 0) &&
            x < rootView.width - maxOf(context.dp(24), insets?.right ?: 0)
    }

    fun contains(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + width &&
            rawY >= location[1] && rawY < location[1] + height
    }

    fun begin() { content.animate().cancel(); origin = content.translationX }

    fun drag(dx: Float) {
        onOpened(this)
        content.translationX = (origin + dx).coerceIn(-2f * actionWidth, actionWidth.toFloat())
        showActions(content.translationX)
    }

    fun finish() {
        val offset = content.translationX
        val extent = if (offset > 0) actionWidth.toFloat() else -2f * actionWidth
        settle(if (abs(offset) >= abs(extent) * .4f) extent else 0f)
    }

    fun close() { settle(0f) }

    private fun settle(offset: Float) {
        content.animate().cancel()
        if (offset != 0f) showActions(offset) else onClosed(this)
        content.animate().translationX(offset).setDuration(160).withEndAction { showActions(offset) }.start()
    }

    private fun showActions(offset: Float) {
        left.visibility = if (offset > 0) View.VISIBLE else View.INVISIBLE
        right.visibility = if (offset < 0) View.VISIBLE else View.INVISIBLE
    }

    override fun onDetachedFromWindow() {
        content.animate().cancel()
        onClosed(this)
        super.onDetachedFromWindow()
    }
}
