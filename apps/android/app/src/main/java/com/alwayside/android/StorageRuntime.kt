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
    private var audited = false
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
            if (!audited) audited = runCatching { StorageManager(context).scan(); true }.getOrDefault(false)
            val remaining = try { drain() } catch (_: Exception) { 1 }
            synchronized(this) { scheduled = false }
            if ((remaining > 0 || catalog.pending().isNotEmpty()) && retry < 3) main.postDelayed({ schedule(retry + 1) }, 5_000L shl retry)
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
                    "reading", "reading_audio" -> {
                        if (job.kind == "reading") PointReadingController.delete(context, job.owner)
                        else PointReadingController.cache(context).removeOwner(job.owner, keepMarks = true)
                        catalog.assets().filter { it.kind == "reading" && it.owner == job.owner && catalog.references(it.path).isEmpty() }.forEach { asset ->
                            val file = assetFile(asset.path)
                            val refs = File(file.parentFile, file.nameWithoutExtension + ".refs")
                            if (refs.isFile && PointReadingController.cache(context).legacyOwners(file.nameWithoutExtension).isEmpty()) {
                                check(!file.exists() || file.delete()) { "点读文件清理失败" }
                                check(!refs.exists() || refs.delete()) { "点读引用清理失败" }
                                catalog.forget(asset.path)
                            }
                        }
                    }
                    else -> if (job.kind.startsWith("purge:")) {
                        val file = assetFile(job.owner)
                        val asset = catalog.assets().find { it.path == job.owner }
                        val refs = catalog.references(job.owner)
                        if (file.exists() && "purge:${file.length()}:${file.lastModified()}" == job.kind &&
                            refs.none { !catalog.isDeleted(it) } && (asset == null || !ConversationUsage.active(asset.owner))) {
                            check(!ChatAudioPlayback.isUsingFile(file) && !ActiveConversationBridge.isAudioRetained(file) &&
                                !ConversationUsage.active("@file:${job.owner}") && !ConversationUsage.active("@reading:${file.nameWithoutExtension}")) { "文件正在使用" }
                            check(file.delete()) { "文件清理失败" }
                            catalog.forget(job.owner)
                        }
                    } else if (job.kind.startsWith("file:")) {
                        val asset = catalog.assets().find { it.path == job.owner }
                        val file = assetFile(job.owner)
                        val fingerprint = "file:${file.length()}:${file.lastModified()}"
                        if (asset?.kind == "reading" && !file.exists()) {
                            val sidecar = File(file.parentFile, file.nameWithoutExtension + ".refs")
                            check(PointReadingController.cache(context).legacyOwners(file.nameWithoutExtension).isEmpty()) { "引用尚未释放" }
                            check(!sidecar.exists() || sidecar.delete()) { "引用文件清理失败" }
                            catalog.forget(asset.path)
                        }
                        if (file.exists() && fingerprint == job.kind && asset != null) {
                            val refs = catalog.references(asset.path)
                            check(refs.isEmpty() && !ConversationUsage.active(asset.owner) &&
                                !ConversationUsage.active("@file:${asset.path}") && !ConversationUsage.active("@reading:${file.nameWithoutExtension}") &&
                                !ChatAudioPlayback.isUsingFile(file) && !ActiveConversationBridge.isAudioRetained(file)) { "文件正在使用" }
                            check(asset.idle || asset.kind in setOf("temporary", "exports")) { "文件仍在使用" }
                            if (asset.kind == "reading") check(PointReadingController.cache(context).legacyOwners(file.nameWithoutExtension).isEmpty()) { "音频仍被引用" }
                            check(file.delete()) { "文件清理失败" }
                            if (asset.kind == "reading") {
                                val sidecar = File(file.parentFile, file.nameWithoutExtension + ".refs")
                                check(!sidecar.exists() || sidecar.delete()) { "引用文件清理失败" }
                            }
                            catalog.forget(asset.path)
                        }
                    } else error("未知的清理任务")
                }
                catalog.assets().filter { it.owner == job.owner && !assetFile(it.path).exists() }.forEach { catalog.forget(it.path) }
            }
        }, catalog::complete, catalog::failed)
        return catalog.pending().size
    }
    fun register(file: File, kind: String, owner: String, idle: Boolean = false) {
        catalog.register(path(file), kind, owner, idle)
    }
    fun published(file: File, kind: String, owner: String) {
        catalog.published(owner, kind)
        register(file, kind, owner)
    }
    fun path(file: File): String {
        val target = file.canonicalFile
        val root = context.noBackupFilesDir.canonicalFile
        if (target.toPath().startsWith(root.toPath())) return target.relativeTo(root).invariantSeparatorsPath
        val cache = context.cacheDir.canonicalFile
        if (target.toPath().startsWith(cache.toPath())) return "@cache/" + target.relativeTo(cache).invariantSeparatorsPath
        val ordinary = context.filesDir.canonicalFile
        if (target.toPath().startsWith(ordinary.toPath())) return "@files/" + target.relativeTo(ordinary).invariantSeparatorsPath
        val prefs = File(context.dataDir, "shared_prefs").canonicalFile
        require(target.toPath().startsWith(prefs.toPath()))
        return "@prefs/" + target.relativeTo(prefs).invariantSeparatorsPath
    }
    fun assetFile(path: String): File {
        require(!path.startsWith("@files/") && !path.startsWith("@prefs/")) { "此类数据仅供查看" }
        val root = (if (path.startsWith("@cache/")) context.cacheDir else context.noBackupFilesDir).canonicalFile
        val file = File(root, path.removePrefix("@cache/")).canonicalFile
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
