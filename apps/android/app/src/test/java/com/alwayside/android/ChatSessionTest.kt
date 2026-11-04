package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class ChatSessionTest {
    @Test fun cancelledCallbacksCannotOverwriteNextRequestOrSaveHistory() {
        val h = Harness()
        h.session.submit(h.config, "old question", null, "old evidence", File("old.wav"))
        h.worker.runNext() // The old transcription and completion are waiting for the main thread.
        val old = h.gateway.calls.single()
        h.session.cancel()
        h.session.submit(h.config, "new question", null, "new evidence")
        old.onDelta("late old delta") // Even an uncooperative transport cannot contaminate the new request.
        h.drainMain()
        h.assertNewRequestUntouched()

        h.worker.runNext()
        h.drainMain()
        assertEquals(listOf(ChatMessage("user", "new question", "new evidence"), ChatMessage("assistant", "answer")), h.session.messages)
        assertEquals(1, h.saved.size)
        assertEquals("new evidence", h.saved.single().evidence)
        assertEquals("", h.saved.single().transcript)
    }

    @Test fun oldTranscriptAndCompletionCannotMutateRestoredConversation() {
        val h = Harness()
        h.session.submit(h.config, "old question", null, "old evidence", File("old.wav"))
        h.worker.runNext()
        val old = h.gateway.calls.single()
        val restored = StoredConversation("restored", 1, "saved", listOf(
            ChatMessage("user", "saved question"), ChatMessage("assistant", "saved answer")), "saved evidence")
        h.session.newConversation(restored)
        old.onDelta("late old delta")
        h.drainMain()

        assertEquals(restored.messages, h.session.messages)
        assertEquals("", h.session.partial)
        assertEquals("", h.session.question)
        assertEquals("", h.session.transcript)
        assertEquals("未附媒体", h.session.evidence)
        assertFalse(h.session.busy)
        assertFalse(h.session.canRetry)
        assertTrue(h.saved.isEmpty())

        h.session.submit(h.config, "new question", null, "new evidence")
        h.worker.runNext()
        h.drainMain()
        assertEquals("restored", h.saved.single().id)
        assertEquals(restored.messages, h.gateway.calls.last().history)
        assertEquals(4, h.saved.single().messages.size)
    }

    @Test fun cancellingInsideTranscriptionStopsExplanationAndIgnoresItsLateFailure() {
        val h = Harness()
        h.gateway.transcription = {
            h.session.cancel()
            h.session.newConversation()
            h.session.submit(h.config, "new question", null, "new evidence")
            "old transcription"
        }
        h.session.submit(h.config, "old question", null, "old evidence", File("old.wav"))
        h.worker.runNext()
        h.drainMain()
        assertTrue(h.gateway.calls.isEmpty())
        h.assertNewRequestUntouched()
        h.worker.runNext()
        h.drainMain()
        assertEquals(1, h.saved.size)
        assertEquals("new question", h.saved.single().messages.first().text)
    }

    @Test fun retryReusesSuccessfulTranscriptionAndOriginalConfigWithoutDuplicateHistory() {
        val h = Harness()
        var settings = h.config.copy(model = "original-model", instructions = "original instructions")
        val original = settings
        h.gateway.response = { call ->
            if (h.gateway.calls.size == 1) {
                call.onDelta("unfinished")
                throw ProviderException("explanation failed")
            }
            "complete answer"
        }
        h.session.submit(settings, "explain audio", null, "original recording", File("old.wav"))
        h.worker.runNext()
        h.drainMain()
        assertEquals(1, h.gateway.transcribeCount)
        assertEquals("transcribed words", h.session.transcript)
        assertTrue(h.session.canRetry)
        assertTrue(h.session.messages.isEmpty())
        assertTrue(h.saved.isEmpty())

        settings = settings.copy(model = "changed-model", instructions = "changed instructions")
        assertFalse(settings == original)
        h.session.retry()
        h.worker.runNext()
        h.drainMain()

        assertEquals(1, h.gateway.transcribeCount)
        assertEquals(2, h.gateway.calls.size)
        h.gateway.calls.forEach { call ->
            assertEquals(original, call.config)
            assertTrue(call.question.contains("transcribed words"))
            assertTrue(call.history.isEmpty())
        }
        assertEquals(h.gateway.calls[0].question, h.gateway.calls[1].question)
        assertEquals(listOf(ChatMessage("user", "explain audio", "original recording"), ChatMessage("assistant", "complete answer")), h.session.messages)
        assertEquals(1, h.saved.size)
        assertEquals("transcribed words", h.saved.single().transcript)
        assertEquals("original recording", h.saved.single().evidence)
        assertFalse(h.session.busy)
        assertFalse(h.session.canRetry)
    }

    private class Harness {
        val config = AppConfig(apiKey = "fixture-key")
        val gateway = FakeGateway()
        val worker = ManualExecutor()
        val posted = ArrayDeque<() -> Unit>()
        val saved = mutableListOf<StoredConversation>()
        val session = ChatSession(gateway, { posted.addLast(it) }, worker, { saved.add(it) }, {})

        fun drainMain() {
            var count = 0
            while (posted.isNotEmpty()) {
                check(++count <= 30) { "Unexpected callback loop" }
                posted.removeFirst().invoke()
            }
        }

        fun assertNewRequestUntouched() {
            assertTrue(session.busy)
            assertEquals("new question", session.question)
            assertEquals("new evidence", session.evidence)
            assertEquals("", session.partial)
            assertEquals("", session.transcript)
            assertEquals("正在连接…", session.status)
            assertTrue(session.messages.isEmpty())
            assertTrue(saved.isEmpty())
        }
    }

    private data class Call(val config: AppConfig, val history: List<ChatMessage>, val question: String,
        val onDelta: (String) -> Unit)

    private class FakeGateway : AiGateway {
        val calls = mutableListOf<Call>()
        var transcribeCount = 0
        var transcription: () -> String = { "transcribed words" }
        var response: (Call) -> String = { "answer" }

        override fun respond(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?,
            cancellation: RequestCancellation, onDelta: (String) -> Unit): String {
            assertEquals(null, imageDataUrl)
            return response(Call(config, history.toList(), question, onDelta).also { calls.add(it) })
        }
        override fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String {
            transcribeCount++
            return transcription()
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); queue.addLast(command) }
        fun runNext() { check(queue.isNotEmpty()); queue.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return queue.toMutableList().also { queue.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
