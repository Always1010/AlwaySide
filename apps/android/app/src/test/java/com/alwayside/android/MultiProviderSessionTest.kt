package com.alwayside.android

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session regressions use in-memory gateways and queued callbacks, never Android media or a paid API. */
class MultiProviderSessionTest {
    private val asr = ConnectionProfile("asr-main", "独立转写", ProviderKind.OPENAI, ApiProtocol.RESPONSES,
        "https://asr.example/v1", apiKey = "fixture-asr-key", transcriptionModel = "asr-model", credentialRevision = 7)
    private val otherAnswer = ConnectionProfile("answer-two", "千问回答", ProviderKind.QWEN, ApiProtocol.CHAT_COMPLETIONS,
        "https://qwen.example/compatible-mode/v1", apiKey = "fixture-other-key", model = "qwen-plus", credentialRevision = 3)
    private val config = AppConfig(apiKey = "fixture-answer-key", providerKind = ProviderKind.CLAUDE,
        apiProtocol = ApiProtocol.CLAUDE, baseUrl = "https://claude.example/v1", model = "claude-model",
        connectionId = "answer-one", connectionName = "Claude 回答", credentialRevision = 4,
        transcriptionConnectionId = asr.id, connections = listOf(asr, otherAnswer), reasoning = "", maxOutputTokens = 0)

    @Test fun independentAsrCacheSurvivesAnswerProviderModelAndCredentialChanges() {
        val h = Harness(config)
        h.submitAudio(config)
        h.completeWork()
        val switched = ProviderConnections.select(config, otherAnswer.id)
        h.submitAudio(switched)
        h.completeWork()
        val modifiedAnswer = switched.copy(model = "another-answer-model", credentialRevision = 99, apiKey = "new-answer-key")
        h.submitAudio(modifiedAnswer)
        h.completeWork()

        assertEquals(1, h.gateway.transcriptions.size)
        val target = ProviderConnections.transcription(h.gateway.transcriptions.single())
        assertEquals(asr.id, target.connectionId)
        assertEquals(asr.apiKey, target.apiKey)
        assertEquals(asr.transcriptionModel, target.transcriptionModel)
        assertEquals(listOf(config.connectionId, otherAnswer.id, otherAnswer.id), h.gateway.answers.map { it.config.connectionId })
        assertEquals(listOf(ProviderKind.CLAUDE, ProviderKind.QWEN, ProviderKind.QWEN), h.gateway.answers.map { it.config.providerKind })
        assertEquals("another-answer-model", h.gateway.answers.last().config.model)
        h.gateway.answers.forEach { assertTrue(it.question.contains("<transcript>\ntranscribed words\n</transcript>")) }
    }

    @Test fun changingTheTranscriberIdModelOrCredentialRevisionInvalidatesItsAudioCache() {
        val variants = listOf(
            asr.copy(id = "asr-second"),
            asr.copy(transcriptionModel = "another-asr-model"),
            asr.copy(credentialRevision = asr.credentialRevision + 1))
        variants.forEach { replacement ->
            val h = Harness(config)
            h.submitAudio(config)
            h.completeWork()
            val changed = config.copy(transcriptionConnectionId = replacement.id,
                connections = config.connections.filterNot { it.id == asr.id } + replacement)
            h.submitAudio(changed)
            h.completeWork()
            assertEquals("Transcriber $replacement", 2, h.gateway.transcriptions.size)
            val actual = ProviderConnections.transcription(h.gateway.transcriptions.last())
            assertEquals(replacement.id, actual.connectionId)
            assertEquals(replacement.transcriptionModel, actual.transcriptionModel)
            assertEquals(replacement.credentialRevision, actual.credentialRevision)
        }
    }

    @Test fun transcriptOnlyUsesAnIndependentAsrWithoutAnAnswerKey() {
        val transcriptOnly = config.copy(apiKey = "", audioOutput = "transcript")
        val h = Harness(transcriptOnly)
        h.submitAudio(transcriptOnly, "只转写录音")
        h.completeWork()

        assertEquals(1, h.gateway.transcriptions.size)
        assertEquals(asr.apiKey, ProviderConnections.transcription(h.gateway.transcriptions.single()).apiKey)
        assertTrue(h.gateway.answers.isEmpty())
        assertEquals("transcribed words", h.session.transcript)
        assertEquals(listOf(ChatMessage("user", "只转写录音", "recording"), ChatMessage("assistant", "transcribed words")), h.session.messages)
        assertEquals("已完成", h.session.status)
        assertFalse(h.session.canRetry)
    }

