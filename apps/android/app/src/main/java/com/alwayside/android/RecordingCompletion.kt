package com.alwayside.android

import java.util.concurrent.atomic.AtomicReference

internal enum class RecordingEndReason { MANUAL, LIMIT, CANCELLED }

/** The first stop wins; cancellation may always discard a clip not yet delivered. */
internal class RecordingCompletion {
    private val end = AtomicReference<RecordingEndReason?>(null)
    val reason: RecordingEndReason? get() = end.get()

    fun stop(discard: Boolean) {
        if (discard) end.set(RecordingEndReason.CANCELLED)
        else end.compareAndSet(null, RecordingEndReason.MANUAL)
    }

    fun shouldStop(bytes: Int, elapsedMillis: Long, maxBytes: Int, maxMillis: Long): Boolean {
        if (bytes >= maxBytes || elapsedMillis >= maxMillis)
            end.compareAndSet(null, RecordingEndReason.LIMIT)
        return end.get() != null
    }
}

internal enum class RecordingLimitAction(val value: String, val title: String) {
    DISCARD("discard", "丢弃录音（默认）"),
    PROMPT("prompt", "保留录音并提示"),
    PROCESS("process", "自动处理录音");

    companion object {
        val choices: Map<String, String> get() = entries.associate { it.value to it.title }
        fun from(value: String?): RecordingLimitAction = entries.firstOrNull { it.value == value } ?: DISCARD
    }
}

internal enum class RecordingDisposition { DISCARD, PROMPT, PROCESS, READY }

internal fun recordingDisposition(reachedLimit: Boolean, action: RecordingLimitAction,
                                  autoAskAudio: Boolean, manualQuestion: Boolean,
                                  useDefaultQuestion: Boolean = true): RecordingDisposition =
    if (reachedLimit) when (action) {
        RecordingLimitAction.DISCARD -> RecordingDisposition.DISCARD
        RecordingLimitAction.PROMPT -> RecordingDisposition.PROMPT
        RecordingLimitAction.PROCESS -> if (useDefaultQuestion) RecordingDisposition.PROCESS else RecordingDisposition.READY
    } else if (useDefaultQuestion && autoAskAudio && !manualQuestion) RecordingDisposition.PROCESS else RecordingDisposition.READY
