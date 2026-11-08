package com.alwayside.android

import java.util.UUID

/** Each write is atomic. Immutable records become visible together at the manifest commit. */
internal interface HistoryFileAccess {
    fun read(name: String, maxBytes: Int? = null): ByteArray?
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
    // Only display summaries live for the lifetime of the process. Full bodies are read on demand.
    @Volatile private var index: HistoryState? = null

    /** Read-only display data, published only after a complete manifest load or successful commit. */
    fun cachedIndex(): HistoryState? = index

    fun loadIndex(): HistoryState {
        index?.let { return it }
        val manifest = files.read(MANIFEST, HistoryArchive.MAX_INDEX_BYTES)
        if (manifest == null) {
            val legacy = files.read(LEGACY)
            val values = legacy?.let { HistoryArchive.decode(decode(it)) }.orEmpty().map {
                it.copy(historyEnteredAt = it.historyEnteredAt.takeIf { time -> time > 0 } ?: it.updatedAt.coerceAtLeast(0))
            }
            val state = HistoryState(values, values.flatMap { it.tags }.distinct())
            references = emptyMap()
            // An empty manifest also prevents a failed legacy deletion from resurrecting data.
            commit(HistoryState(), state, force = true)
            if (legacy != null) files.delete(LEGACY)
            return checkNotNull(index)
        }
        val root = decode(manifest)
        val version = (root["version"] as? Number)?.toDouble()
        check(version == 2.0 || version == 3.0) { "不支持的历史记录版本" }
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
            val value = if (version == 2.0) {
                // Upgrade one record at a time; never build or retain a collection of bodies.
                val bytes = files.read(name) ?: error("会话文件缺失，未修改历史记录")
                HistorySummary.of(HistoryArchive.decodeRecord(decode(bytes)))
            } else {
                val summary = HistoryArchive.decodeRecord(entry["summary"] as? Map<*, *> ?: error("会话摘要缺失"))
                check(summary.messages.isEmpty() && summary.transcript.isEmpty() && summary.sourceText.isEmpty()
                    && summary.evidence.length <= 180 && summary.status.length <= 180) { "会话摘要格式错误" }
                summary
            }
            value.also { check(it.id == id && it.tags.all { tag -> tag in tags }) { "会话索引不一致" } }
        }
        val state = snapshot(HistoryState(values, tags, limitNumber.toInt()))
        references = nextReferences
        // Do not mark a failed migration as loaded, or subsequent writes could hide its failure.
        if (version == 2.0) persist(state, emptyMap()) else index = state
        return checkNotNull(index)
    }

    /** Explicit full export/test compatibility; the returned bodies are never cached. */
    fun load(): HistoryState {
        val state = loadIndex()
        return state.copy(conversations = state.conversations.map { readRecord(it) })
    }

    fun find(id: String): StoredConversation? = loadIndex().conversations.find { it.id == id }?.let(::readRecord)

    private fun readRecord(summary: StoredConversation): StoredConversation {
        val name = references[summary.id] ?: error("会话索引缺失")
        val bytes = files.read(name) ?: error("会话文件缺失，未修改历史记录")
        val body = HistoryArchive.decodeRecord(decode(bytes))
        check(body.id == summary.id && body.updatedAt == summary.updatedAt
            && (body.title == summary.title || summary.customTitle.isNotEmpty())
            && body.kind == summary.kind) { "会话索引不一致" }
        // Collection metadata is authoritative in v3 and need not rewrite the immutable body.
        return HistorySummary.restore(body, summary)
    }

    /** Collection edits operate entirely on summaries. Only an incoming saved body is rewritten. */
    fun change(bodyId: String? = null, transform: (HistoryState) -> HistoryState): Pair<HistoryState, HistoryState> {
        val previous = loadIndex()
        val next = transform(previous)
        if (previous == next) return previous to previous
        val bodies = if (bodyId == null) emptyMap() else
            next.conversations.filter { it.id == bodyId }.associateBy { it.id }
        persist(next, bodies)
        return previous to checkNotNull(index)
    }

    /** Full-state compatibility for imports and transactional storage tests. */
    fun commit(previous: HistoryState, next: HistoryState, force: Boolean = false) {
        if (!force && previous == next) return
        val old = previous.conversations.associateBy { it.id }
        val changed = next.conversations.filter { references[it.id] == null || old[it.id] != it }.associateBy { it.id }
        persist(next, changed)
    }

    private fun persist(next: HistoryState, bodies: Map<String, StoredConversation>) {
        require(next.historyLimit > 0) { "历史数量须大于 0" }
        next.tags.forEach(HistoryCollection::validateTag)
        check(next.tags.distinct().size == next.tags.size) { "标签重复" }
        check(next.conversations.map { it.id }.distinct().size == next.conversations.size) { "会话编号重复" }
        // Full-state imports may include unchanged bodies as well as changed ones. Every index entry
        // must be reduced to a summary, even when its existing immutable body file can be reused.
        val summaries = next.conversations.map(HistorySummary::of)
        val nextReferences = linkedMapOf<String, String>()
        summaries.forEach { value ->
            HistoryArchive.validate(value)
            check(value.tags.all { it in next.tags }) { "标签不存在" }
            val body = bodies[value.id]
            val name = if (body == null) references[value.id] ?: error("会话文件缺失") else {
                val bytes = encode(HistoryArchive.record(body))
                val fresh = UUID.randomUUID().toString() + ".json"
                files.write(fresh, bytes)
                fresh
            }
            nextReferences[value.id] = name
        }
        val state = snapshot(next.copy(conversations = summaries))
        val manifest = encode(linkedMapOf("version" to 3, "historyLimit" to state.historyLimit,
            "tags" to state.tags, "records" to state.conversations.map { summary ->
                mapOf("id" to summary.id, "file" to nextReferences.getValue(summary.id),
                    "summary" to HistoryArchive.record(summary))
            }))
        check(manifest.size <= HistoryArchive.MAX_INDEX_BYTES) { "历史索引过大，未保存" }
        files.write(MANIFEST, manifest)
        references = nextReferences
        index = state
        // Failed record/manifest writes stay invisible. Cleanup cannot turn a committed save into a failure.
        val live = nextReferences.values.toSet()
        runCatching { files.records() }.getOrDefault(emptyList())
            .filter { RECORD_NAME.matches(it) && it !in live }.forEach { runCatching { files.delete(it) } }
    }

    /** Recovery never needs to read a missing/corrupt body and preserves readable collection settings. */
    fun clear(onCommitted: () -> Unit = {}) {
        val current = index ?: runCatching {
            files.read(MANIFEST, HistoryArchive.MAX_INDEX_BYTES)?.let(decode)
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
        persist(current.copy(conversations = emptyList()), emptyMap())
        try {
            files.delete(LEGACY)
            files.records().filter { RECORD_NAME.matches(it) }.forEach(files::delete)
        } finally { onCommitted() }
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
