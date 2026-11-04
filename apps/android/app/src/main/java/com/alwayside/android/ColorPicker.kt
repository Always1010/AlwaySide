package com.alwayside.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import com.google.android.material.slider.LabelFormatter

/** A visual HSV picker. Its private draft is applied only when the owner confirms. */
internal class ColorPicker(context: Context, initialColor: Int) : LinearLayout(context) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }
    val selectedColor: Int get() = Color.HSVToColor(hsv)
    private val preview = View(context)
    private val plane = SaturationBrightnessPlane(context)

    init {
        orientation = VERTICAL
        setPadding(context.dp(22), context.dp(12), context.dp(22), context.dp(12))
        addView(context.label("左右选鲜艳程度，上下选明暗", 12f, true))
        addView(plane, LayoutParams(-1, context.dp(200)).apply { topMargin = context.dp(10) })
        addView(context.label("色彩", 12f).apply { setPadding(0, context.dp(16), 0, 0) })
        addView(Slider(context).apply {
            // RGB colors can produce fractional hue values, so keep the slider continuous.
            valueFrom=0f;valueTo=360f;stepSize=0f;value=hsv[0].coerceIn(0f,360f)
            contentDescription = "色彩，拖动选择颜色"
            labelBehavior=LabelFormatter.LABEL_GONE
            addOnChangeListener { _,value,_ -> hsv[0]=value;update() }
        }, LayoutParams(-1, context.dp(48)))
        addView(context.label("颜色预览", 12f).apply { setPadding(0, context.dp(8), 0, context.dp(8)) })
        addView(preview, LayoutParams(-1, context.dp(44)))
        update()
    }

    private fun update() {
        preview.background = card(selectedColor, context.dp(8).toFloat()).apply {
            setStroke(context.dp(1).coerceAtLeast(1), BORDER)
        }
        plane.invalidate()
        plane.contentDescription = "颜色面板，左右调整鲜艳程度，上下调整明暗"
    }

    private inner class SaturationBrightnessPlane(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return
            paint.shader = ComposeShader(
                LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE,
                    Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP),
                LinearGradient(0f, 0f, 0f, height.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
                PorterDuff.Mode.MULTIPLY,
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            val x = hsv[1] * width
            val y = (1f - hsv[2]) * height
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(4).toFloat(); paint.color = Color.BLACK
            canvas.drawCircle(x, y, context.dp(8).toFloat(), paint)
            paint.strokeWidth = context.dp(2).toFloat(); paint.color = Color.WHITE
            canvas.drawCircle(x, y, context.dp(8).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    hsv[1] = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    hsv[2] = 1f - (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    update()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        performClick(); parent.requestDisallowInterceptTouchEvent(false)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
