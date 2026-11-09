package com.alwayside.android

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Stream recordings into a private archive without loading a full WAV into memory. */
internal class ConversationAudioArchive(
    private val directory: File,
) {
    fun save(id: String, input: InputStream, length: Long) = synchronized(copyLock) {
        val target = audioFile(id)
        require(length > 44) { "录音为空，无法保存" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建音频目录" }
        // Only one copy runs at a time; these are leftovers from an interrupted process.
        synchronized(lock) {
            directory.listFiles()?.filter { it.extension == "tmp" }?.forEach { check(it.delete()) { "无法清理未完成的录音" } }
        }
        val pending = File.createTempFile("recording-", ".tmp", directory)
        try {
            pending.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (copied < length) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                    check(count > 0) { "音频读取不完整，未保存" }
                    output.write(buffer, 0, count)
                    copied += count
                }
                check(input.read() == -1) { "音频文件发生变化，未保存" }
                output.fd.sync()
            }
            synchronized(lock) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }

    // Atomic publication lets playback and main-thread history reads proceed during a long copy.
    fun load(id: String): File? = audioFile(id).takeIf { it.isFile && it.length() > 44 }

    fun delete(id: String) = synchronized(lock) {
        val target = audioFile(id)
        check(!target.exists() || target.delete()) { "音频删除失败" }
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { check(it.delete()) { "音频删除失败" } }
    }

    private fun audioFile(id: String): File {
        require(id.isNotBlank() && id.length <= 128) { "Invalid conversation ID" }
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.wav")
    }

    private companion object { val copyLock = Any(); val lock = Any() }
}
