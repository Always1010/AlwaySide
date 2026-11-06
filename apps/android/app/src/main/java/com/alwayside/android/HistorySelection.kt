package com.alwayside.android

/** Selection is identified by archive ID, never by a row's current position. */
internal class HistorySelection(restored: List<String> = emptyList(), active: Boolean = restored.isNotEmpty()) {
    private val selected = linkedSetOf<String>().apply { addAll(restored.filter { it.isNotBlank() }) }
    var selecting: Boolean = active || selected.isNotEmpty()
        private set
    fun contains(id: String): Boolean = id in selected
    fun snapshot(): List<String> = selected.toList()
    fun select(id: String) { if (id.isNotBlank()) { selecting = true; selected.add(id) } }
    fun toggle(id: String) { if (!selected.remove(id)) select(id) }
    fun retain(existing: Collection<String>) { selected.retainAll(existing.toSet()) }
    fun allSelected(ids: Collection<String>): Boolean = ids.isNotEmpty() && ids.all(::contains)
    fun toggleAll(ids: Collection<String>) {
        selecting = true
        if (allSelected(ids)) selected.clear() else { selected.clear(); selected.addAll(ids.filter { it.isNotBlank() }) }
    }
    fun cancel(): Boolean = selecting.also { selected.clear(); selecting = false }
}

/** The confirmation owns a copy: later selection changes cannot alter deletion targets. */
internal class HistoryDeletion(ids: List<String>) {
    val ids: List<String> = ids.distinct().toList()
    fun execute(delete: (String) -> Unit): List<String> = ids.filter { id ->
        runCatching { delete(id) }.isFailure
    }
}
