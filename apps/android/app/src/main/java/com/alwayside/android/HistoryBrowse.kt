package com.alwayside.android

import java.time.ZoneId

/** Presentation state is independent of the ordinary-history retention queue. */
internal class HistoryBrowse(
    var favorites: Boolean = false,
    var tag: String? = null,
    var untagged: Boolean = false,
    var query: String = "",
    var dateRange: HistoryDateRange? = null,
    var page: Int = 0,
) {
    val canGoBack: Boolean get() = favorites || query.isNotBlank() || dateRange != null

    fun showAll() { favorites = false; tag = null; untagged = false; query = ""; dateRange = null; page = 0 }
    fun showFavorites(tag: String? = null, untagged: Boolean = false) {
        favorites = true; this.tag = tag; this.untagged = untagged; query = ""; dateRange = null; page = 0
    }

    fun back(): Boolean = when {
        query.isNotBlank() -> { query = ""; page = 0; true }
        dateRange != null -> { dateRange = null; page = 0; true }
        tag != null || untagged -> { showFavorites(); true }
        favorites -> { showAll(); true }
        else -> false
    }

    companion object {
        fun matchesText(value: StoredConversation, search: String): Boolean = sequenceOf(
            value.title, value.transcript, value.sourceText, value.tags.joinToString(" "),
        ).plus(value.messages.asSequence().map { it.text }).any { it.contains(search, ignoreCase = true) }
    }

    fun visible(values: List<StoredConversation>): List<StoredConversation> {
        val search = query.trim()
        val zone = ZoneId.systemDefault()
        return values.filter { value ->
            (!favorites || value.isFavorite) && (tag == null || tag in value.tags) &&
                (!untagged || value.tags.isEmpty()) && (dateRange?.contains(value.updatedAt, zone) != false) &&
                (search.isEmpty() || matchesText(value, search))
        }.let { filtered ->
            if (favorites) filtered.sortedByDescending { it.favoriteAt } else filtered
        }
    }
}

/** Read at most one complete body at a time, retain only matching summaries, and stop obsolete queries. */
internal object HistorySearch {
    fun run(
        browse: HistoryBrowse,
        summaries: List<StoredConversation>,
        load: (String) -> StoredConversation?,
        cancelled: () -> Boolean = { false },
    ): List<StoredConversation> {
        val scope = HistoryBrowse(browse.favorites, browse.tag, browse.untagged, dateRange = browse.dateRange)
        val candidates = scope.visible(summaries)
        val query = browse.query.trim()
        if (query.isEmpty()) return candidates
        return candidates.filter { summary ->
            if (cancelled()) throw java.util.concurrent.CancellationException()
            HistoryBrowse.matchesText(summary, query) || load(summary.id)?.let {
                HistoryBrowse.matchesText(it, query)
            } == true
        }
    }
}
