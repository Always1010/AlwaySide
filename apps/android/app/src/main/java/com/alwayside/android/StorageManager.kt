package com.alwayside.android

import android.content.Context
import java.io.File
import java.nio.file.Files

internal class StorageManager(private val context: Context) {
    private val runtime = StorageRuntime.get(context)
    private val catalog get() = runtime.catalog
    private val cache get() = PointReadingController.cache(context)
    data class Snapshot(val inventory: StorageInventory, val conversations: List<StoredConversation>, val available: Long, val pending: Int)

    fun scan(): Snapshot {
        val history = HistoryStore(context).list()
        val owners = (history.map { it.id } + catalog.deletedOwners()).associateBy(::readingDigest)
        val bodies = catalog.bodyReferences()
        val known = catalog.assets().associateBy { it.path }.toMutableMap()
        // Import only files whose owner can be established. Missing/corrupt references stay unknown.
        cache.files().forEach { file ->
            val path = runtime.path(file)
            run {
                val refs = cache.legacyOwners(file.nameWithoutExtension)
                val matches = refs.mapNotNull(owners::get)
                val reliable = File(file.parentFile, file.nameWithoutExtension + ".refs").isFile && refs.all { it in owners }
                if (reliable) {
                    if (path !in known) {
                        runtime.register(file, "reading", matches.firstOrNull().orEmpty(), refs.isEmpty())
                        known[path] = StorageCatalog.Asset(path, "reading", matches.firstOrNull().orEmpty(), refs.isEmpty())
                    }
                    matches.forEach { owner ->
                        val marks = runCatching { cache.marks.list(owner).filter { it.key == file.nameWithoutExtension } }.getOrNull()
                        if (marks == null || marks.isEmpty()) {
                            if (owner !in catalog.references(path)) catalog.bind(path, owner, "selection:${file.nameWithoutExtension}")
                        } else {
                            marks.forEach { catalog.bind(path, owner, "mark:${it.id}") }
                            catalog.unbind(owner, "selection:${file.nameWithoutExtension}")
                            catalog.unbind(owner, "legacy:${file.nameWithoutExtension}")
                        }
                    }
                }
            }
        }
        val assets = catalog.assets().associateBy { it.path }
        val liveBodies = bodies.entries.associate { it.value to it.key }
        val files = listOf(context.noBackupFilesDir, context.cacheDir, context.filesDir, File(context.dataDir, "shared_prefs")).flatMap { root ->
            root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.toList()
        }
        val entries = files.map { file ->
            val path = runtime.path(file)
            val asset = assets[path]
            val parts = path.split('/')
            val inferredOwner = when (parts.first()) {
                "conversation_audio", "conversation_images" -> owners[file.name.substringBefore('-').substringBefore('.')]
                "conversation_subtitles" -> parts.getOrNull(1)?.let(owners::get)
                "conversations_v2" -> liveBodies[file.name]
                "point_reading_audio" -> if (parts.getOrNull(1) == "marks") owners[file.nameWithoutExtension] else null
                else -> null
            }
            val inferredKind = when (parts.first()) {
                "conversation_audio" -> "audio"
                "conversation_images" -> "images"
                "conversation_subtitles" -> "subtitles"
                "conversations_v2" -> "history"
                "point_reading_audio" -> when { parts.getOrNull(1) == "marks" -> "reading_marks"; file.extension == "refs" -> "reading_refs"; else -> "reading" }
                else -> if (path.startsWith("@cache/subtitle-exports/") && file.extension == "srt") "exports" else "other"
            }
            val shared = if (asset?.kind == "reading") catalog.references(path) else emptySet()
            val ownerSet = if (shared.isNotEmpty()) shared else setOfNotNull(asset?.owner?.ifEmpty { null } ?: inferredOwner)
            val managed = parts.first() in setOf("conversation_audio", "conversation_images", "conversation_subtitles", "conversations_v2", "point_reading_audio")
            val isTemp = ((managed && file.extension in setOf("tmp", "part", "new")) ||
                path.startsWith("@cache/listening-") || path.startsWith("@cache/point-reading-") ||
                (parts.first() == "conversations_v2" && HistoryFiles.RECORD_NAME.matches(file.name) && file.name !in liveBodies) ||
                path in setOf("conversations_v2/index.json", "conversations_v2/index.json.bak", "conversations_v2/index.json.new"))
            val sidecar = parts.first() == "point_reading_audio" && file.extension == "refs"
            val parentAsset = if (sidecar) assets.values.firstOrNull { it.path.substringBeforeLast('.') == path.substringBeforeLast('.') } else null
            val effectiveOwners = if (sidecar && parentAsset != null) catalog.references(parentAsset.path).ifEmpty { setOfNotNull(parentAsset.owner.ifEmpty { null }) } else ownerSet
            val temporary = isTemp || (path.startsWith("@cache/") && asset?.kind == "temporary")
            val unknown = (asset == null && inferredOwner == null && !temporary && inferredKind !in setOf("other", "exports") && parentAsset == null) ||
                (asset?.kind == "reading" && cache.legacyOwners(file.nameWithoutExtension).any { it !in owners })
            val inUse = effectiveOwners.any(ConversationUsage::active) || ConversationUsage.active("@file:$path") ||
                ConversationUsage.active("@reading:${file.nameWithoutExtension}") || ChatAudioPlayback.isUsingFile(file) || ActiveConversationBridge.isAudioRetained(file) ||
                (temporary && asset == null && ConversationUsage.ids().isNotEmpty())
            if (inferredOwner != null && catalog.isDeleted(inferredOwner) && inferredKind in setOf("audio", "images", "subtitles", "reading", "reading_marks", "reading_refs"))
                catalog.enqueue(inferredOwner, if (inferredKind.startsWith("reading")) "reading" else inferredKind)
            StorageEntry(path, asset?.kind ?: inferredKind, file.length(), file.lastModified(), effectiveOwners,
                inUse, asset?.idle == true && asset.kind == "reading", temporary, unknown)
        }
        return Snapshot(StorageInventory(entries), history, android.os.StatFs(context.noBackupFilesDir.path).availableBytes, catalog.pending().size)
    }

    fun clean(entries: List<StorageEntry>): Int {
        val current = scan().inventory.entries.associateBy { it.path }
        entries.forEach { selected ->
            val entry = current[selected.path] ?: return@forEach
            if (entry.modified != selected.modified || entry.bytes != selected.bytes || (!entry.safeCleanup && !entry.optionalCleanup)) return@forEach
            if (catalog.assets().none { it.path == entry.path }) runtime.register(runtime.assetFile(entry.path), "temporary", "", idle = true)
            catalog.enqueue(entry.path, "file:${entry.bytes}:${entry.modified}")
        }
        return drain()
    }

    fun removeMedia(owners: Set<String>, kinds: Set<String>): Int = synchronized(ConversationUsage.lock) {
        check(owners.none(ConversationUsage::active)) { "会话正在使用，请先结束播放或处理" }
        owners.forEach { owner ->
            kinds.forEach { kind -> catalog.enqueue(owner, if (kind == "reading") "reading_audio" else kind) }
            if ("audio" in kinds) catalog.enqueue(owner, "subtitles")
        }
        drain()
    }
    fun retry() = drain()
    private fun drain(): Int = try { runtime.drain() } finally { runtime.schedule() }
}
