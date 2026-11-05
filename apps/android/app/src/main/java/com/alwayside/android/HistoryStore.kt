package com.alwayside.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

/** A bounded, text-only local archive. The file is excluded from Android backup. */
internal class HistoryStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "conversations.json"))
    private val media = ConversationMediaStore(context)

    fun list(): List<StoredConversation> = synchronized(lock) { read() }
    fun find(id: String): StoredConversation? = synchronized(lock) { read().find { it.id == id } }

    fun save(value: StoredConversation) = synchronized(lock) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "Invalid conversation ID" }
        val old = read()
        if ((old.find { it.id == value.id }?.updatedAt ?: Long.MIN_VALUE) > value.updatedAt) return@synchronized
        val next = (old.filterNot { it.id == value.id } + bounded(value))
            .sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS)
        write(next)
        old.filter { previous -> next.none { it.id == previous.id } }.forEach { media.delete(it.id) }
    }

    fun delete(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }); media.delete(id) }
    fun clear() = synchronized(lock) {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()
            && !File(file.baseFile.path + ".new").exists()) { "History deletion failed" }
        media.clear()
    }

    private fun read(): List<StoredConversation> {
        val bytes = try { file.openRead().use { input ->
            val buffer = ByteArray(8192)
            val result = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(result.size() + count <= MAX_FILE_BYTES) { "History exceeds size limit" }
                result.write(buffer, 0, count)
            }
            val bytes = result.toByteArray()
            check(bytes.size <= MAX_FILE_BYTES) { "History exceeds size limit" }
            bytes
        } } catch (_: java.io.FileNotFoundException) { return emptyList() }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        check(root.getInt("version") == 1) { "Unknown history version" }
        val data = root.getJSONArray("conversations")
        return (0 until minOf(data.length(), MAX_CONVERSATIONS)).map { index ->
            val entry = data.getJSONObject(index)
            val messages = entry.getJSONArray("messages")
            bounded(StoredConversation(entry.getString("id"), entry.getLong("updatedAt"),
                entry.getString("title"), (0 until minOf(messages.length(), MAX_MESSAGES)).map { position ->
                    val message = messages.getJSONObject(position)
                    ChatMessage(message.getString("role"), message.getString("text"),message.optString("evidence"), message.optString("searchSuggestions"))
                }, entry.optString("evidence"), entry.optString("transcript"), entry.optString("kind", "text"), entry.optString("status", "已完成"), entry.optString("sourceText")))
        }.sortedByDescending { it.updatedAt }
    }

    private fun bounded(value: StoredConversation): StoredConversation {
        var budget = 120000
        val messages = value.messages.takeLast(MAX_MESSAGES).asReversed().mapNotNull { message ->
            if (budget <= 0 || message.role !in setOf("user", "assistant", "system")) null
            else {
                val text = message.text.take(minOf(20000, budget))
                val suggestions = message.searchSuggestions.takeIf { it.length <= minOf(32768, budget - text.length) }.orEmpty()
                message.copy(text = text, evidence = message.evidence.take(1000), searchSuggestions = suggestions)
                    .also { budget -= it.text.length + it.searchSuggestions.length }
            }
        }.asReversed()
        return value.copy(id = value.id.take(128), title = ConversationTitle.resolve(value.title, messages).take(160), messages = messages,
            evidence = value.evidence.take(2000), transcript = value.transcript.take(32000),
            kind = value.kind.takeIf { it in setOf("photo", "audio", "text") } ?: "text", status = value.status.take(300),
            sourceText = value.sourceText.take(12000))
    }

    private fun write(values: List<StoredConversation>) {
        val entries = JSONArray()
        values.forEach { value ->
            val messages = JSONArray()
            value.messages.forEach { messages.put(JSONObject().put("role", it.role).put("text", it.text).put("evidence",it.evidence).put("searchSuggestions", it.searchSuggestions)) }
            entries.put(JSONObject().put("id", value.id).put("updatedAt", value.updatedAt)
                .put("title", value.title).put("messages", messages)
                .put("evidence", value.evidence).put("transcript", value.transcript).put("kind", value.kind).put("status", value.status)
                .put("sourceText", value.sourceText))
        }
        val bytes = JSONObject().put("version", 1).put("conversations", entries).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_FILE_BYTES) { "History exceeds size limit" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        private val lock = Any()
        private const val MAX_CONVERSATIONS = 50
        private const val MAX_MESSAGES = 100
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