    @Test fun failedAnswerRetryReusesTranscriptAndOriginalBackendEvenAfterSelectingAnotherAnswer() {
        val h = Harness(config)
        h.gateway.response = { if (h.gateway.answers.size == 1) throw ProviderException("answer failed") else "complete answer" }
        h.submitAudio(config, "explain this recording")
        h.completeWork()
        assertTrue(h.session.canRetry)
        assertTrue(h.session.messages.isEmpty())
        assertEquals("transcribed words", h.session.transcript)

        // Revoking the ASR connection after its successful upload must not re-upload the recording on answer retry.
        h.current = ProviderConnections.remove(ProviderConnections.select(config, otherAnswer.id), asr.id)
        h.session.retry()
        assertTrue(h.session.busy)
        assertEquals(listOf(true to false), h.credentialChecks.map { it.second to it.third })
        h.completeWork()

        assertEquals(1, h.gateway.transcriptions.size)
        assertEquals(2, h.gateway.answers.size)
        val first = h.gateway.answers.first()
        val second = h.gateway.answers.last()
        assertEquals(first.config, second.config)
        assertEquals(config.connectionId, second.config.connectionId)
        assertEquals(config.providerKind, second.config.providerKind)
        assertEquals(first.question, second.question)
        assertEquals(first.history, second.history)
        assertEquals(listOf(ChatMessage("user", "explain this recording", "recording"), ChatMessage("assistant", "complete answer")), h.session.messages)
        assertEquals(1, h.saved.size)
        assertFalse(h.session.canRetry)
    }

    @Test fun pausedSearchRequiresAnExplicitContinuationOnTheOriginalBackend() {
        val continuation = listOf(mapOf<String, Any?>("role" to "assistant", "content" to listOf(
            mapOf("type" to "server_tool_use", "id" to "search-1", "name" to "web_search", "input" to mapOf("query" to "latest")))))
        val h = Harness(config.copy(webSearch = true))
        h.gateway.response = { call ->
            if (h.gateway.answers.size == 1) {
                call.onDelta("Before search. ")
                throw ProviderPausedException(continuation)
            }
            call.onDelta("finished search")
            "finished search"
        }
        h.session.submit(h.current, "research this", null, "text")
        h.completeWork()

        assertFalse(h.session.busy)
        assertTrue(h.session.canRetry)
        assertEquals("继续生成", h.session.retryLabel)
        assertEquals(1, h.gateway.answers.size)
        assertEquals(0, h.worker.pending)
        assertTrue(h.session.messages.isEmpty())
        assertEquals("Before search. ", h.session.partial)
        assertTrue(h.session.status.contains("再次调用原服务"))

        val original = h.gateway.answers.single()
        h.current = ProviderConnections.select(h.current, otherAnswer.id)
        h.session.retry()
        assertEquals("Before search. ", h.session.partial)
        assertEquals(1, h.gateway.answers.size) // Retry only queued work; no automatic fallback or parallel call.
        h.completeWork()
        val resumed = h.gateway.answers.last()
        assertEquals(original.config.copy(continuationMessages = continuation), resumed.config)
        assertEquals(original.question, resumed.question)
        assertEquals(original.history, resumed.history)
        assertEquals(continuation, resumed.config.continuationMessages)
        assertEquals("Before search. finished search", h.session.messages.last().text)
        assertFalse(h.session.canRetry)
    }

