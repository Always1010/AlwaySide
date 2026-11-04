package com.alwayside.android

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
internal data class PixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)
internal object Geometry {
    fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Bounds {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        return Bounds((viewWidth-w)/2, (viewHeight-h)/2, (viewWidth+w)/2, (viewHeight+h)/2)
    }
    fun crop(selection: Bounds, display: Bounds, width: Int, height: Int): PixelCrop? {
        if (display.width <= 0 || display.height <= 0 || width <= 0 || height <= 0) return null
        val l = max(min(selection.left, selection.right), display.left)
        val t = max(min(selection.top, selection.bottom), display.top)
        val r = min(max(selection.left, selection.right), display.right)
        val b = min(max(selection.top, selection.bottom), display.bottom)
        if (r-l < 8 || b-t < 8) return null
        val x = floor((l-display.left) / display.width * width).toInt().coerceIn(0,width-1)
        val y = floor((t-display.top) / display.height * height).toInt().coerceIn(0,height-1)
        val endX = ceil((r-display.left) / display.width * width).toInt().coerceIn(x+1,width)
        val endY = ceil((b-display.top) / display.height * height).toInt().coerceIn(y+1,height)
        return PixelCrop(x,y,endX-x,endY-y)
    }
}
