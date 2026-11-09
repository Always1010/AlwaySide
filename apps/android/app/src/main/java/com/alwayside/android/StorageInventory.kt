package com.alwayside.android

internal data class StorageEntry(
    val path: String, val kind: String, val bytes: Long, val modified: Long,
    val owners: Set<String> = emptySet(), val inUse: Boolean = false,
    val idle: Boolean = false, val temporary: Boolean = false, val unknown: Boolean = false,
) {
    val safeCleanup: Boolean get() = temporary && !inUse && !unknown
    val optionalCleanup: Boolean get() = !inUse && !unknown && (idle || kind == "exports")
}

/** Physical files count once; a shared file is freed only when its last owner is selected. */
internal class StorageInventory(entries: List<StorageEntry>) {
    val entries = entries.distinctBy { it.path }
    val bytes get() = entries.sumOf { it.bytes }
    fun releasable(owners: Set<String>, kinds: Set<String>): Long = entries.filter {
        (it.kind in kinds || (it.kind == "reading_refs" && "reading" in kinds)) && !it.inUse && !it.unknown && it.owners.isNotEmpty() && owners.containsAll(it.owners)
    }.sumOf { it.bytes }
}
