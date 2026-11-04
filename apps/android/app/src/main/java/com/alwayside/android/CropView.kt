package com.alwayside.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

internal class CropView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var area: Bounds? = null
    private var selection: Bounds? = null
    private var startX = 0f
    private var startY = 0f
    init { accessible("在冻结画面上拖动框选区域"); setLayerType(LAYER_TYPE_SOFTWARE,null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val fit = Geometry.fit(bitmap.width,bitmap.height,width,height)
        area = fit
        paint.style = Paint.Style.FILL; paint.color = INK
        canvas.drawBitmap(bitmap,null,RectF(fit.left,fit.top,fit.right,fit.bottom),paint)
        selection?.let {
            paint.color = 0x448FE3D1; paint.style = Paint.Style.FILL
            val rect = RectF(minOf(it.left,it.right),minOf(it.top,it.bottom),maxOf(it.left,it.right),maxOf(it.top,it.bottom))
            canvas.drawRect(rect,paint)
            paint.color = ACCENT; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat()
            canvas.drawRect(rect,paint)
            paint.style = Paint.Style.FILL
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fit = area ?: return false
        val x = event.x.coerceIn(fit.left,fit.right)
        val y = event.y.coerceIn(fit.top,fit.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=x; startY=y; selection=null; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                selection = Bounds(startX,startY,x,y)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> selection=null
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun selected(): Bitmap? {
        val crop = selectedBounds() ?: return null
        return Bitmap.createBitmap(bitmap,crop.x,crop.y,crop.width,crop.height)
    }
    fun selectedBounds(): PixelCrop? { return Geometry.crop(selection ?: return null,area ?: return null,bitmap.width,bitmap.height) }
}
