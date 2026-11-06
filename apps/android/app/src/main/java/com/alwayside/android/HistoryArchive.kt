package com.alwayside.android

/** Legacy archive reader and complete conversation records. */
internal object HistoryArchive {
    const val DEFAULT_HISTORY_LIMIT = 500
    const val MAX_INDEX_BYTES = 32 * 1024 * 1024

    fun record(value: StoredConversation): Map<String, Any?> {
        validate(value)
        return linkedMapOf(
            "id" to value.id, "updatedAt" to value.updatedAt,
            "title" to ConversationTitle.resolve(value.title, value.messages),
            "messages" to value.messages.map { linkedMapOf("role" to it.role, "text" to it.text,
                "evidence" to it.evidence, "searchSuggestions" to it.searchSuggestions) },
            "evidence" to value.evidence, "transcript" to value.transcript, "kind" to value.kind,
            "status" to value.status, "sourceText" to value.sourceText,
            "favoriteAt" to value.favoriteAt, "historyEnteredAt" to value.historyEnteredAt,
            "tags" to value.tags,
        )
    }

    fun decode(root: Map<String, Any?>): List<StoredConversation> {
        check((root["version"] as? Number)?.toDouble() == 1.0) { "不支持的历史记录版本" }
        val entries = root["conversations"] as? List<*> ?: error("历史记录格式错误")
        return entries.map { decodeRecord(it as? Map<*, *> ?: error("会话格式错误")) }
            .also { values -> check(values.map { it.id }.distinct().size == values.size) { "会话编号重复" } }
            .sortedByDescending { it.updatedAt }
    }

    fun decodeRecord(entry: Map<*, *>): StoredConversation {
        fun required(name: String) = entry[name] as? String ?: error("会话字段错误：$name")
        val rawMessages = entry["messages"] as? List<*> ?: error("会话消息格式错误")
        val messages = rawMessages.map { item ->
            val message = item as? Map<*, *> ?: error("会话消息格式错误")
            ChatMessage(message["role"] as? String ?: error("会话角色错误"),
                message["text"] as? String ?: error("会话正文错误"),
                message["evidence"] as? String ?: "", message["searchSuggestions"] as? String ?: "")
        }
        val tags = when (val raw = entry["tags"]) {
            null -> emptyList()
            is List<*> -> raw.map { it as? String ?: error("会话标签格式错误") }
            else -> error("会话标签格式错误")
        }
        return StoredConversation(required("id"),
            (entry["updatedAt"] as? Number)?.toLong() ?: error("会话时间错误"),
            ConversationTitle.resolve(required("title"), messages), messages, entry["evidence"] as? String ?: "",
            entry["transcript"] as? String ?: "", entry["kind"] as? String ?: "text",
            entry["status"] as? String ?: "已完成", entry["sourceText"] as? String ?: "",
            (entry["favoriteAt"] as? Number)?.toLong() ?: 0,
            (entry["historyEnteredAt"] as? Number)?.toLong() ?: 0, tags).also(::validate)
    }

    fun validate(value: StoredConversation) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "会话编号无效" }
        require(value.messages.all { it.role in setOf("user", "assistant", "system") }) { "会话角色无效" }
        require(value.kind in setOf("photo", "audio", "text")) { "会话类型无效" }
        require(value.favoriteAt >= 0 && value.historyEnteredAt >= 0) { "会话时间无效" }
        require(value.tags.distinct().size == value.tags.size) { "会话标签重复" }
        value.tags.forEach(HistoryCollection::validateTag)
    }
}
