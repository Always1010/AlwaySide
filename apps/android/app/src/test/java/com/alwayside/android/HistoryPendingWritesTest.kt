package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HistoryPendingWritesTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test(timeout = 10000) fun enqueueRemainsResponsiveWhileAnotherConversationIsBeingSaved() {
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val queue = HistoryPendingWrites(Any(), writer, ConversationUsage::pending)
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val secondSaved = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        try {
            queue.submit("a", {
                writing.countDown()
                check(finishWrite.await(5, TimeUnit.SECONDS))
            }, { failure.set(it) })
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            // A UI caller must enqueue the next snapshot without waiting for the first disk write.
            caller.submit {
                queue.submit("b", { secondSaved.countDown() }, { failure.set(it) })
            }.get(2, TimeUnit.SECONDS)
            finishWrite.countDown()
            assertTrue(secondSaved.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            finishWrite.countDown()
            writer.shutdownNow(); caller.shutdownNow()
        }
    }

    @Test fun latestSnapshotReplacesPendingCopiesAndDeletionOrClearCannotResurrectThem() {
        val worker = Worker()
        val queue = HistoryPendingWrites(Any(), worker)
        val written = mutableListOf<String>()
        fun submit(id: String, text: String) = queue.submit(id, { written.add(text) }, { throw it })
        submit("a", "old a"); submit("a", "latest a"); submit("b", "b")
        assertEquals(2, worker.tasks.size)
        queue.invalidate("a")
        submit("a", "explicitly recreated a")
        worker.drain()
        assertEquals(listOf("b", "explicitly recreated a"), written)
        submit("a", "discard a"); submit("b", "discard b")
        queue.invalidateAll()
        submit("c", "after clear")
        worker.drain()
        assertEquals(listOf("b", "explicitly recreated a", "after clear"), written)
    }

    @Test fun olderQueuedSnapshotCannotReplaceANewerSynchronousSaveAndOnlyLatestFailureIsReported() {
        val worker = Worker()
        val queue = HistoryPendingWrites(Any(), worker)
        var state = HistoryState()
        val old = StoredConversation("a", 1, "old", listOf(ChatMessage("assistant", "old answer")), "")
        val latest = old.copy(updatedAt = 2, title = "latest", messages = listOf(ChatMessage("assistant", "complete answer")))
        queue.submit("a", { state = HistoryCollection.save(state, old, 1) }, { throw it })
        state = HistoryCollection.save(state, latest, 2)
        worker.drain()
        assertEquals(listOf(latest.copy(historyEnteredAt = 2)), state.conversations)
        var oldFailure = false
        var latestFailure = false
        queue.submit("a", { error("outdated failure") }, { oldFailure = true })
        queue.submit("a", { error("latest failure") }, { latestFailure = true })
        worker.drain()
        assertFalse(oldFailure)
        assertTrue(latestFailure)
        val saved = mutableListOf<String>()
        queue.submit("a", { saved.add("newest") }, { throw it }, updatedAt = 3)
        queue.submit("a", { saved.add("late old snapshot") }, { throw it }, updatedAt = 1)
        worker.drain()
        assertEquals(listOf("newest"), saved)
    }
}
