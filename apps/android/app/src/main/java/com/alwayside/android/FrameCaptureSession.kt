package com.alwayside.android

/** Thread-confined ownership of a single temporary capture output. */
internal class FrameCaptureSession<T>(private val discard: (T) -> Unit) {
    private class Request<T>(val id: Long, val callback: (Result<T>) -> Unit) {
        var output: AutoCloseable? = null
    }

    private var nextId = 0L
    private var current: Request<T>? = null
    private var closed = false

    fun start(callback: (Result<T>) -> Unit, openOutput: (Long) -> AutoCloseable) {
        if (closed) {
            callback(Result.failure(IllegalStateException("共享已停止")))
            return
        }
        cancel()
        val request = Request(++nextId, callback)
        current = request
        try {
            request.output = openOutput(request.id)
        } catch (e: Exception) {
            complete(request.id, Result.failure(e))
        }
    }

    fun isCurrent(id: Long): Boolean = current?.id == id

    fun complete(id: Long, result: Result<T>) {
        val request = current
        if (request == null || request.id != id) {
            result.getOrNull()?.let(discard)
            return
        }
        current = null
        // Detach/close output before publishing the result or allowing another capture.
        val cleanup = runCatching { request.output?.close() }
        val delivered = if (cleanup.isFailure) {
            result.getOrNull()?.let(discard)
            Result.failure(cleanup.exceptionOrNull()!!)
        } else result
        request.callback(delivered)
    }

    fun cancel(message: String = "取图已取消") {
        current?.let { complete(it.id, Result.failure(IllegalStateException(message))) }
    }

    fun close() {
        if (closed) return
        closed = true
        cancel("共享已停止")
    }
}
