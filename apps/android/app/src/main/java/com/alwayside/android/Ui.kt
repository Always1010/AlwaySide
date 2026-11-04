package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

private val nightMode: Boolean get() = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
internal val BACKGROUND: Int get() = if (nightMode) 0xFF18231F.toInt() else 0xFFFFFEFA.toInt()
internal val SURFACE: Int get() = if (nightMode) 0xFF24322B.toInt() else 0xFFF0F3ED.toInt()
internal val ACCENT: Int get() = if (nightMode) 0xFF9CCDB0.toInt() else 0xFF315D4B.toInt()
internal val INK: Int get() = if (nightMode) 0xFFEAF0EB.toInt() else 0xFF283D35.toInt()
internal val MUTED: Int get() = if (nightMode) 0xFFAFBBB2.toInt() else 0xFF6C7970.toInt()
internal val BORDER: Int get() = if (nightMode) 0xFF35463A.toInt() else 0xFFE1E7DF.toInt()
internal val ON_ACCENT: Int get() = if (nightMode) 0xFF16281D.toInt() else Color.WHITE
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun card(color: Int, radius: Float = 8f * Resources.getSystem().displayMetrics.density) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
}
internal fun Context.outlinedCard(color: Int = BACKGROUND, radius: Int = 8) = card(color, dp(radius).toFloat()).apply { setStroke(dp(1).coerceAtLeast(1), BORDER) }
internal fun Context.touchFeedback() = RippleDrawable(ColorStateList.valueOf((INK and 0x00FFFFFF) or 0x18000000), null, card(Color.WHITE, dp(8).toFloat()))
internal fun Context.iconButton(resource: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
    setImageDrawable(prototypeIcon(resource)); imageTintList = ColorStateList.valueOf(INK)
    background = touchFeedback(); scaleType = ImageView.ScaleType.FIT_CENTER
    setPadding(dp(13), dp(13), dp(13), dp(13)); minimumWidth = dp(44); minimumHeight = dp(44)
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    accessible(description); setOnClickListener { action() }
}
internal fun Context.column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(18), dp(22), dp(18))
}
internal fun Context.label(value: String, size: Float = 13f, muted: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(if (muted) MUTED else INK)
    includeFontPadding = false
    if (size >= 15) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}
internal fun LinearLayout.action(title: String, primary: Boolean = false, run: () -> Unit): Button =
    Button(context).also { button ->
        button.text = title; button.isAllCaps = false
        button.textSize = 13f
        button.setTextColor(if (primary) ON_ACCENT else INK)
        button.background = RippleDrawable(ColorStateList.valueOf(0x18000000), context.outlinedCard(if (primary) ACCENT else SURFACE), null)
        button.minHeight = context.dp(40)
        button.setOnClickListener { run() }
        addView(button, LinearLayout.LayoutParams(-1, context.dp(40)).apply { topMargin = context.dp(8) })
    }
internal fun View.accessible(name: String) { contentDescription = name; tooltipText = name }
