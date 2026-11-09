package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class FrameCaptureSessionTest {
    @Test fun successReleasesOutputBeforeDeliveryAndDiscardsDuplicateFrames() {
        val events = mutableListOf<String>()
        val session = FrameCaptureSession<String> { events += "discard:$it" }
        var id = 0L
        session.start({ events += "deliver:${it.getOrThrow()}" }) { request ->
            id = request
            AutoCloseable { events += "close" }
        }
        session.complete(id, Result.success("frame"))
        session.complete(id, Result.success("late"))
        session.cancel()
        assertEquals(listOf("close", "deliver:frame", "discard:late"), events)
        assertFalse(session.isCurrent(id))
    }

    @Test fun replacingRequestClosesOldOutputAndOldCallbacksCannotEndNewRequest() {
        val events = mutableListOf<String>()
        val session = FrameCaptureSession<String> { events += "discard:$it" }
        var first = 0L
        var second = 0L
        session.start({ assertTrue(it.isFailure); events += "cancel:first" }) { id ->
            first = id
            AutoCloseable { events += "close:first" }
        }
        session.start({ events += "deliver:${it.getOrThrow()}" }) { id ->
            second = id
            events += "open:second"
            AutoCloseable { events += "close:second" }
        }
        session.complete(first, Result.failure(IllegalStateException("old timeout")))
        session.complete(first, Result.success("old frame"))
        assertTrue(session.isCurrent(second))
        session.complete(second, Result.success("new frame"))
        assertEquals(listOf("close:first", "cancel:first", "open:second", "discard:old frame",
            "close:second", "deliver:new frame"), events)
    }

    @Test fun cancellationAndRepeatedCloseReleaseOnceAndRejectFurtherCapture() {
        var closes = 0
        var replies = 0
        val session = FrameCaptureSession<String> { fail("No frame should be allocated") }
        session.start({ assertTrue(it.isFailure); replies++ }) { AutoCloseable { closes++ } }
        session.cancel()
        session.cancel()
        session.close()
        session.close()
        session.start({ assertTrue(it.isFailure); replies++ }) { error("Closed session opened output") }
        assertEquals(1, closes)
        assertEquals(2, replies)
    }

    @Test fun stoppingWhileCaptureIsActiveReleasesBeforeReportingStopped() {
        var released = false
        var reply: Result<String>? = null
        val session = FrameCaptureSession<String> { fail("No result expected") }
        session.start({ assertTrue(released); reply = it }) { AutoCloseable { released = true } }
        session.close()
        assertEquals("共享已停止", reply!!.exceptionOrNull()!!.message)
    }

    @Test fun timeoutOrCaptureFailureReleasesOutputAndAllowsRetry() {
        var closes = 0
        val replies = mutableListOf<Result<String>>()
        val session = FrameCaptureSession<String> {}
        var id = 0L
        repeat(2) {
            session.start({ replies += it }) { request ->
                id = request
                AutoCloseable { closes++ }
            }
            session.complete(id, Result.failure(IllegalStateException("timeout")))
        }
        assertEquals(2, closes)
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.isFailure })
    }

    @Test fun outputSetupFailureReportsOnceAndDoesNotBlockNextCapture() {
        val replies = mutableListOf<Result<String>>()
        val session = FrameCaptureSession<String> {}
        session.start({ replies += it }) { error("surface unavailable") }
        var id = 0L
        session.start({ replies += it }) { request -> id = request; AutoCloseable {} }
        session.complete(id, Result.success("retry"))
        assertEquals(2, replies.size)
        assertEquals("surface unavailable", replies[0].exceptionOrNull()!!.message)
        assertEquals("retry", replies[1].getOrThrow())
    }

    @Test fun cleanupFailureDiscardsBitmapAndReportsFailureInsteadOfPublishingIt() {
        val discarded = mutableListOf<String>()
        var reply: Result<String>? = null
        var closes = 0
        var id = 0L
        val session = FrameCaptureSession<String> { discarded += it }
        session.start({ reply = it }) { request ->
            id = request
            AutoCloseable { closes++; error("detach failed") }
        }
        session.complete(id, Result.success("bitmap"))
        session.close()
        assertEquals(listOf("bitmap"), discarded)
        assertEquals("detach failed", reply!!.exceptionOrNull()!!.message)
        assertEquals(1, closes)
    }
}
