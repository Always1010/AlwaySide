package com.alwayside.android

/** A failed item must remain retryable without preventing unrelated deletions. */
internal object StorageCleanup {
    fun <T> run(jobs: List<T>, delete: (T) -> Unit, complete: (T) -> Unit, failed: (T) -> Unit) {
        jobs.forEach { job ->
            try { delete(job); complete(job) }
            catch (_: Exception) { runCatching { failed(job) } }
        }
    }
}
