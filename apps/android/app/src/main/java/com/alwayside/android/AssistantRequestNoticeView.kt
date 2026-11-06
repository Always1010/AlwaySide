package com.alwayside.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import com.google.android.material.color.MaterialColors

/** A persistent notice outside the answer scroll area; details stay behind the info action. */
@SuppressLint("ViewConstructor")
internal class AssistantRequestNoticeView(context: Context, dismiss: () -> Unit) : LinearLayout(context) {
    private var notice: RequestNotice? = null
    private var details: PopupWindow? = null
    private val symbol = ImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val summary = context.label("", 12f).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val info = context.iconButton(R.drawable.ic_line_info, "查看请求状态详情", ::showDetails)
    private val close = context.iconButton(R.drawable.ic_line_close, "关闭状态提示", dismiss)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), context.dp(4), context.dp(4), context.dp(4))
        visibility = GONE
        addView(symbol, LayoutParams(context.dp(18), context.dp(18)).apply { marginEnd = context.dp(8) })
        addView(summary, LayoutParams(0, -2, 1f))
        listOf(info, close).forEach { button ->
            button.iconSize = context.dp(16)
            button.minWidth = 0; button.minimumWidth = 0; button.minHeight = 0; button.minimumHeight = 0
            button.setPadding(context.dp(8), 0, context.dp(8), 0)
            addView(button, LayoutParams(context.dp(32), context.dp(40)))
        }
    }

    fun render(value: RequestNotice?) {
        if (notice != value) { details?.dismiss(); details = null }
        notice = value
        visibility = if (value == null) GONE else VISIBLE
        if (value == null) return
        val foreground = if (value.isError) MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorOnErrorContainer) else INK
        val fill = if (value.isError) MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorErrorContainer) else SURFACE
        background = card(fill, context.dp(10).toFloat())
        summary.text = value.summary; summary.setTextColor(foreground)
        symbol.setImageDrawable(context.prototypeIcon(if (value.isError) R.drawable.ic_line_alert else R.drawable.ic_line_info))
        symbol.imageTintList = ColorStateList.valueOf(foreground)
        info.iconTint = ColorStateList.valueOf(foreground)
        close.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun showDetails() {
        val value = notice ?: return
        if (!isAttachedToWindow) return
        details?.dismiss()
        val content = context.column().apply {
            addView(context.label(value.summary, 15f))
            addView(context.label(value.detail, 13f).apply {
                setTextIsSelectable(true)
                setPadding(0, context.dp(12), 0, 0)
            })
        }
        val popup = PopupWindow(ScrollView(context).apply { addView(content) },
            width.coerceAtMost(context.dp(360)),
            (rootView.height - context.dp(24)).coerceIn(context.dp(96), context.dp(240)), true).apply {
            setBackgroundDrawable(context.outlinedCard())
            elevation = context.dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        details = popup
        popup.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    override fun onDetachedFromWindow() {
        details?.dismiss(); details = null
        super.onDetachedFromWindow()
    }
}
