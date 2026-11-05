package com.alwayside.android

import android.content.Context
import android.util.AtomicFile
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
        val packed = HistoryArchive.save(old, value, ::encodeRecord)
        val next = packed.values
        writeBytes(packed.bytes)
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
                check(result.size() + count <= HistoryArchive.MAX_FILE_BYTES) { "History exceeds size limit" }
                result.write(buffer, 0, count)
            }
            val bytes = result.toByteArray()
            check(bytes.size <= HistoryArchive.MAX_FILE_BYTES) { "History exceeds size limit" }
            bytes
        } } catch (_: java.io.FileNotFoundException) { return emptyList() }
        return HistoryArchive.decode(WireJson.decode(bytes.toString(Charsets.UTF_8)))
    }

    private fun write(values: List<StoredConversation>) {
        writeBytes(HistoryArchive.encode(values, ::encodeRecord))
    }

    private fun encodeRecord(value: StoredConversation): ByteArray =
        WireJson.encode(HistoryArchive.record(value)).toByteArray(Charsets.UTF_8)

    private fun writeBytes(bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        private val lock = Any()
    }
}