    @Test fun repeatedSearchPausesPreserveEveryPrefixAndStopAfterThreeExplicitContinuations() {
        val h = Harness(config.copy(webSearch = true))
        h.gateway.response = { call ->
            val round = h.gateway.answers.size
            call.onDelta("step $round ")
            throw ProviderPausedException(listOf(mapOf("role" to "assistant", "round" to round)))
        }
        h.session.submit(h.current, "research", null, "text")
        h.completeWork()
        assertEquals("step 1 ", h.session.partial)
        repeat(3) { continuation ->
            assertTrue(h.session.canRetry)
            assertEquals("继续生成", h.session.retryLabel)
            assertEquals(0, h.worker.pending)
            h.session.retry()
            h.completeWork()
            assertEquals((1..continuation + 2).joinToString("") { "step $it " }, h.session.partial)
        }
        assertEquals(4, h.gateway.answers.size)
        assertFalse(h.session.busy)
        assertFalse(h.session.canRetry)
        assertTrue(h.session.messages.isEmpty())
        assertTrue(h.session.status.contains("已达 3 次"))
        assertTrue(h.session.status.contains("仍未完成"))
        h.gateway.answers.drop(1).forEachIndexed { index, call ->
            assertEquals(listOf(mapOf("role" to "assistant", "round" to index + 1)), call.config.continuationMessages)
        }
        h.session.retry()
        assertEquals(0, h.worker.pending)
        assertEquals(4, h.gateway.answers.size)
    }

    @Test fun revokedCredentialsPreventRetryBeforeAnyGatewayCall() {
        val h = Harness(config)
        h.gateway.response = { throw ProviderException("failed") }
        h.session.submit(config, "question", null, "text")
        h.completeWork()
        assertTrue(h.session.canRetry)
        h.forceCredentialsCurrent = false
        h.session.retry()

        assertFalse(h.session.busy)
        assertFalse(h.session.canRetry)
        assertEquals(0, h.worker.pending)
        assertEquals(1, h.gateway.answers.size)
        assertEquals("连接或密钥已更新，请重新发送问题", h.session.status)
        assertEquals(listOf(true to false), h.credentialChecks.map { it.second to it.third })
    }

    @Test fun failedTranscriptionRetryRequiresOnlyAsrCredentialsInTranscriptMode() {
        val transcriptOnly = config.copy(apiKey = "", audioOutput = "transcript")
        val h = Harness(transcriptOnly)
        h.gateway.transcription = { if (h.gateway.transcriptions.size == 1) throw ProviderException("ASR failed") else "recovered transcript" }
        h.submitAudio(transcriptOnly)
        h.completeWork()
        h.session.retry()
        assertEquals(listOf(false to true), h.credentialChecks.map { it.second to it.third })
        h.completeWork()
        assertEquals(2, h.gateway.transcriptions.size)
        assertTrue(h.gateway.answers.isEmpty())
        assertEquals("recovered transcript", h.session.messages.last().text)
    }

    @Test fun sessionRetryRejectsDeletedOrRevisedAnswerCredentialsFromCurrentSettings() {
        val selected = ProviderConnections.select(config, otherAnswer.id)
        val revoked = listOf(
            ProviderConnections.remove(selected, config.connectionId),
            selected.copy(connections = selected.connections.map { if (it.id == config.connectionId) it.copy(credentialRevision = it.credentialRevision + 1) else it }),
            selected.copy(connections = selected.connections.map { if (it.id == config.connectionId) it.copy(apiKey = "") else it }))
        revoked.forEach { current ->
            val h = Harness(config)
            h.gateway.response = { throw ProviderException("failed") }
            h.session.submit(config, "question", null, "text")
            h.completeWork()
            h.current = current
            h.session.retry()
            assertFalse(h.session.canRetry)
            assertFalse(h.session.busy)
            assertEquals(0, h.worker.pending)
            assertEquals(1, h.gateway.answers.size)
            assertEquals("连接或密钥已更新，请重新发送问题", h.session.status)
        }
    }

    @Test fun failedAsrCannotRetryWithADeletedTranscriptionConnection() {
        val h = Harness(config.copy(apiKey = "", audioOutput = "transcript"))
        h.gateway.transcription = { throw ProviderException("ASR failed") }
        h.submitAudio(h.current)
        h.completeWork()
        h.current = ProviderConnections.remove(h.current, asr.id)
        h.session.retry()
        assertFalse(h.session.canRetry)
        assertEquals(0, h.worker.pending)
        assertEquals(1, h.gateway.transcriptions.size)
        assertTrue(h.gateway.answers.isEmpty())
    }

