package com.alwayside.android

internal data class AudioQueueItem(val id: String, val title: String)

/** An immutable membership/order snapshot. Unavailable items are skipped at most once per search. */
internal class AudioPlaybackQueue(items: List<AudioQueueItem>, val source: String) {
    val items: List<AudioQueueItem> = items.distinctBy { it.id }.toList()
    var index: Int = 0; private set
    private val unavailable = mutableSetOf<Int>()
    val current: AudioQueueItem? get() = items.getOrNull(index)

    fun select(index: Int) { require(index in items.indices); this.index = index }
    fun rejectCurrent() { unavailable.add(index) }
    fun acceptCurrent() { unavailable.remove(index) }

    fun target(mode: AudioRepeat, delta: Int = 1, automatic: Boolean = false): Int? {
        if (items.isEmpty()) return null
        val start = if (automatic && mode == AudioRepeat.ONE) index else index + delta
        var position = start
        repeat(items.size) {
            if (mode == AudioRepeat.ALL) position = Math.floorMod(position, items.size)
            if (position !in items.indices) return null
            if (position !in unavailable) return position
            position += delta
        }
        return null
    }

    fun candidates(start: Int, delta: Int = 1, wrap: Boolean = false): List<Int> {
        require(delta == 1 || delta == -1)
        if (items.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var position = start
        repeat(items.size) {
            if (wrap) position = Math.floorMod(position, items.size)
            if (position !in items.indices) return result
            if (position !in unavailable) result.add(position)
            position += delta
        }
        return result
    }

    companion object {
        fun recordings(values: List<StoredConversation>, available: (String) -> Boolean): List<AudioQueueItem> =
            values.asSequence().filter { it.kind == "audio" && available(it.id) }
                .distinctBy { it.id }.map { AudioQueueItem(it.id, it.title.ifBlank { "原始音频" }) }.toList()
    }
}
