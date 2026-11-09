package com.alwayside.android

/** Tokens represent actual readers/jobs, never an Activity retained after it becomes hidden. */
internal object ConversationUsage {
    val lock = Any()
    private val owners = mutableMapOf<Any, String>()
    private val pending = java.util.concurrent.ConcurrentHashMap<Any, String>()
    private val listeners = mutableMapOf<Any, (String) -> Unit>()
    fun ids(): Set<String> = synchronized(lock) { owners.values.toSet() + pending.values }
    fun active(owner: String) = synchronized(lock) { owner in owners.values || owner in pending.values }
    /** Enqueuing a history snapshot must remain responsive during another conversation's disk write. */
    fun pending(token: Any, owner: String?) {
        if (owner != null) pending[token] = owner
        else pending.remove(token)?.let { previous ->
            val callbacks = synchronized(lock) { if (!active(previous)) listeners.values.toList() else emptyList() }
            callbacks.forEach { it(previous) }
        }
    }
    class Changed : IllegalStateException("会话正在使用，未执行清理，请重试")
    fun onIdle(token: Any, action: (String) -> Unit) = synchronized(lock) { listeners[token] = action }
    fun update(token: Any, owner: String?) {
        val notification = synchronized(lock) {
            val previous = owners.remove(token)
            if (!owner.isNullOrEmpty()) owners[token] = owner
            if (previous != null && previous != owner && previous !in owners.values)
                previous to listeners.values.toList() else null
        }
        notification?.let { (previous, callbacks) -> callbacks.forEach { it(previous) } }
    }
    fun <T> hold(owner: String, action: () -> T): T {
        val token = Any()
        update(token, owner)
        try { return action() } finally { update(token, null) }
    }
    private class Work(owner: String, private val action: () -> Unit) : Runnable {
        private val state = java.util.concurrent.atomic.AtomicInteger(0)
        init { update(this, owner) }
        override fun run() {
            if (!state.compareAndSet(0, 1)) return
            try { action() } finally { update(this, null) }
        }
        fun cancelQueued() { if (state.compareAndSet(0, 2)) update(this, null) }
    }
    fun execute(worker: java.util.concurrent.Executor, owner: String, action: () -> Unit) {
        val job = Work(owner, action)
        try { worker.execute(job) } catch (error: Exception) { job.cancelQueued(); throw error }
    }
    fun cancelQueued(jobs: List<Runnable>) { jobs.forEach { (it as? Work)?.cancelQueued() } }
}
