package com.alwayside.android

internal data class HistoryState(
    val conversations: List<StoredConversation> = emptyList(),
    val tags: List<String> = emptyList(),
    val historyLimit: Int = HistoryArchive.DEFAULT_HISTORY_LIMIT,
)

/** Content timestamps and the ordinary-history retention queue are deliberately independent. */
internal object HistoryCollection {
    fun validateTag(name: String) {
        require(name.isNotBlank() && name == name.trim() && name.length <= 64) { "标签须为 1–64 个字符" }
    }

    fun retain(state: HistoryState, protectedId: String? = null): HistoryState {
        require(state.historyLimit > 0) { "历史数量须大于 0" }
        val ordinary = state.conversations.filterNot { it.isFavorite }
            .sortedWith(compareByDescending<StoredConversation> { it.historyEnteredAt.takeIf { time -> time > 0 } ?: it.updatedAt }
                .thenByDescending { it.updatedAt }.thenBy { it.id })
        val keep = ordinary.take(state.historyLimit).map { it.id }.toMutableSet()
        if (protectedId != null && ordinary.any { it.id == protectedId } && protectedId !in keep) {
            keep.remove(ordinary.take(state.historyLimit).last().id)
            keep.add(protectedId)
        }
        return state.copy(conversations = state.conversations.filter { it.isFavorite || it.id in keep }
            .sortedByDescending { it.updatedAt })
    }

    private fun queueTime(state: HistoryState, now: Long): Long =
        maxOf(1, now, (state.conversations.maxOfOrNull { it.historyEnteredAt } ?: 0).let {
            check(it < Long.MAX_VALUE) { "历史队列时间超出范围" }; it + 1
        })

    fun save(state: HistoryState, incoming: StoredConversation, now: Long): HistoryState {
        HistoryArchive.validate(incoming)
        val previous = state.conversations.find { it.id == incoming.id }
        if (previous != null && previous.updatedAt > incoming.updatedAt) return state
        val next = if (previous == null) incoming.copy(
            historyEnteredAt = queueTime(state, now), tags = incoming.tags.filter { it in state.tags },
        ) else incoming.copy(favoriteAt = previous.favoriteAt, tags = previous.tags,
            historyEnteredAt = if (incoming.updatedAt > previous.updatedAt) queueTime(state, now) else previous.historyEnteredAt)
        return retain(state.copy(conversations = state.conversations.filterNot { it.id == next.id } + next), next.id)
    }

    fun favorite(state: HistoryState, id: String, favorite: Boolean, now: Long): HistoryState {
        val value = state.conversations.find { it.id == id } ?: error("会话不存在")
        if (value.isFavorite == favorite) return state
        val next = value.copy(favoriteAt = if (favorite) maxOf(1, now) else 0,
            historyEnteredAt = if (favorite) value.historyEnteredAt else queueTime(state, now))
        return retain(state.copy(conversations = state.conversations.map { if (it.id == id) next else it }), id)
    }

    fun setTags(state: HistoryState, id: String, tags: List<String>): HistoryState {
        check(state.conversations.any { it.id == id }) { "会话不存在" }
        val unique = tags.distinct()
        require(unique.all { it in state.tags }) { "标签不存在" }
        return state.copy(conversations = state.conversations.map { if (it.id == id) it.copy(tags = unique) else it })
    }

    fun createTag(state: HistoryState, name: String): HistoryState {
        validateTag(name)
        require(name !in state.tags) { "标签已存在" }
        return state.copy(tags = state.tags + name)
    }

    fun renameTag(state: HistoryState, old: String, new: String): HistoryState {
        validateTag(new)
        require(old in state.tags) { "标签不存在" }
        if (old == new) return state
        require(new !in state.tags) { "标签已存在" }
        return state.copy(tags = state.tags.map { if (it == old) new else it },
            conversations = state.conversations.map { value -> value.copy(tags = value.tags.map { if (it == old) new else it }) })
    }

    fun deleteTag(state: HistoryState, name: String): HistoryState = state.copy(
        tags = state.tags.filterNot { it == name },
        conversations = state.conversations.map { it.copy(tags = it.tags.filterNot { tag -> tag == name }) },
    )
}
