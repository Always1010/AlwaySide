package com.alwayside.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.ceil
import kotlin.math.hypot

internal data class BubbleGestureHint(val label: String, val icon: Int? = null, val unavailableReason: String? = null) {
    val available get() = icon != null && unavailableReason == null
}

internal val InteractionConfig.actionCircleDistanceDp: Int
    get() = maxOf(48, slideDistanceDp + actionCircleSizeDp / 2 + 8)

/** Passive direction targets. Geometry is independent of the user's bubble diameter. */
internal class BubbleGestureHintsView(
    context: Context,
    private val hints: Map<BubbleGesture, BubbleGestureHint>,
    private val ballOnLeft: Boolean,
    private val holdLabel: String,
    config: InteractionConfig,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val directions = listOf(BubbleGesture.UP, BubbleGesture.INWARD, BubbleGesture.DOWN)
    private val icons = hints.mapValues { (_, hint) -> hint.icon?.let(context::prototypeIcon) }
    private val radius = context.dp(config.actionCircleSizeDp) / 2f
    private val distance = context.dp(config.actionCircleDistanceDp).toFloat()
    private val iconSize = context.dp((config.actionCircleSizeDp * .55f).toInt()).toFloat()
    private val dashed = DashPathEffect(floatArrayOf(context.dp(3).toFloat(), context.dp(3).toFloat()), 0f)
    private var reveal = 1f
    private var opening: ValueAnimator? = null
    private var choosing: ValueAnimator? = null
    private val emphasis = directions.associateWith { 0f }.toMutableMap()
    private var preview = BubbleGestureStateMachine.Preview(null, false, 0f, 0f)
    private var alignedX: Int? = null
    private var alignedY: Int? = null

    val anchorX get() = alignedX ?: if (ballOnLeft) context.dp(24) else measuredWidth - context.dp(24)
    val anchorY get() = alignedY ?: ceil(distance + context.dp(28)).toInt()

    init {
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Clamping the window must not move the gesture origin away from the real bubble. */
    fun alignAnchor(x: Int, y: Int) { alignedX = x; alignedY = y; invalidate() }

    fun preview(value: BubbleGestureStateMachine.Preview) {
        val changed = value.direction != preview.direction || value.cancelled != preview.cancelled
        preview = value
        if (changed) {
            choosing?.cancel()
            val start = emphasis.toMap()
            fun update(f: Float) {
                directions.forEach { direction ->
                    val target = if (direction == value.direction && hints[direction]?.available == true) 1f else 0f
                    emphasis[direction] = start.getValue(direction) + (target - start.getValue(direction)) * f
                }
                invalidate()
            }
            if (ValueAnimator.areAnimatorsEnabled()) {
                choosing = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 100
                    addUpdateListener { update(it.animatedValue as Float) }
                    start()
                }
            } else update(1f)
        }
        invalidate()
    }

    fun showAnimated() {
        opening?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { reveal = 1f; invalidate(); return }
        reveal = 0f
        opening = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun hideAnimated(onEnd: () -> Unit) {
        opening?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { onEnd(); return }
        animate().alpha(0f).setDuration(120).withEndAction(onEnd).start()
    }

    override fun onDetachedFromWindow() {
        opening?.cancel(); choosing?.cancel(); animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = maxOf(textPaint.measureText(holdLabel), textPaint.measureText("松手不执行"),
            hints.values.maxOfOrNull { maxOf(textPaint.measureText(it.label), textPaint.measureText(it.unavailableReason.orEmpty())) } ?: 0f)
            .coerceAtMost(context.dp(144).toFloat())
        val desiredWidth = ceil(context.dp(24) + distance + radius * 1.12f + context.dp(28) + textWidth).toInt()
        val desiredHeight = ceil(2 * (distance + context.dp(28)) + context.dp(8)).toInt()
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inward = if (ballOnLeft) 1f else -1f
        val cx = anchorX.toFloat(); val cy = anchorY.toFloat()
        fun point(direction: BubbleGesture): Pair<Float, Float> {
            val offset = when (direction) {
                BubbleGesture.UP -> 0f to -distance
                BubbleGesture.DOWN -> 0f to distance
                else -> distance to 0f
            }
            val travel = .25f + .75f * reveal
            return (cx + inward * offset.first * travel) to (cy + offset.second * travel)
        }
        val selected = preview.direction
        if (selected != null && hints[selected]?.available == true) {
            val (tx, ty) = point(selected)
            val length = hypot(preview.dx, preview.dy).coerceAtMost(distance)
            val f = (length / distance).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE; paint.pathEffect = null
            paint.color = ACCENT; paint.alpha = (100 * reveal).toInt(); paint.strokeWidth = context.dp(1).toFloat()
            canvas.drawLine(cx, cy, cx + (tx-cx) * f, cy + (ty-cy) * f, paint)
            paint.style = Paint.Style.FILL; paint.alpha = (200 * reveal).toInt()
            canvas.drawCircle(cx + (tx-cx)*f, cy + (ty-cy)*f, context.dp(2).toFloat(), paint)
        }
        directions.forEach { direction ->
            val hint = hints[direction] ?: BubbleGestureHint("未设置")
            val (x, y) = point(direction)
            val focus = emphasis.getValue(direction)
            val r = radius * (.85f + .15f * reveal) * (1f + .1f * focus)
            val faded = selected != null && direction != selected
            val opacity = reveal * if (!hint.available) .48f else if (faded) .48f else 1f
            paint.pathEffect = null; paint.style = Paint.Style.FILL
            paint.color = if (focus > .5f) TONAL_SURFACE else SURFACE; paint.alpha = (255 * reveal).toInt()
            canvas.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
            paint.color = if (focus > 0f) ACCENT else BORDER
            paint.alpha = (255 * opacity).toInt(); paint.pathEffect = if (hint.icon == null) dashed else null
            canvas.drawCircle(x, y, r, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
            icons[direction]?.let { icon ->
                val half = iconSize * (1f + .1f * focus) / 2
                icon.setTint(if (hint.available) ACCENT else MUTED); icon.alpha = (255 * opacity).toInt()
                icon.setBounds((x-half).toInt(),(y-half).toInt(),(x+half).toInt(),(y+half).toInt())
                icon.draw(canvas)
            }
        }
        val hint = selected?.let(hints::get)
        val first = when {
            hint != null -> hint.unavailableReason ?: hint.label
            preview.cancelled -> "松手取消"
            else -> "原地松手"
        }
        val second = when {
            hint != null -> if (hint.available) "松手执行" else "松手不执行"
            preview.cancelled -> "可重新选择"
            else -> holdLabel
        }
        val labelWidth = (width - context.dp(24) - distance - radius * 1.12f - context.dp(16)).coerceAtLeast(context.dp(48).toFloat())
        val textLeft = if (ballOnLeft) width - labelWidth else 0f
        val lineHeight = textPaint.fontSpacing
        val textY = cy.coerceIn(lineHeight + context.dp(8), (height - lineHeight - context.dp(8)).coerceAtLeast(lineHeight + context.dp(8)))
        paint.style = Paint.Style.FILL; paint.color = SURFACE; paint.alpha = (245 * reveal).toInt()
        canvas.drawRoundRect(textLeft, textY-lineHeight-context.dp(6), textLeft+labelWidth, textY+lineHeight+context.dp(6), context.dp(8).toFloat(), context.dp(8).toFloat(), paint)
        textPaint.alpha = (255 * reveal).toInt(); textPaint.color = INK
        drawText(canvas, first, textLeft + context.dp(6), textY - context.dp(2), labelWidth - context.dp(12))
        textPaint.color = MUTED
        drawText(canvas, second, textLeft + context.dp(6), textY + lineHeight, labelWidth - context.dp(12))
    }

    private fun drawText(canvas: Canvas, value: String, left: Float, baseline: Float, width: Float) {
        canvas.drawText(TextUtils.ellipsize(value, textPaint, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString(), left, baseline, textPaint)
    }
}
