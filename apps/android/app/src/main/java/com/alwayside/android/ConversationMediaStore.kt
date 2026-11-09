package com.alwayside.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Optional media archive, isolated from credentials, JSON and Android backup. */
internal class ConversationMediaStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversation_images")
    private val audio = ConversationAudioArchive(File(context.noBackupFilesDir, "conversation_audio"))

    fun saveAudio(id: String, input: java.io.InputStream, length: Long) = audio.save(id, input, length)
    fun loadAudio(id: String): File? = audio.load(id)
    fun deleteAudio(id: String) {
        SubtitleController.deleteConversation(context, id)
        ChatAudioPlayback.stopConversation(id)
        audio.delete(id)
    }
    fun deleteAudioFile(id: String) = audio.delete(id)
    fun delete(id: String) { deleteImage(id); deleteAudio(id) }

    fun saveImages(id: String, original: Bitmap, current: Bitmap) = synchronized(lock) {
        val first = imageFile(id, original = true)
        val originalExists = try { AtomicFile(first).openRead().use { true } }
            catch (_: java.io.FileNotFoundException) { false }
        if (!originalExists) writeImage(first, original)
        val latest = imageFile(id)
        if (current !== original) writeImage(latest, current)
        else AtomicFile(latest).delete()
    }

    private fun writeImage(file: File, bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "图片已失效，请重新截图" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建图片目录" }
        val ratio = minOf(1.0, 2400.0 / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val bytes = try { ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "无法保存图片" }
            output.toByteArray()
        } } finally { if (scaled !== bitmap) scaled.recycle() }
        check(bytes.size <= MAX_IMAGE_BYTES) { "图片过大，无法长期保存" }
        val storedBytes = directory.listFiles()?.sumOf { it.length() } ?: 0L
        check(storedBytes - file.length() + bytes.size <= MAX_TOTAL_BYTES) { "历史图片已达到 128 MiB，请删除不需要的会话后重试" }
        val target = AtomicFile(file)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }

    fun loadImages(id: String): Pair<Bitmap?, Bitmap?> = synchronized(lock) {
        val original = readImage(imageFile(id, original = true))
        val current = readImage(imageFile(id)) ?: original
        (original ?: current) to current
    }

    private fun readImage(target: File): Bitmap? {
        val atomic = AtomicFile(target)
        val input = try { atomic.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        return input.use {
            // openRead restores an interrupted write before size checks or decoding (including API 29 .bak files).
            if (it.channel.size() !in 1..MAX_IMAGE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(it.fd, null, bounds)
            if (bounds.outWidth !in 1..2400 || bounds.outHeight !in 1..2400) return null
            it.channel.position(0)
            BitmapFactory.decodeFileDescriptor(it.fd)
        }
    }

    fun deleteImage(id: String) = synchronized(lock) {
        listOf(imageFile(id), imageFile(id, original = true)).forEach { file ->
            AtomicFile(file).delete()
            check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none(File::exists)) { "图片删除失败" }
        }
    }

    fun clear() = synchronized(lock) {
        SubtitleController.clear(context)
        ChatAudioPlayback.stopConversation()
        directory.listFiles()?.forEach { check(it.delete()) { "图片删除失败" } }
        audio.clear()
    }

    private fun imageFile(id: String, original: Boolean = false): File {
        require(id.isNotBlank() && id.length <= 128) { "Invalid conversation ID" }
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, if (original) "$name-original.jpg" else "$name.jpg")
    }

    private companion object {
        val lock = Any()
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
