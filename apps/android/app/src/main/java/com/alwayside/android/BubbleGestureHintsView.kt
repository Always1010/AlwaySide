package com.alwayside.android

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.ceil
import kotlin.math.abs

/** Passive hints: host in a FLAG_NOT_TOUCHABLE overlay and align [anchorX]/[anchorY]
 * with the bubble's screen center. All labels sit on the inward side of its 48dp target. */
internal class BubbleGestureHintsView(
    context: Context,
    private val labels: Map<BubbleGesture, String>,
    private val ballOnLeft: Boolean,
    private val holdLabel: String = labels[BubbleGesture.HOLD] ?: "默认动作",
) : View(context) {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val up = context.prototypeIcon(R.drawable.ic_line_chevron_up)
    private val down = context.prototypeIcon(R.drawable.ic_line_chevron_down)
    private val inward = context.prototypeIcon(R.drawable.ic_line_chevron_up)
    private val directions = listOf(BubbleGesture.UP, BubbleGesture.INWARD, BubbleGesture.DOWN)
    private val padding = context.dp(10)
    private val iconSize = context.dp(16)
    private val gap = context.dp(5)
    private val ballSpace = context.dp(56)
    private val releaseText = "松手 · $holdLabel"
    private var rowHeight = context.dp(28)
    private var selected: BubbleGesture? = null

    val anchorX: Int get() = if (ballOnLeft) context.dp(24) else measuredWidth - context.dp(24)
    val anchorY: Int get() = rowHeight + gap + rowHeight / 2

    init {
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun preview(dx: Float, dy: Float) {
        val next = when {
            maxOf(abs(dx), abs(dy)) < context.dp(4) -> null
            abs(dy) >= abs(dx) -> if (dy < 0) BubbleGesture.UP else BubbleGesture.DOWN
            (ballOnLeft && dx > 0) || (!ballOnLeft && dx < 0) -> BubbleGesture.INWARD
            else -> null
        }
        if (selected != next) { selected = next; invalidate() }
    }

    fun showAnimated() {
        animate().cancel()
        animate().withEndAction(null)
        if (!ValueAnimator.areAnimatorsEnabled()) { alpha = 1f; translationX = 0f; return }
        alpha = 0f; translationX = context.dp(8).toFloat() * if (ballOnLeft) -1 else 1
        animate().alpha(1f).translationX(0f).setDuration(120).start()
    }

    /** For a passive dismissal; gesture dispatch should remove the overlay immediately. */
    fun hideAnimated(onEnd: () -> Unit) {
        animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { alpha = 0f; onEnd(); return }
        animate().alpha(0f).translationX(context.dp(8).toFloat() * if (ballOnLeft) -1 else 1)
            .setDuration(90).withEndAction { onEnd() }.start()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = textPaint.fontMetrics
        rowHeight = maxOf(context.dp(28), ceil(metrics.descent - metrics.ascent).toInt() + context.dp(12))
        val longest = maxOf(textPaint.measureText(releaseText), directions.maxOf {
            textPaint.measureText(labels[it] ?: "未分配") + iconSize + gap
        })
        val desiredWidth = ballSpace + padding * 2 + ceil(longest).toInt()
        val desiredHeight = rowHeight * 4 + gap * 3
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = if (ballOnLeft) ballSpace.toFloat() else 0f
        val right = if (ballOnLeft) width.toFloat() else (width - ballSpace).toFloat()
        if (right <= left) return
        val maxTextWidth = (right - left - padding * 2 - iconSize - gap).coerceAtLeast(0f)
        directions.forEachIndexed { index, gesture ->
            val top = (index * (rowHeight + gap)).toFloat()
            val assigned = labels[gesture]?.let { it != "未分配" && it != "不执行动作" } == true
            val highlighted = selected == gesture
            val faded = selected != null && !highlighted
            val foregroundAlpha = if (!assigned) 110 else if (faded) 125 else 255
            drawChip(canvas, left, top, right, highlighted && assigned, faded)
            val icon = when (gesture) { BubbleGesture.UP -> up; BubbleGesture.DOWN -> down; else -> inward }
            val iconLeft = left + padding
            val iconTop = top + (rowHeight - iconSize) / 2f
            val save = canvas.save()
            if (gesture == BubbleGesture.INWARD) canvas.rotate(if (ballOnLeft) 90f else -90f,
                iconLeft + iconSize / 2f, iconTop + iconSize / 2f)
            icon.setTint(if (assigned) ACCENT else MUTED); icon.alpha = foregroundAlpha
            icon.setBounds(iconLeft.toInt(),iconTop.toInt(),(iconLeft+iconSize).toInt(),(iconTop+iconSize).toInt())
            icon.draw(canvas); canvas.restoreToCount(save)
            drawLabel(canvas, labels[gesture] ?: "未分配", iconLeft + iconSize + gap, top, maxTextWidth, foregroundAlpha)
        }
        val top = (3 * (rowHeight + gap)).toFloat()
        drawChip(canvas, left, top, right)
        drawLabel(canvas, releaseText, left + padding, top, (right-left-padding*2).coerceAtLeast(0f))
    }

    private fun drawChip(canvas: Canvas, left: Float, top: Float, right: Float,
        highlighted: Boolean = false, faded: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = if (highlighted) TONAL_SURFACE else SURFACE; paint.alpha = if (faded) 210 else 245
        canvas.drawRoundRect(left,top,right,top+rowHeight,context.dp(8).toFloat(),context.dp(8).toFloat(),paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
        paint.color = if (highlighted) ACCENT else BORDER; paint.alpha = if (faded) 150 else 255
        val inset = paint.strokeWidth / 2
        canvas.drawRoundRect(left+inset,top+inset,right-inset,top+rowHeight-inset,
            context.dp(8).toFloat(),context.dp(8).toFloat(),paint)
    }

    private fun drawLabel(canvas: Canvas, value: String, left: Float, top: Float, maxWidth: Float, alpha: Int = 255) {
        textPaint.color = INK; textPaint.alpha = alpha
        val text = TextUtils.ellipsize(value, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        val baseline = top + rowHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text,left,baseline,textPaint)
    }
}
