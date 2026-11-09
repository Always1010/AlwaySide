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
import java.util.concurrent.atomic.AtomicBoolean

internal data class CapturedFrame(val bitmap: Bitmap, val capturedAt: Long)

/** One display per consent, with no output surface or image queue between snapshots. */
internal class FrameSource(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    private val density: Int,
    private val main: Handler,
) {
    private val thread = HandlerThread("screen-frames").apply { start() }
    private val worker = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var captureWidth = width
    private var captureHeight = height
    private val closing = AtomicBoolean(false)
    private val session = FrameCaptureSession<CapturedFrame> { it.bitmap.recycle() }

    init {
        try {
            display = checkNotNull(projection.createVirtualDisplay("AlwaySide", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, worker)) { "无法建立屏幕共享" }
        } catch (e: Exception) {
            thread.quitSafely(); throw e
        }
    }

    fun snapshot(afterNanos: Long, callback: (Result<CapturedFrame>) -> Unit) {
        val deliver: (Result<CapturedFrame>) -> Unit = { result -> main.post { callback(result) } }
        if (closing.get()) {
            deliver(Result.failure(IllegalStateException("共享已停止")))
            return
        }
        if (!worker.post {
            if (closing.get()) deliver(Result.failure(IllegalStateException("共享已停止")))
            else session.start(deliver) { id -> openOutput(id, afterNanos) }
        }) deliver(Result.failure(IllegalStateException("共享已停止")))
    }

    private fun openOutput(id: Long, cutoff: Long): AutoCloseable {
        val currentDisplay = checkNotNull(display) { "共享已停止" }
        currentDisplay.resize(captureWidth, captureHeight, density)
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
        val timeout = Runnable {
            session.complete(id, Result.failure(IllegalStateException("没有取得新画面，请返回目标页面后重试")))
        }
        val output = AutoCloseable {
            worker.removeCallbacks(timeout)
            // Always close the queue, even if the system has already revoked projection.
            try { currentDisplay.surface = null }
            finally {
                try { reader.setOnImageAvailableListener(null, null) }
                finally { reader.close() }
            }
        }
        try {
            reader.setOnImageAvailableListener({ source -> consume(id, cutoff, source) }, worker)
            // A fresh surface requests composition even for a static screen and has no old pixels.
            currentDisplay.surface = reader.surface
            check(worker.postDelayed(timeout, 3000)) { "共享已停止" }
        } catch (e: Exception) {
            runCatching { output.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        return output
    }

    private fun consume(id: Long, cutoff: Long, source: ImageReader) {
        // Released readers and cancelled requests can still have callbacks in the queue.
        if (closing.get() || !session.isCurrent(id)) return
        try {
            val image = source.acquireLatestImage() ?: return
            val frame = image.use {
                if (image.timestamp <= cutoff) return
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
                    CapturedFrame(bitmap, System.currentTimeMillis())
                } catch (e: Exception) { padded.recycle(); throw e }
            }
            // Image.use must finish before completion closes its owning ImageReader.
            session.complete(id, Result.success(frame))
        } catch (e: Exception) { session.complete(id, Result.failure(e)) }
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        worker.post {
            if (closing.get() || (width == captureWidth && height == captureHeight)) return@post
            captureWidth = width; captureHeight = height
            session.cancel("画面尺寸已变化，请重新取图")
            // Apply the new geometry on the next snapshot, without opening an idle image queue.
        }
    }

    fun cancelSnapshot() {
        if (!closing.get()) worker.post { session.cancel() }
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        worker.post {
            try { session.close() }
            finally {
                try { display?.release() }
                catch (e: RuntimeException) { android.util.Log.w("FrameSource", "Virtual display release failed", e) }
                finally { display = null; thread.quitSafely() }
            }
        }
    }
}
