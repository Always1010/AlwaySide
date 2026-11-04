package com.alwayside.android

/** Selection is identified by archive ID, never by a row's current position. */
internal class HistorySelection(restored: List<String> = emptyList()) {
    private val selected = linkedSetOf<String>().apply { addAll(restored.filter { it.isNotBlank() }) }
    val selecting: Boolean get() = selected.isNotEmpty()
    fun contains(id: String): Boolean = id in selected
    fun snapshot(): List<String> = selected.toList()
    fun select(id: String) { if (id.isNotBlank()) selected.add(id) }
    fun toggle(id: String) { if (!selected.remove(id)) select(id) }
    fun retain(existing: Collection<String>) { selected.retainAll(existing.toSet()) }
    fun cancel(): Boolean = selecting.also { selected.clear() }
}

/** The confirmation owns a copy: later selection changes cannot alter deletion targets. */
internal class HistoryDeletion(ids: List<String>) {
    val ids: List<String> = ids.distinct().toList()
    fun execute(delete: (String) -> Unit): List<String> = ids.filter { id ->
        runCatching { delete(id) }.isFailure
    }
}
