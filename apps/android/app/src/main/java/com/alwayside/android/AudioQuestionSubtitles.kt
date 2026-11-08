package com.alwayside.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Immutable choice for one audio request; implementations return source text before translation finishes. */
internal interface AudioQuestionSubtitles {
    val cacheKey: String
    fun isCurrent(): Boolean
    fun transcribe(cancellation: RequestCancellation): String
}

/** Wait on a worker, without holding UI/file locks or delaying cancellation behind a queued recording. */
internal fun <T> awaitSubtitleOriginal(future: CompletableFuture<T>, cancellation: RequestCancellation,
    current: () -> Boolean = { true }): T {
    while (true) {
        cancellation.throwIfCancelled()
        if (!current()) throw CancellationException("字幕设置已改变，请重新发送问题")
        try { return future.get(100, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { /* Check cancellation even when another recording is ahead in the queue. */ }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
    }
}

internal object SubtitleQuestionTranscript {
    fun text(bundle: SubtitleBundle): String {
        require(bundle.transcriptionComplete && bundle.cues.isNotEmpty()) { "字幕原文尚未完整生成" }
        val text = StringBuilder()
        bundle.cues.forEach { cue ->
            require(text.length.toLong() + cue.text.length + (if (text.isEmpty()) 0 else 1) <= ChatPrompt.MAX_TRANSCRIPT_CHARS) {
                "字幕原文过长，请缩短录音后提问"
            }
            if (text.isNotEmpty()) text.append('\n')
            text.append(cue.text)
        }
        return text.toString()
    }
}
