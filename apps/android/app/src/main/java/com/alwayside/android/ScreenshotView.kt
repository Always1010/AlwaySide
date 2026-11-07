package com.alwayside.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Local screenshot viewer; opening, panning and zooming never issue a request. */
internal class ScreenshotView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var fit = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var selecting = false
    private var selection: Bounds? = null
    private var anchorX = 0f
    private var anchorY = 0f
    private var selectingGesture = false
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun selectText(enabled: Boolean) {
        selecting = enabled; selection = null; selectingGesture = false
        accessible(if (enabled) "图片点读，拖动框选文字，点击识别" else "截图预览，双指缩放、拖动查看，双击切换缩放")
        invalidate()
    }

    fun selectedBitmap(): Bitmap? {
        val bounds = selection ?: return null
        val display = Bounds(offsetX,offsetY,offsetX+bitmap.width*zoom,offsetY+bitmap.height*zoom)
        val crop = Geometry.crop(bounds,display,bitmap.width,bitmap.height) ?: return null
        val clipped = Bitmap.createBitmap(bitmap,crop.x,crop.y,crop.width,crop.height)
        return if(clipped===bitmap) bitmap.copy(Bitmap.Config.ARGB_8888,false) else clipped
    }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt((zoom * detector.scaleFactor).coerceIn(fit, maxOf(fit * 8, 2f)), detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoomAt(if (zoom > fit * 1.1f) fit else maxOf(fit * 2, 1f), event.x, event.y)
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scale.isInProgress) { offsetX -= dx; offsetY -= dy; clamp(); invalidate() }
            return true
        }
    })

    init { accessible("截图预览，双指缩放、拖动查看，双击切换缩放"); isClickable = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height).coerceAtLeast(0.001f)
        zoom = fit; clamp()
        selection = null
    }

    private fun zoomAt(next: Float, x: Float, y: Float) {
        val ratio = next / zoom
        offsetX = x - (x - offsetX) * ratio; offsetY = y - (y - offsetY) * ratio
        zoom = next; clamp(); invalidate()
    }

    private fun clamp() {
        val w = bitmap.width * zoom; val h = bitmap.height * zoom
        offsetX = if (w <= width) (width - w) / 2 else offsetX.coerceIn(width - w, 0f)
        offsetY = if (h <= height) (height - h) / 2 else offsetY.coerceIn(height - h, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(zoom, zoom)
        canvas.drawBitmap(bitmap, 0f, 0f, paint); canvas.restore()
        if(selecting) selection?.let { bounds ->
            val rect=RectF(minOf(bounds.left,bounds.right),minOf(bounds.top,bounds.bottom),
                maxOf(bounds.left,bounds.right),maxOf(bounds.top,bounds.bottom))
            selectionPaint.style=Paint.Style.FILL;selectionPaint.color=(ACCENT and 0x00ffffff) or 0x33000000
            canvas.drawRect(rect,selectionPaint)
            selectionPaint.style=Paint.Style.STROKE;selectionPaint.strokeWidth=context.dp(2).toFloat();selectionPaint.color=ACCENT
            canvas.drawRect(rect,selectionPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(selecting) {
            if(event.pointerCount>1) { selection=null;selectingGesture=false;invalidate();return true }
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectingGesture=true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    anchorX=event.x;anchorY=event.y;selection=Bounds(anchorX,anchorY,anchorX,anchorY)
                }
                MotionEvent.ACTION_MOVE -> if(selectingGesture)selection=Bounds(anchorX,anchorY,event.x,event.y)
                MotionEvent.ACTION_UP -> {
                    if(selectingGesture) { selection=Bounds(anchorX,anchorY,event.x,event.y);performClick() }
                    selectingGesture=false
                }
                MotionEvent.ACTION_CANCEL -> { selection=null;selectingGesture=false }
            }
            invalidate();return true
        }
        scale.onTouchEvent(event); gestures.onTouchEvent(event); return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
