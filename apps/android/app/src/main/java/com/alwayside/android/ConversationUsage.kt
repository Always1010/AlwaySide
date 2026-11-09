package com.alwayside.android

/** Tokens represent actual readers/jobs, never an Activity retained after it becomes hidden. */
internal object ConversationUsage {
    val lock = Any()
    private val owners = mutableMapOf<Any, String>()
    private val listeners = mutableMapOf<Any, (String) -> Unit>()
    fun ids(): Set<String> = synchronized(lock) { owners.values.toSet() }
    fun active(owner: String) = synchronized(lock) { owner in owners.values }
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
}
