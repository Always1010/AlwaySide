package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageCleanupTest {
    @Test fun failedFileRemainsQueuedWhileOtherFilesCompleteAndRetryIsIdempotent() {
        val pending = mutableListOf("image", "audio", "subtitles")
        val files = pending.toMutableSet()
        val failures = mutableListOf<String>()
        var rejectAudio = true
        fun drain() = StorageCleanup.run(pending.toList(), { name ->
            if (name == "audio" && rejectAudio) throw IOException()
            files.remove(name)
        }, { pending.remove(it) }, { failures.add(it) })
        drain()
        assertEquals(listOf("audio"), pending)
        assertEquals(setOf("audio"), files)
        assertEquals(listOf("audio"), failures)
        rejectAudio = false
        drain(); drain()
        assertTrue(pending.isEmpty()); assertTrue(files.isEmpty())
    }
    @Test fun failureRecordingCannotBlockRemainingJobs() {
        val completed = mutableListOf<Int>()
        StorageCleanup.run(listOf(1, 2), { if (it == 1) error("failed") }, { completed.add(it) }, { error("disk full") })
        assertEquals(listOf(2), completed)
    }
}
