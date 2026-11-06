package com.alwayside.android

import java.util.UUID

/** Each write is atomic. Immutable records become visible together at the manifest commit. */
internal interface HistoryFileAccess {
    fun read(name: String, maxBytes: Int): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun records(): List<String>
}

internal class HistoryFiles(
    private val files: HistoryFileAccess,
    private val encode: (Map<String, Any?>) -> ByteArray,
    private val decode: (ByteArray) -> Map<String, Any?>,
) {
    private var references = emptyMap<String, String>()
    private var loaded: HistoryState? = null

    fun load(): HistoryState {
        loaded?.let { return it }
        val manifest = files.read(MANIFEST, HistoryArchive.MAX_FILE_BYTES)
        if (manifest == null) {
            val legacy = files.read(LEGACY, HistoryArchive.MAX_FILE_BYTES)
            val values = legacy?.let { HistoryArchive.decode(decode(it)) }.orEmpty().map {
                it.copy(historyEnteredAt = it.historyEnteredAt.takeIf { time -> time > 0 } ?: it.updatedAt.coerceAtLeast(0))
            }
            val state = HistoryState(values, values.flatMap { it.tags }.distinct())
            references = emptyMap()
            // Even an empty migration leaves a manifest, so failed legacy deletion can never resurrect data.
            commit(HistoryState(), state, force = true)
            if (legacy != null) files.delete(LEGACY)
            return checkNotNull(loaded)
        }
        val root = decode(manifest)
        check((root["version"] as? Number)?.toDouble() == 2.0) { "不支持的历史记录版本" }
        val limitNumber = (root["historyLimit"] as? Number)?.toLong() ?: error("历史数量格式错误")
        check(limitNumber in 1..Int.MAX_VALUE.toLong()) { "历史数量须为正整数" }
        val tags = (root["tags"] as? List<*>)?.map { it as? String ?: error("标签格式错误") }
            ?: error("标签格式错误")
        tags.forEach(HistoryCollection::validateTag)
        check(tags.distinct().size == tags.size) { "标签重复" }
        val entries = root["records"] as? List<*> ?: error("历史索引格式错误")
        val nextReferences = linkedMapOf<String, String>()
        val values = entries.map { raw ->
            val entry = raw as? Map<*, *> ?: error("历史索引格式错误")
            val id = entry["id"] as? String ?: error("会话编号错误")
            val name = entry["file"] as? String ?: error("会话文件名错误")
            check(RECORD_NAME.matches(name)) { "会话文件名错误" }
            check(nextReferences.put(id, name) == null) { "会话编号重复" }
            val bytes = files.read(name, HistoryArchive.MAX_RECORD_BYTES) ?: error("会话文件缺失，未修改历史记录")
            HistoryArchive.decodeRecord(decode(bytes)).also {
                check(it.id == id && it.tags.all { tag -> tag in tags }) { "会话索引不一致" }
            }
        }
        references = nextReferences
        return snapshot(HistoryState(values, tags, limitNumber.toInt())).also { loaded = it }
    }

    fun commit(previous: HistoryState, next: HistoryState, force: Boolean = false) {
        if (!force && previous == next) return
        require(next.historyLimit > 0) { "历史数量须大于 0" }
        next.tags.forEach(HistoryCollection::validateTag)
        check(next.tags.distinct().size == next.tags.size) { "标签重复" }
        check(next.conversations.map { it.id }.distinct().size == next.conversations.size) { "会话编号重复" }
        val old = previous.conversations.associateBy { it.id }
        val nextReferences = linkedMapOf<String, String>()
        next.conversations.forEach { value ->
            HistoryArchive.validate(value)
            check(value.tags.all { it in next.tags }) { "标签不存在" }
            val existing = references[value.id]
            val name = if (existing != null && old[value.id] == value) existing else {
                val bytes = encode(HistoryArchive.record(value))
                check(bytes.size <= HistoryArchive.MAX_RECORD_BYTES) { "完整会话超过 32 MiB，未保存" }
                val fresh = UUID.randomUUID().toString() + ".json"
                files.write(fresh, bytes)
                fresh
            }
            nextReferences[value.id] = name
        }
        val manifest = encode(linkedMapOf("version" to 2, "historyLimit" to next.historyLimit,
            "tags" to next.tags, "records" to nextReferences.map { (id, file) -> mapOf("id" to id, "file" to file) }))
        check(manifest.size <= HistoryArchive.MAX_FILE_BYTES) { "历史索引过大，未保存" }
        files.write(MANIFEST, manifest)
        references = nextReferences
        loaded = snapshot(next)
        // Cleanup happens only after the complete next collection is committed. Failed writes stay invisible.
        // Orphan removal must not turn a committed save into a failure or skip the caller's media cleanup.
        val live = nextReferences.values.toSet()
        runCatching { files.records() }.getOrDefault(emptyList())
            .filter { RECORD_NAME.matches(it) && it !in live }.forEach { runCatching { files.delete(it) } }
    }

    /** Explicit recovery must work even when the manifest, legacy archive, or a record is corrupt. */
    fun clear(onCommitted: () -> Unit = {}) {
        val current = loaded ?: runCatching {
            files.read(MANIFEST, HistoryArchive.MAX_FILE_BYTES)?.let(decode)
        }.getOrNull()?.let { root ->
            val limit = runCatching {
                (root["historyLimit"] as? Number)?.toLong()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            }.getOrNull() ?: HistoryArchive.DEFAULT_HISTORY_LIMIT
            val tags = runCatching {
                (root["tags"] as? List<*>)?.map {
                    (it as? String ?: error("标签格式错误")).also(HistoryCollection::validateTag)
                }?.distinct().orEmpty()
            }.getOrDefault(emptyList())
            HistoryState(tags = tags, historyLimit = limit)
        } ?: HistoryState()
        // The empty manifest stays authoritative even if removal of the old archive later fails.
        commit(current, current.copy(conversations = emptyList()), force = true)
        try {
            files.delete(LEGACY)
            files.records().filter { RECORD_NAME.matches(it) }.forEach(files::delete)
        } finally {
            onCommitted()
        }
    }

    private fun snapshot(state: HistoryState): HistoryState = state.copy(
        conversations = state.conversations.sortedByDescending { it.updatedAt }.map {
            it.copy(messages = it.messages.toList(), tags = it.tags.toList())
        },
        tags = state.tags.toList(),
    )

    companion object {
        const val MANIFEST = "index.json"
        const val LEGACY = "legacy"
        val RECORD_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")
    }
}
