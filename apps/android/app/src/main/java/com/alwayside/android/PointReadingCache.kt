package com.alwayside.android

import java.io.File
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private persistent audio. Constructing this store never creates a directory. */
internal interface ReadingCacheIndex {
    fun used(file: File, owner: String) {}
    fun marked(file: File, owner: String, mark: String) {}
    fun removed(owner: String, mark: String) {}
    fun removeOwner(owner: String) {}
    fun forgotten(file: File) {}
}

internal class PointReadingCache(private val directory: File, private val index: ReadingCacheIndex = object : ReadingCacheIndex {}) {
    val marks = PointReadingMarks(File(directory, "marks"))
    data class Ticket(val generation: Long, val ownerRevision: Long)
    private var generation = 0L
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized fun ticket(owner: String) = Ticket(generation, revisions[owner] ?: 0)
    @Synchronized fun current(owner: String, ticket: Ticket) = ticket == ticket(owner)

    @Synchronized fun contains(key: String): Boolean = listOf("mp3", "wav").any { audio(key, it).let { file -> file.isFile && file.length() > 0 } }

    @Synchronized fun remember(owner: String, ticket: Ticket, mark: ReadingMark): Boolean {
        if (!current(owner, ticket) || !contains(mark.key)) return false
        val previous = marks.list(owner).firstOrNull { it.id == mark.id }
        marks.put(owner, mark)
        audioFiles().firstOrNull { it.nameWithoutExtension == mark.key }?.let { index.marked(it, owner, mark.id) }
        if (previous != null && previous.key != mark.key && marks.list(owner).none { it.key == previous.key })
            releaseReference(previous.key, owner)
        return true
    }

    @Synchronized fun removeMark(owner: String, mark: ReadingMark) {
        marks.remove(owner, mark.id)
        index.removed(owner, mark.id)
        if (marks.list(owner).none { it.key == mark.key }) releaseReference(mark.key, owner)
    }

    private fun releaseReference(key: String, owner: String) { writeOwners(key, owners(key) - readingDigest(owner)) }

    @Synchronized fun get(key: String, owner: String, ticket: Ticket): File? {
        if (!current(owner, ticket)) return null
        val target = listOf("mp3", "wav").map { audio(key, it) }.firstOrNull { it.isFile } ?: return null
        if (!target.isFile || target.length() == 0L) return null
        addOwner(key, owner)
        index.used(target, owner)
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    @Synchronized fun put(key: String, owner: String, ticket: Ticket, bytes: ByteArray,
        protectedOwners: Set<String>, active: File? = null, format: String = "mp3"): File? {
        if (!current(owner, ticket)) return null
        require(bytes.isNotEmpty() && bytes.size <= MAX_AUDIO_BYTES) { "朗读音频大小无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法保存点读音频" }
        val target = audio(key, format)
        val temporary = File(directory, "$key.part")
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            addOwner(key, owner)
            index.used(target, owner)
            return target
        } finally { temporary.delete() }
    }

    @Synchronized fun removeOwner(owner: String) {
        revisions[owner] = (revisions[owner] ?: 0) + 1
        marks.removeOwner(owner)
        index.removeOwner(owner)
        val id = readingDigest(owner)
        audioFiles().forEach { file ->
            val key = file.nameWithoutExtension
            val previous = owners(key)
            if (id in previous) {
                val next = previous - id
                if (next.isEmpty()) { deleteFile(file); deleteFile(refs(key)); index.forgotten(file) } else writeOwners(key, next)
            }
        }
    }

    @Synchronized fun clear(protectedOwners: Set<String> = emptySet(), active: File? = null) {
        generation++
        val protected = protectedOwners.map(::readingDigest).toSet()
        audioFiles().forEach { file ->
            val references = owners(file.nameWithoutExtension)
            if (file != active && "unreadable" !in references && references.none { it in protected }) {
                deleteFile(file); deleteFile(refs(file.nameWithoutExtension)); index.forgotten(file)
            }
        }
    }

    @Synchronized fun idleFiles(): List<File> = audioFiles().filter {
        refs(it.nameWithoutExtension).isFile && owners(it.nameWithoutExtension).isEmpty()
    }
    @Synchronized fun clearIdle(active: File? = null) {
        generation++
        idleFiles().filterNot { it == active }.forEach { file ->
            deleteFile(file); deleteFile(refs(file.nameWithoutExtension)); index.forgotten(file)
        }
    }
    @Synchronized fun legacyOwners(key: String): Set<String> = owners(key)
    @Synchronized fun files(): List<File> = audioFiles().toList()
    private fun deleteFile(file: File) { check(!file.exists() || file.delete()) { "点读文件清理失败" } }

    @Synchronized fun bytes(): Long = audioFiles().sumOf(File::length)
    @Synchronized fun discard(key: String) { listOf("mp3", "wav").forEach { audio(key, it).delete() }; refs(key).delete() }
    private fun audioFiles() = directory.listFiles()?.filter { it.extension in setOf("mp3", "wav") && valid(it.nameWithoutExtension) } ?: emptyList()
    private fun valid(key: String) = Regex("[a-f0-9]{64}").matches(key)
    private fun audio(key: String, format: String): File { require(valid(key) && format in setOf("mp3", "wav")); return File(directory, "$key.$format") }
    private fun refs(key: String): File { require(valid(key)); return File(directory, "$key.refs") }
    private fun owners(key: String): Set<String> {
        val file = refs(key)
        if (!file.isFile) return emptySet()
        // A broken reference list must not cause automatic eviction of potentially protected data.
        return runCatching { Properties().apply { file.inputStream().use(::load) }.stringPropertyNames().toSet() }
            .getOrElse { setOf("unreadable") }
    }
    private fun addOwner(key: String, owner: String) = writeOwners(key, owners(key) + readingDigest(owner))
    private fun writeOwners(key: String, values: Set<String>) {
        val target = refs(key)
        val temporary = File(directory, "$key.refs.part")
        try {
            temporary.outputStream().use { output ->
                Properties().apply { values.forEach { setProperty(it, "1") } }.store(output, null)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object { const val MAX_AUDIO_BYTES = 16 * 1024 * 1024 }
}
