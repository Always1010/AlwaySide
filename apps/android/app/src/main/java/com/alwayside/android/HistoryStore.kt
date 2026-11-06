package com.alwayside.android

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/** Local archive outside Android backup; favorites never participate in automatic retention. */
internal class HistoryStore(context: Context) {
    private val media = ConversationMediaStore(context)
    private val files = synchronized(lock) {
        val root = context.noBackupFilesDir.canonicalFile
        repositories.getOrPut(root.path) {
            HistoryFiles(AndroidHistoryFiles(root),
                { WireJson.encode(it).toByteArray(Charsets.UTF_8) },
                { WireJson.decode(it.toString(Charsets.UTF_8)) })
        }
    }

    fun list(): List<StoredConversation> = synchronized(lock) { files.load().conversations }
    fun find(id: String): StoredConversation? = synchronized(lock) { files.load().conversations.find { it.id == id } }
    fun historyLimit(): Int = synchronized(lock) { files.load().historyLimit }
    fun tags(): List<String> = synchronized(lock) { files.load().tags }

    fun save(value: StoredConversation) = change { HistoryCollection.save(it, value, System.currentTimeMillis()) }
    fun setHistoryLimit(limit: Int) = change {
        require(limit > 0) { "历史数量须大于 0" }
        HistoryCollection.retain(it.copy(historyLimit = limit))
    }
    fun setFavorite(id: String, favorite: Boolean) = change {
        HistoryCollection.favorite(it, id, favorite, System.currentTimeMillis())
    }
    fun setTags(id: String, tags: List<String>) = change { HistoryCollection.setTags(it, id, tags) }
    fun createTag(name: String) = change { HistoryCollection.createTag(it, name.trim()) }
    fun renameTag(old: String, new: String) = change { HistoryCollection.renameTag(it, old, new.trim()) }
    fun deleteTag(name: String) = change { HistoryCollection.deleteTag(it, name) }
    fun delete(id: String) = change { it.copy(conversations = it.conversations.filterNot { value -> value.id == id }) }
    fun clear() = synchronized(lock) {
        files.clear { media.clear() }
    }

    private fun change(transform: (HistoryState) -> HistoryState) = synchronized(lock) {
        val old = files.load()
        val next = transform(old)
        files.commit(old, next)
        val remaining = next.conversations.map { it.id }.toSet()
        old.conversations.filterNot { it.id in remaining }.forEach { media.delete(it.id) }
    }

    private companion object {
        val lock = Any()
        val repositories = mutableMapOf<String, HistoryFiles>()
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
