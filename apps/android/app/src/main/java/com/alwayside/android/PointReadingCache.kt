package com.alwayside.android

import java.io.File
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private persistent audio. Constructing this store never creates a directory. */
internal class PointReadingCache(private val directory: File, private val capacity: Long = 256L * 1024 * 1024) {
    data class Ticket(val generation: Long, val ownerRevision: Long)
    private var generation = 0L
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized fun ticket(owner: String) = Ticket(generation, revisions[owner] ?: 0)
    @Synchronized fun current(owner: String, ticket: Ticket) = ticket == ticket(owner)

    @Synchronized fun get(key: String, owner: String, ticket: Ticket): File? {
        if (!current(owner, ticket)) return null
        val target = audio(key)
        if (!target.isFile || target.length() == 0L) return null
        addOwner(key, owner)
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    @Synchronized fun put(key: String, owner: String, ticket: Ticket, bytes: ByteArray,
        protectedOwners: Set<String>, active: File? = null): File? {
        if (!current(owner, ticket)) return null
        require(bytes.isNotEmpty() && bytes.size <= MAX_AUDIO_BYTES) { "朗读音频大小无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法保存点读音频" }
        var used = audioFiles().sumOf(File::length)
        val protected = protectedOwners.map(::readingDigest).toSet()
        for (candidate in audioFiles().sortedBy(File::lastModified)) {
            if (used + bytes.size <= capacity) break
            val references = owners(candidate.nameWithoutExtension)
            if (candidate == active || candidate.nameWithoutExtension == key || "unreadable" in references || references.any { it in protected }) continue
            val length = candidate.length()
            if (candidate.delete()) { used -= length; refs(candidate.nameWithoutExtension).delete() }
        }
        if (used + bytes.size > capacity) return null
        val target = audio(key)
        val temporary = File(directory, "$key.part")
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            addOwner(key, owner)
            return target
        } finally { temporary.delete() }
    }

    @Synchronized fun removeOwner(owner: String) {
        revisions[owner] = (revisions[owner] ?: 0) + 1
        val id = readingDigest(owner)
        audioFiles().forEach { file ->
            val key = file.nameWithoutExtension
            val previous = owners(key)
            if (id in previous) {
                val next = previous - id
                if (next.isEmpty()) { file.delete(); refs(key).delete() } else writeOwners(key, next)
            }
        }
    }

    @Synchronized fun clear(protectedOwners: Set<String> = emptySet(), active: File? = null) {
        generation++
        val protected = protectedOwners.map(::readingDigest).toSet()
        audioFiles().forEach { file ->
            val references = owners(file.nameWithoutExtension)
            if (file != active && "unreadable" !in references && references.none { it in protected }) {
                file.delete(); refs(file.nameWithoutExtension).delete()
            }
        }
    }

    @Synchronized fun bytes(): Long = audioFiles().sumOf(File::length)
    @Synchronized fun discard(key: String) { audio(key).delete(); refs(key).delete() }
    private fun audioFiles() = directory.listFiles()?.filter { it.extension == "mp3" && valid(it.nameWithoutExtension) } ?: emptyList()
    private fun valid(key: String) = Regex("[a-f0-9]{64}").matches(key)
    private fun audio(key: String): File { require(valid(key)); return File(directory, "$key.mp3") }
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