    @Test fun credentialsAreCheckedByOriginalProfileRatherThanTheCurrentlySelectedAnswer() {
        val selected = ProviderConnections.select(config, otherAnswer.id)
        assertTrue(ProviderConnections.credentialsCurrent(config, selected, answer = true, audio = true))
        val selectedAsrChanged = selected.copy(transcriptionConnectionId = otherAnswer.id)
        assertTrue(ProviderConnections.credentialsCurrent(config, selectedAsrChanged, answer = true, audio = true))
        val deletedAnswer = ProviderConnections.remove(selected, config.connectionId)
        assertFalse(ProviderConnections.credentialsCurrent(config, deletedAnswer, answer = true, audio = false))
        assertTrue(ProviderConnections.credentialsCurrent(config, deletedAnswer, answer = false, audio = true))
    }

    @Test fun credentialRevisionKeyRemovalAndEndpointChangesInvalidateTheRequiredOriginalConnection() {
        val selected = ProviderConnections.select(config, otherAnswer.id)
        val changes: List<(ConnectionProfile) -> ConnectionProfile> = listOf(
            { it.copy(credentialRevision = it.credentialRevision + 1) },
            { it.copy(apiKey = "replacement-key") },
            { it.copy(apiKey = "") },
            { it.copy(baseUrl = "https://replacement.example/v1") })
        changes.forEach { transform ->
            val answerChanged = selected.copy(connections = selected.connections.map { if (it.id == config.connectionId) transform(it) else it })
            assertFalse(ProviderConnections.credentialsCurrent(config, answerChanged, answer = true, audio = false))
            assertTrue(ProviderConnections.credentialsCurrent(config, answerChanged, answer = false, audio = true))
            val asrChanged = selected.copy(connections = selected.connections.map { if (it.id == asr.id) transform(it) else it })
            assertFalse(ProviderConnections.credentialsCurrent(config, asrChanged, answer = false, audio = true))
            assertTrue(ProviderConnections.credentialsCurrent(config, asrChanged, answer = true, audio = false))
        }
        val deletedAsr = ProviderConnections.remove(selected, asr.id)
        assertFalse(ProviderConnections.credentialsCurrent(config, deletedAsr, answer = false, audio = true))
        assertTrue(ProviderConnections.credentialsCurrent(config, deletedAsr, answer = true, audio = false))
    }

    private class Harness(initial: AppConfig) {
        var current = initial
        var forceCredentialsCurrent: Boolean? = null
        val credentialChecks = mutableListOf<Triple<AppConfig, Boolean, Boolean>>()
        val gateway = FakeGateway()
        val worker = ManualExecutor()
        private val callbacks = ArrayDeque<() -> Unit>()
        private val archive = linkedMapOf<String, StoredConversation>()
        val saved: List<StoredConversation> get() = archive.values.toList()
        val session = ChatSession(gateway, { callbacks.addLast(it) }, worker, { archive[it.id] = it }, {},
            { requested, answer, audio ->
                credentialChecks.add(Triple(requested, answer, audio))
                forceCredentialsCurrent ?: ProviderConnections.credentialsCurrent(requested, current, answer, audio)
            })

        fun submitAudio(config: AppConfig, question: String = "explain audio") {
            current = config
            session.submit(config, question, null, "recording", File("multi-provider-session-fixture-unused.wav"))
        }

        fun completeWork() {
            worker.runNext()
            var count = 0
            while (callbacks.isNotEmpty()) {
                check(++count <= 30) { "Unexpected callback loop" }
                callbacks.removeFirst().invoke()
            }
        }
    }

    private data class AnswerCall(val config: AppConfig, val history: List<ChatMessage>, val question: String,
        val onDelta: (String) -> Unit)

    private class FakeGateway : AiGateway {
        val answers = mutableListOf<AnswerCall>()
        val transcriptions = mutableListOf<AppConfig>()
        var response: (AnswerCall) -> String = { "answer" }
        var transcription: () -> String = { "transcribed words" }
        override fun respond(config: AppConfig, history: List<ChatMessage>, question: String, imageDataUrl: String?,
            cancellation: RequestCancellation, onDelta: (String) -> Unit): String {
            assertEquals(null, imageDataUrl)
            return response(AnswerCall(config, history.toList(), question, onDelta).also { answers.add(it) })
        }
        override fun transcribe(config: AppConfig, file: File, cancellation: RequestCancellation): String {
            transcriptions.add(config)
            return transcription()
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var stopped = false
        val pending: Int get() = queue.size
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
