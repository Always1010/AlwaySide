package com.alwayside.android

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Local archive outside Android backup; favorites never participate in automatic retention. */
internal class HistoryStore(private val context: Context) {
    private val media = ConversationMediaStore(context)
    private val repository = context.noBackupFilesDir.canonicalFile.let { root ->
        // Looking up an existing repository must not wait behind an unrelated long body write.
        repositories.computeIfAbsent(root.path) {
            Repository(HistoryFiles(AndroidHistoryFiles(root),
                { WireJson.encode(it).toByteArray(Charsets.UTF_8) },
                { WireJson.decode(it.toString(Charsets.UTF_8)) }), HistoryPendingWrites(lock, writer))
        }
    }

    private val files get() = repository.files
    fun index(): HistoryState = files.cachedIndex() ?: synchronized(lock) { files.loadIndex() }
    /** Display summaries; use find for the complete body of a single conversation. */
    fun list(): List<StoredConversation> = index().conversations
    fun summary(id: String): StoredConversation? = index().conversations.find { it.id == id }
    fun find(id: String): StoredConversation? = synchronized(lock) { files.find(id) }
    fun historyLimit(): Int = index().historyLimit
    fun tags(): List<String> = index().tags

    fun save(value: StoredConversation) = change(value.id) { HistoryCollection.save(it, value, System.currentTimeMillis()) }
    fun saveAsync(value: StoredConversation, failed: (Exception) -> Unit) {
        repository.pending.submit(value.id, { save(value) }, failed, value.updatedAt)
    }
    fun setHistoryLimit(limit: Int) = change {
        require(limit > 0) { "历史数量须大于 0" }
        HistoryCollection.retain(it.copy(historyLimit = limit))
    }
    fun setFavorite(id: String, favorite: Boolean) = change {
        HistoryCollection.favorite(it, id, favorite, System.currentTimeMillis())
    }
    fun setTags(id: String, tags: List<String>) = change { HistoryCollection.setTags(it, id, tags) }
    fun rename(id: String, title: String) = change { HistoryCollection.rename(it, id, title.trim()) }
    fun editTags(ids: List<String>, edits: Map<String, Boolean>, collectMissing: Boolean) = change {
        HistoryCollection.editTags(it, ids, edits, collectMissing, System.currentTimeMillis())
    }
    fun createTag(name: String) = change { HistoryCollection.createTag(it, name.trim()) }
    fun renameTag(old: String, new: String) = change { HistoryCollection.renameTag(it, old, new.trim()) }
    fun deleteTag(name: String) = change { HistoryCollection.deleteTag(it, name) }
    fun delete(id: String) = synchronized(lock) {
        change { it.copy(conversations = it.conversations.filterNot { value -> value.id == id }) }
        repository.pending.invalidate(id)
    }
    fun clear() = synchronized(lock) {
        PointReadingController.clearAll(context)
        files.clear { repository.pending.invalidateAll(); media.clear() }
    }

    private fun change(bodyId: String? = null, transform: (HistoryState) -> HistoryState) = synchronized(lock) {
        val (old, next) = files.change(bodyId, transform)
        val remaining = next.conversations.map { it.id }.toSet()
        old.conversations.filterNot { it.id in remaining }.forEach {
            repository.pending.invalidate(it.id)
            media.delete(it.id)
            PointReadingController.delete(context, it.id)
        }
    }

    private companion object {
        val lock = Any()
        val writer = Executors.newSingleThreadExecutor()
        data class Repository(val files: HistoryFiles, val pending: HistoryPendingWrites)
        val repositories = ConcurrentHashMap<String, Repository>()
    }
}

private class AndroidHistoryFiles(private val root: File) : HistoryFileAccess {
    private val directory = File(root, "conversations_v2")
    private fun target(name: String): AtomicFile {
        require(name == HistoryFiles.LEGACY || name == HistoryFiles.MANIFEST || HistoryFiles.RECORD_NAME.matches(name))
        return AtomicFile(if (name == HistoryFiles.LEGACY) File(root, "conversations.json") else File(directory, name))
    }

    override fun read(name: String, maxBytes: Int?): ByteArray? {
        val file = target(name)
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw error
            return null
        }
        return input.use {
            check(maxBytes == null || it.channel.size() <= maxBytes) { "历史文件超过大小上限" }
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(maxBytes == null || result.size().toLong() + count <= maxBytes) { "历史文件超过大小上限" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建历史目录" }
        val file = target(name)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    override fun delete(name: String) {
        val file = target(name)
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()
            && !File(file.baseFile.path + ".new").exists()) { "历史文件删除失败" }
    }

    override fun records(): List<String> {
        if (!directory.exists()) return emptyList()
        return (directory.list() ?: error("无法读取历史目录"))
            .map { it.removeSuffix(".bak").removeSuffix(".new") }.distinct()
    }
}
