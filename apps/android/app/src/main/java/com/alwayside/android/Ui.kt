package com.alwayside.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal const val BACKGROUND = 0xFF10191E.toInt()
internal const val SURFACE = 0xFF1D2B32.toInt()
internal const val ACCENT = 0xFF8FE3D1.toInt()
internal const val INK = 0xFFEAF4F2.toInt()
internal const val MUTED = 0xFFA9BEC0.toInt()
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun card(color: Int, radius: Float = 24f) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
}
internal fun Context.column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(16), dp(20), dp(16))
}
internal fun Context.label(value: String, size: Float = 16f, muted: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(if (muted) MUTED else INK)
    setPadding(0, dp(6), 0, dp(6))
    if (size >= 22) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}
internal fun LinearLayout.action(title: String, primary: Boolean = false, run: () -> Unit): Button =
    Button(context).also { button ->
        button.text = title; button.isAllCaps = false
        button.setTextColor(if (primary) BACKGROUND else INK)
        button.background = card(if (primary) ACCENT else SURFACE)
        button.minHeight = context.dp(48)
        button.setOnClickListener { run() }
        addView(button, LinearLayout.LayoutParams(-1, context.dp(52)).apply { topMargin = context.dp(10) })
    }
internal fun View.accessible(name: String) { contentDescription = name; tooltipText = name }
