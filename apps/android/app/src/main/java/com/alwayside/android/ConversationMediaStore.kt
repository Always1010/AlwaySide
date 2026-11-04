package com.alwayside.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Optional screenshot archive, isolated from credentials, JSON and Android backup. */
internal class ConversationMediaStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversation_images")

    fun saveImage(id: String, bitmap: Bitmap) = synchronized(lock) {
        require(!bitmap.isRecycled) { "图片已失效，请重新截图" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建图片目录" }
        val ratio = minOf(1.0, 2000.0 / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val bytes = try { ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "无法保存图片" }
            output.toByteArray()
        } } finally { if (scaled !== bitmap) scaled.recycle() }
        check(bytes.size <= MAX_IMAGE_BYTES) { "图片过大，无法长期保存" }
        val target = AtomicFile(imageFile(id))
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
        trim()
    }

    fun loadImage(id: String): Bitmap? = synchronized(lock) {
        val target = imageFile(id)
        if (!target.exists() || target.length() !in 1..MAX_IMAGE_BYTES) return@synchronized null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (bounds.outWidth !in 1..2000 || bounds.outHeight !in 1..2000) return@synchronized null
        BitmapFactory.decodeFile(target.path)
    }

    fun deleteImage(id: String) = synchronized(lock) { AtomicFile(imageFile(id)).delete() }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { check(it.delete()) { "图片删除失败" } }
    }

    private fun imageFile(id: String): File {
        require(id.isNotBlank() && id.length <= 128) { "Invalid conversation ID" }
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.jpg")
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= 50 || total > 32L * 1024 * 1024) AtomicFile(file).delete()
        }
    }

    private companion object {
        val lock = Any()
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    }
}
