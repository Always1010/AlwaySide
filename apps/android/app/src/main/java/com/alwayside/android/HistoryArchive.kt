package com.alwayside.android

import java.io.ByteArrayOutputStream

/** Version-one JSON with complete records; capacity is enforced by evicting whole old records. */
internal object HistoryArchive {
    const val MAX_CONVERSATIONS = 50
    const val MAX_MESSAGES = 100
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private val prefix = "{\"version\":1,\"conversations\":[".toByteArray(Charsets.UTF_8)
    private val suffix = "]}".toByteArray(Charsets.UTF_8)
    private const val TOO_LARGE = "完整会话超过本地历史大小上限，未保存"

    data class Packed(val values: List<StoredConversation>, val bytes: ByteArray)

    fun save(previous: List<StoredConversation>, value: StoredConversation,
             encodeRecord: (StoredConversation) -> ByteArray,
             maxBytes: Int = MAX_FILE_BYTES, maxConversations: Int = MAX_CONVERSATIONS): Packed {
        validate(value)
        previous.forEach(::validate)
        require(previous.map { it.id }.distinct().size == previous.size) { "Duplicate conversation ID" }
        require(maxConversations in 1..MAX_CONVERSATIONS && maxBytes in 1..MAX_FILE_BYTES)
        val incoming = encodeRecord(value)
        check(prefix.size.toLong() + suffix.size + incoming.size <= maxBytes) { TOO_LARGE }
        val encoded = mutableMapOf(value.id to incoming)
        val values = (previous.filterNot { it.id == value.id } + value).sortedByDescending { it.updatedAt }.toMutableList()
        values.filterNot { it.id == value.id }.forEach { encoded[it.id] = encodeRecord(it) }
        var size = prefix.size.toLong() + suffix.size + encoded.values.sumOf { it.size.toLong() } + values.size - 1
        while (values.size > maxConversations || size > maxBytes) {
            val index = values.indexOfLast { it.id != value.id }
            check(index >= 0) { TOO_LARGE }
            size -= encoded.getValue(values.removeAt(index).id).size + 1L
        }
        return Packed(values, assemble(values.map { encoded.getValue(it.id) }))
    }

    fun encode(values: List<StoredConversation>, encodeRecord: (StoredConversation) -> ByteArray): ByteArray {
        check(values.size <= MAX_CONVERSATIONS) { "History exceeds conversation limit" }
        values.forEach(::validate)
        val entries = values.map(encodeRecord)
        check(prefix.size.toLong() + suffix.size + entries.sumOf { it.size.toLong() } + (entries.size - 1).coerceAtLeast(0) <= MAX_FILE_BYTES) {
            "History exceeds size limit"
        }
        return assemble(entries)
    }

    fun record(value: StoredConversation): Map<String, Any?> {
        validate(value)
        return linkedMapOf(
            "id" to value.id, "updatedAt" to value.updatedAt,
            "title" to ConversationTitle.resolve(value.title, value.messages),
            "messages" to value.messages.map { linkedMapOf("role" to it.role, "text" to it.text,
                "evidence" to it.evidence, "searchSuggestions" to it.searchSuggestions) },
            "evidence" to value.evidence, "transcript" to value.transcript, "kind" to value.kind,
            "status" to value.status, "sourceText" to value.sourceText,
        )
    }

    fun decode(root: Map<String, Any?>): List<StoredConversation> {
        check((root["version"] as? Number)?.toDouble() == 1.0) { "Unknown history version" }
        val entries = root["conversations"] as? List<*> ?: error("Invalid history conversations")
        check(entries.size <= MAX_CONVERSATIONS) { "History exceeds conversation limit" }
        return entries.map { raw ->
            val entry = raw as? Map<*, *> ?: error("Invalid history conversation")
            fun required(name: String) = entry[name] as? String ?: error("Invalid history $name")
            val rawMessages = entry["messages"] as? List<*> ?: error("Invalid history messages")
            check(rawMessages.size <= MAX_MESSAGES) { "History exceeds message limit" }
            val messages = rawMessages.map { item ->
                val message = item as? Map<*, *> ?: error("Invalid history message")
                ChatMessage(message["role"] as? String ?: error("Invalid history role"),
                    message["text"] as? String ?: error("Invalid history text"),
                    message["evidence"] as? String ?: "", message["searchSuggestions"] as? String ?: "")
            }
            StoredConversation(required("id"), (entry["updatedAt"] as? Number)?.toLong() ?: error("Invalid history timestamp"),
                ConversationTitle.resolve(required("title"), messages), messages, entry["evidence"] as? String ?: "",
                entry["transcript"] as? String ?: "", entry["kind"] as? String ?: "text",
                entry["status"] as? String ?: "已完成", entry["sourceText"] as? String ?: "").also(::validate)
        }.also { values -> check(values.map { it.id }.distinct().size == values.size) { "Duplicate conversation ID" } }
            .sortedByDescending { it.updatedAt }
    }

    private fun validate(value: StoredConversation) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "Invalid conversation ID" }
        require(value.messages.size <= MAX_MESSAGES) { "History exceeds message limit" }
        require(value.messages.all { it.role in setOf("user", "assistant", "system") }) { "Invalid history role" }
        require(value.kind in setOf("photo", "audio", "text")) { "Invalid history kind" }
    }

    private fun assemble(entries: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(prefix)
        entries.forEachIndexed { index, entry -> if (index > 0) write(','.code); write(entry) }
        write(suffix)
    }.toByteArray()
}
