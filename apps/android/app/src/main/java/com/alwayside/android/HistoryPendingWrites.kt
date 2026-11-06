package com.alwayside.android

import java.util.concurrent.Executor

/** Invalidate queued snapshots when their conversation or entire collection is explicitly removed. */
internal class HistoryPendingWrites(private val lock: Any, private val worker: Executor) {
    private val queueLock = Any()
    private val tokens = mutableMapOf<String, Any>()
    private class Pending(val token: Any, var updatedAt: Long, var save: () -> Unit, var failed: (Exception) -> Unit)
    private val pending = mutableMapOf<String, Pending>()

    fun submit(id: String, save: () -> Unit, failed: (Exception) -> Unit, updatedAt: Long = 0) {
        val entry = synchronized(queueLock) {
            val token = tokens.getOrPut(id) { Any() }
            pending[id]?.takeIf { it.token === token }?.let {
                // Intermediate request-status snapshots need not queue multiple copies of a long conversation.
                if (updatedAt >= it.updatedAt) {
                    it.updatedAt = updatedAt; it.save = save; it.failed = failed
                }
                return
            }
            Pending(token, updatedAt, save, failed).also { pending[id] = it }
        }
        worker.execute {
            try {
                synchronized(lock) {
                    val current = synchronized(queueLock) {
                        if (pending[id] === entry) {
                            pending.remove(id)
                            entry.takeIf { tokens[id] === it.token }
                        } else null
                    }
                    // Keep the repository stable while saving, without blocking new enqueue operations.
                    current?.save?.invoke()
                }
            } catch (error: Exception) { entry.failed(error) }
        }
    }

    /** The repository owner holds its write lock before invalidating pending snapshots. */
    fun invalidate(id: String) = synchronized(queueLock) { tokens.remove(id); pending.remove(id); Unit }
    fun invalidateAll() = synchronized(queueLock) { tokens.clear(); pending.clear() }
}
