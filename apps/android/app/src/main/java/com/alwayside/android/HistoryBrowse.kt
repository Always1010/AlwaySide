package com.alwayside.android

import java.time.ZoneId

/** Presentation state is independent of the ordinary-history retention queue. */
internal class HistoryBrowse(
    var favorites: Boolean = false,
    var tag: String? = null,
    var untagged: Boolean = false,
    var query: String = "",
    var dateRange: HistoryDateRange? = null,
) {
    val canGoBack: Boolean get() = favorites || query.isNotBlank() || dateRange != null

    fun showAll() { favorites = false; tag = null; untagged = false; query = ""; dateRange = null }
    fun showFavorites(tag: String? = null, untagged: Boolean = false) {
        favorites = true; this.tag = tag; this.untagged = untagged; query = ""; dateRange = null
    }

    fun back(): Boolean = when {
        query.isNotBlank() -> { query = ""; true }
        dateRange != null -> { dateRange = null; true }
        tag != null || untagged -> { showFavorites(); true }
        favorites -> { showAll(); true }
        else -> false
    }

    fun visible(values: List<StoredConversation>): List<StoredConversation> {
        val search = query.trim()
        val zone = ZoneId.systemDefault()
        return values.filter { value ->
            (!favorites || value.isFavorite) && (tag == null || tag in value.tags) &&
                (!untagged || value.tags.isEmpty()) && (dateRange?.contains(value.updatedAt, zone) != false) &&
                (search.isEmpty() || sequenceOf(
                    value.title, value.transcript, value.sourceText, value.tags.joinToString(" "),
                ).plus(value.messages.asSequence().map { it.text }).any { it.contains(search, ignoreCase = true) })
        }.let { filtered ->
            if (favorites) filtered.sortedByDescending { it.favoriteAt } else filtered
        }
    }
}
