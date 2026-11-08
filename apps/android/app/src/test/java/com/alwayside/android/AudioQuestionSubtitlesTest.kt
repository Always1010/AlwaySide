package com.alwayside.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AudioQuestionSubtitlesTest {
    @Test fun completeSourceIsUsableBeforeChineseTranslationExistsAndRetainsOrder() {
        val bundle = SubtitleBundle("hash", SubtitleMethod.WHISPER, 2000, "signature",
            cues = listOf(SubtitleCue(1, 0, 1000, "First."), SubtitleCue(2, 1000, 2000, "Second.")),
            transcriptionComplete = true)
        assertFalse(bundle.bilingualComplete)
        assertEquals("First.\nSecond.", SubtitleQuestionTranscript.text(bundle))
        assertThrows(IllegalArgumentException::class.java) { SubtitleQuestionTranscript.text(bundle.copy(transcriptionComplete = false)) }
        assertThrows(IllegalArgumentException::class.java) { SubtitleQuestionTranscript.text(bundle.copy(
            cues = listOf(SubtitleCue(1, 0, 1000, "a".repeat(ChatPrompt.MAX_TRANSCRIPT_CHARS + 1))))) }
    }

    @Test fun sourceWaitPropagatesFailuresAndHonoursCancellationAndSettingsChanges() {
        val future = CompletableFuture<String>()
        val failure = ProviderException("recognition failed")
        future.completeExceptionally(failure)
        assertSame(failure, assertThrows(ProviderException::class.java) { awaitSubtitleOriginal(future, RequestCancellation()) })
        val cancellation = RequestCancellation().apply { cancel() }
        assertThrows(CancellationException::class.java) { awaitSubtitleOriginal(CompletableFuture<String>(), cancellation) }
        assertThrows(CancellationException::class.java) { awaitSubtitleOriginal(CompletableFuture<String>(), RequestCancellation()) { false } }
        val original = CompletableFuture.completedFuture("English")
        assertFalse(original.completeExceptionally(ProviderException("translation failed")))
        assertEquals("English", awaitSubtitleOriginal(original, RequestCancellation()))
    }
}
