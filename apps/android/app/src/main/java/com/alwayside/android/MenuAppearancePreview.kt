package com.alwayside.android

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.function.Consumer

/** A local scene and the production menu, with no capture, API calls or overlay permission. */
internal class MenuAppearancePreview(
    context: Context,
    private val config: InteractionConfig,
    private val displayScale: Float = .58f,
    private val onBlurAvailability: (Boolean) -> Unit = {},
) : FrameLayout(context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var appearance = config.menuAppearance
    private var blurListener: Consumer<Boolean>? = null
    private val sample = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        setBackgroundColor(SURFACE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val shade = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val shared = AssistantMenuViews(context, webSearch = config.menuWebSearch ?: false,
        useDefaultQuestion = config.useDefaultQuestion, items = config.menuItems,
        appearance = appearance, displayScale = displayScale, onAction = {})
    private val menuScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(shared.view)
        clipToOutline = true
    }

    init {
        background = card(SURFACE, context.dp(12).toFloat()); clipToOutline = true
        addView(sample, LayoutParams(-1, -1))
        with(context) {
            sample.addView(label("今天的计划", 16f).apply { setPadding(dp(6), dp(8), 0, dp(8)) })
            sample.addView(label("今天 15:48", 12f, true).apply { gravity = Gravity.CENTER })
            fun message(text: String, own: Boolean = false) {
                sample.addView(label(text, 13f).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = card(if (own) TONAL_SURFACE else BACKGROUND, dp(12).toFloat())
                }, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(12); leftMargin = if (own) dp(40) else 0; rightMargin = if (own) 0 else dp(40)
                })
            }
            message("帮我整理一下今天要做的事。", true)
            message("下午的安排\n\n整理本周的工作笔记\n\n读完收藏的文章\n\n傍晚出去走一走")
            message("好的，先从第一件开始。", true)
        }
        addView(shade, LayoutParams(-1, -1))
        addView(menuScroll, LayoutParams(1, 1, Gravity.CENTER))
        render(appearance)
    }

    private fun blurSupported(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { windows.isCrossWindowBlurEnabled }.getOrDefault(false)

    val currentDimPercent: Int get() = appearance.effectiveDimPercent(blurSupported())

    fun render(next: MenuAppearance) {
        appearance = next
        val supported = blurSupported()
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = next.blurRadiusDp * resources.displayMetrics.density * displayScale
            sample.setRenderEffect(if (supported && next.blurStrength > 0)
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
        }
        shade.alpha = next.effectiveDimPercent(supported) / 100f
        onBlurAvailability(supported)
        shared.applyAppearance(next)
        menuScroll.background = card(Color.TRANSPARENT, context.dp(next.cornerRadiusDp) * next.scale * displayScale)
        positionMenu()
    }

    private fun positionMenu() {
        val scroll = menuScroll
        if (width == 0 || height == 0) return
        val w = minOf(context.dp(shared.preferredWidthDp), (width - context.dp(16)).coerceAtLeast(1))
        val child = scroll.getChildAt(0)
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        scroll.layoutParams = LayoutParams(w, minOf(child.measuredHeight,
            (height - context.dp(16)).coerceAtLeast(1)), Gravity.CENTER)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionMenu()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = Consumer<Boolean> { render(appearance) }
            blurListener = listener
            runCatching { windows.addCrossWindowBlurEnabledListener(context.mainExecutor, listener) }
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) blurListener?.let { listener ->
            runCatching { windows.removeCrossWindowBlurEnabledListener(listener) }
        }
        blurListener = null
        super.onDetachedFromWindow()
    }
}
