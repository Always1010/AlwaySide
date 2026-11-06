package com.alwayside.android

/** Bounded display data only; the complete body remains in its independently loaded record. */
internal object HistorySummary {
    fun of(value: StoredConversation): StoredConversation {
        val preview = value.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
            .ifBlank { value.messages.lastOrNull()?.text.orEmpty() }.ifBlank { value.evidence }
        return value.copy(
            title = ConversationTitle.resolve(value.title, value.messages), messages = emptyList(),
            evidence = preview.take(180), transcript = "", sourceText = "", status = value.status.take(180),
            tags = value.tags.toList(),
        )
    }

    fun restore(body: StoredConversation, summary: StoredConversation): StoredConversation = body.copy(
        favoriteAt = summary.favoriteAt, historyEnteredAt = summary.historyEnteredAt, tags = summary.tags,
    )
}
