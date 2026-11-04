package com.alwayside.android

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer

internal data class CapturedFrame(val bitmap: Bitmap, val capturedAt: Long)

/** A single VirtualDisplay per consent token. No screenshots are written to disk. */
internal class FrameSource(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    private val density: Int,
    private val main: Handler,
) {
    private val thread = HandlerThread("screen-frames").apply { start() }
    private val worker = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var pending: ((Result<CapturedFrame>) -> Unit)? = null
    private var cutoff = 0L
    private var closed = false
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("没有取得新画面，请返回目标页面后重试"))) }

    init {
        try {
            reader = makeReader(width, height)
            display = projection.createVirtualDisplay("AlwaySide", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, worker)
        } catch (e: Exception) {
            reader?.close(); thread.quitSafely(); throw e
        }
    }

    private fun makeReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            // Keep the producer moving even when no screenshot is requested.
            setOnImageAvailableListener({ source -> consume(source) }, worker)
        }

    fun snapshot(afterNanos: Long, callback: (Result<CapturedFrame>) -> Unit) {
        worker.post {
            if (closed) { main.post { callback(Result.failure(IllegalStateException("共享已停止"))) }; return@post }
            finish(Result.failure(IllegalStateException("取图已取消")))
            cutoff = afterNanos; pending = callback
            try {
                // A new target cannot contain queued overlay pixels. Changing surface identity
                // also requests composition for a static screen without creating another display.
                val previous = checkNotNull(reader) { "共享已停止" }
                val currentDisplay = checkNotNull(display) { "共享已停止" }
                val replacement = makeReader(previous.width, previous.height)
                try { currentDisplay.surface = replacement.surface }
                catch (e: Exception) { replacement.close(); throw e }
                reader = replacement
                previous.close()
                worker.postDelayed(timeout, 3000)
            } catch (e: Exception) { finish(Result.failure(e)) }
        }
    }

    private fun consume(source: ImageReader) {
        // Resize can leave an availability callback queued for the released reader.
        if (closed || source !== reader) return
        try {
            val image = source.acquireLatestImage() ?: return
            image.use {
                if (pending == null || image.timestamp <= cutoff) return
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                try {
                    val required=plane.rowStride*image.height
                    val buffer=plane.buffer
                    if (buffer.remaining()>=required) padded.copyPixelsFromBuffer(buffer)
                    else {
                        // Some producers omit only the unused padding of the last scanline.
                        check(buffer.remaining()>=(image.height-1)*plane.rowStride+image.width*plane.pixelStride)
                        val complete=ByteBuffer.allocate(required).put(buffer)
                        complete.rewind(); padded.copyPixelsFromBuffer(complete)
                    }
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (bitmap !== padded) padded.recycle()
                    finish(Result.success(CapturedFrame(bitmap, System.currentTimeMillis())))
                } catch (e: Exception) { padded.recycle(); throw e }
            }
        } catch (e: Exception) { finish(Result.failure(e)) }
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        worker.post {
            if (closed) return@post
            try {
                finish(Result.failure(IllegalStateException("画面尺寸已变化，请重新取图")))
                val old = reader
                val replacement = makeReader(width, height)
                display?.resize(width, height, density)
                display?.surface = replacement.surface
                reader = replacement
                old?.close()
            } catch (e: Exception) { finish(Result.failure(e)) }
        }
    }

    private fun finish(result: Result<CapturedFrame>) {
        worker.removeCallbacks(timeout)
        val callback = pending
        pending = null
        if (callback != null) main.post { callback(result) }
    }

    fun close() {
        worker.post {
            closed = true
            finish(Result.failure(IllegalStateException("共享已停止")))
            display?.release(); display = null
            reader?.close(); reader = null
            thread.quitSafely()
        }
    }
}
