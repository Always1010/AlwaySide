package com.alwayside.android

/** Presentation state is independent of the ordinary-history retention queue. */
internal class HistoryBrowse(
    var favorites: Boolean = false,
    var tag: String? = null,
    var untagged: Boolean = false,
    var query: String = "",
) {
    val canGoBack: Boolean get() = favorites || query.isNotBlank()

    fun showAll() { favorites = false; tag = null; untagged = false; query = "" }
    fun showFavorites(tag: String? = null, untagged: Boolean = false) {
        favorites = true; this.tag = tag; this.untagged = untagged; query = ""
    }

    fun back(): Boolean = when {
        query.isNotBlank() -> { query = ""; true }
        tag != null || untagged -> { showFavorites(); true }
        favorites -> { showAll(); true }
        else -> false
    }

    fun visible(values: List<StoredConversation>): List<StoredConversation> {
        val search = query.trim()
        return values.filter { value ->
            (!favorites || value.isFavorite) && (tag == null || tag in value.tags) &&
                (!untagged || value.tags.isEmpty()) && (search.isEmpty() || sequenceOf(
                    value.title, value.transcript, value.sourceText, value.tags.joinToString(" "),
                ).plus(value.messages.asSequence().map { it.text }).any { it.contains(search, ignoreCase = true) })
        }.let { filtered ->
            if (favorites) filtered.sortedByDescending { it.favoriteAt } else filtered
        }
    }
}
