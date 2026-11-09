package com.alwayside.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class StorageRuntime private constructor(val context: Context) {
    val catalog = StorageCatalog(context)
    private val worker = Executors.newSingleThreadExecutor()
    private var scheduled = false
    private val main = Handler(Looper.getMainLooper())
    init {
        ConversationUsage.onIdle(this) {
            worker.execute { runCatching { HistoryStore(context).trim() }; schedule() }
        }
    }
    @Synchronized fun schedule(retry: Int = 0) {
        if (scheduled) return
        scheduled = true
        worker.execute {
            val remaining = try { drain() } catch (_: Exception) { 1 }
            synchronized(this) { scheduled = false }
            if (remaining > 0 && retry < 3) main.postDelayed({ schedule(retry + 1) }, 5_000L shl retry)
        }
    }
    fun drain(): Int {
        val media = ConversationMediaStore(context)
        StorageCleanup.run(catalog.pending().filterNot { ConversationUsage.active(it.owner) }, { job ->
            synchronized(ConversationUsage.lock) {
                check(!ConversationUsage.active(job.owner)) { "会话正在使用" }
                when (job.kind) {
                    "images" -> media.deleteImage(job.owner)
                    "audio" -> { ChatAudioPlayback.stopConversation(job.owner); media.deleteAudioFile(job.owner) }
                    "subtitles" -> SubtitleController.deleteConversation(context, job.owner)
                    "reading" -> PointReadingController.delete(context, job.owner)
                    else -> error("未知的清理任务")
                }
            }
        }, catalog::complete, catalog::failed)
        return catalog.pending().size
    }
    fun register(file: File, kind: String, owner: String, idle: Boolean = false) {
        val root = context.noBackupFilesDir.canonicalFile
        val target = file.canonicalFile
        require(target.toPath().startsWith(root.toPath()))
        catalog.register(target.relativeTo(root).invariantSeparatorsPath, kind, owner, idle)
    }
    fun assetFile(path: String): File {
        val root = context.noBackupFilesDir.canonicalFile
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root)
        return file
    }
    companion object {
        private val instances = ConcurrentHashMap<String, StorageRuntime>()
        fun get(context: Context): StorageRuntime = instances.computeIfAbsent(context.noBackupFilesDir.canonicalPath) {
            StorageRuntime(context.applicationContext)
        }
    }
}
