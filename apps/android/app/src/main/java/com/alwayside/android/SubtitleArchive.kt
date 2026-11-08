package com.alwayside.android

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID

/** Private, per-recording storage. A single atomic pointer publishes all three immutable files. */
internal class SubtitleArchive(
    private val directory: File,
    private val encode: (Map<String, Any?>) -> String = WireJson::encode,
    private val decode: (String) -> Map<String, Any?> = WireJson::decode,
) {
    fun load(id: String, method: SubtitleMethod): SubtitleBundle? = synchronized(LOCK) {
        val folder = methodDirectory(id, method)
        val current = File(folder, "current")
        if (!current.exists()) return null
        val generation = readBounded(current, 80).trim()
        require(GENERATION.matches(generation)) { "字幕版本索引损坏" }
        val published = File(folder, generation)
        val bundle = SubtitleArchiveCodec.fromMap(decode(readBounded(File(published, "metadata.json"))))
        require(bundle.method == method && bundle.bilingualComplete) { "字幕版本不完整" }
        // Reject interrupted or externally corrupted exports instead of reporting a usable publication.
        require(readBounded(File(published, "en.srt")) == SubtitleFormat.srt(bundle.cues)) { "原文字幕损坏" }
        require(readBounded(File(published, "zh-CN.srt")) == SubtitleFormat.srt(bundle.cues, bundle.translations)) {
            "译文字幕损坏"
        }
        return bundle
    }

    fun loadDraft(id: String, method: SubtitleMethod): SubtitleBundle? = synchronized(LOCK) {
        val draft = File(methodDirectory(id, method), "draft.json")
        if (!draft.exists()) return null
        return SubtitleArchiveCodec.fromMap(decode(readBounded(draft))).also {
            require(it.method == method) { "字幕检查点方案不一致" }
        }
    }

    fun saveDraft(id: String, bundle: SubtitleBundle): Unit = synchronized(LOCK) {
        val content = metadata(bundle)
        val folder = methodDirectory(id, bundle.method)
        ensureDirectory(folder)
        atomicWrite(File(folder, "draft.json"), content)
    }

    fun publish(id: String, bundle: SubtitleBundle): Unit = synchronized(LOCK) {
        SubtitleArchiveCodec.validate(bundle)
        require(bundle.bilingualComplete) { "双语字幕尚未完成" }
        val content = metadata(bundle)
        val folder = methodDirectory(id, bundle.method)
        ensureDirectory(folder)
        val previous = File(folder, "current").takeIf(File::exists)?.let { readBounded(it, 80).trim() }
        val name = "g-${UUID.randomUUID()}"
        val generation = File(folder, name)
        ensureDirectory(generation)
        var published = false
        try {
            durableWrite(File(generation, "en.srt"), SubtitleFormat.srt(bundle.cues))
            durableWrite(File(generation, "zh-CN.srt"), SubtitleFormat.srt(bundle.cues, bundle.translations))
            durableWrite(File(generation, "metadata.json"), content)
            syncDirectory(generation)
            syncDirectory(folder)
            atomicWrite(File(folder, "current"), name)
            published = true
        } finally {
            if (!published) generation.deleteRecursively()
        }
        // Keep the previous publication as well. Draft checkpoints never affect the current pointer.
        folder.listFiles()?.filter { it.isDirectory && GENERATION.matches(it.name) &&
            it.name != name && it.name != previous }?.forEach { it.deleteRecursively() }
        File(folder, "draft.json").delete()
        Unit
    }

    fun discardDraft(id: String, method: SubtitleMethod): Unit = synchronized(LOCK) {
        deleteChecked(File(methodDirectory(id, method), "draft.json"))
    }

    fun delete(id: String, method: SubtitleMethod): Unit = synchronized(LOCK) {
        deleteChecked(methodDirectory(id, method))
    }

    fun delete(id: String): Unit = synchronized(LOCK) { deleteChecked(recordingDirectory(id)) }

    fun clear(): Unit = synchronized(LOCK) {
        directory.listFiles()?.forEach(::deleteChecked)
        Unit
    }

    private fun metadata(bundle: SubtitleBundle): String = encode(SubtitleArchiveCodec.toMap(bundle)).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "字幕元数据过大" }
    }

    private fun recordingDirectory(id: String): File {
        require(id.isNotBlank() && id.length <= 4096) { "录音标识无效" }
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, hash)
    }

    private fun methodDirectory(id: String, method: SubtitleMethod) = File(recordingDirectory(id), method.id)

    private fun readBounded(file: File, limit: Int = MAX_FILE_BYTES): String = file.inputStream().use {
        require(file.length() <= limit) { "字幕文件过大" }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer, 0, minOf(buffer.size, limit + 1 - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
            require(bytes.size() <= limit) { "字幕文件过大" }
        }
        bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun ensureDirectory(file: File) {
        if (file.isDirectory) return
        val parent = file.parentFile ?: throw IOException("无法创建字幕目录")
        ensureDirectory(parent)
        if (!file.mkdir() && !file.isDirectory) throw IOException("无法创建字幕目录")
        syncDirectory(parent)
    }

    private fun durableWrite(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "字幕文件过大" }
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun atomicWrite(target: File, text: String) {
        val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
        try {
            durableWrite(temporary, text)
            // Never fall back to a non-atomic copy: readers must see either the old or new version.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(requireNotNull(target.parentFile))
        } finally {
            temporary.delete()
        }
    }

    private fun deleteChecked(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("无法删除字幕文件")
    }

    private fun syncDirectory(folder: File) {
        // Supported by Android/Linux. Some desktop test filesystems cannot open directory channels.
        try {
            FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Atomic rename still guarantees coherent publications on such filesystems.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is an additional power-loss durability guarantee where available.
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        val LOCK = Any()
        val GENERATION = Regex("g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Strict map codec is independent of Android's JSON implementation, including in JVM tests. */
internal object SubtitleArchiveCodec {
    private const val MAX_CUES = 20_000
    private const val MAX_TEXT = 4_000
    private const val MAX_TOTAL_TEXT = 2_000_000L
    private val HASH = Regex("[0-9a-fA-F]{64}")
    private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")

    fun validate(bundle: SubtitleBundle) {
        require(HASH.matches(bundle.audioHash) && HASH.matches(bundle.requestSignature)) { "字幕摘要无效" }
        require(bundle.durationMs in 1..86_400_000L && bundle.updatedAt >= 0) { "字幕时长无效" }
        require(bundle.totalParts in 0..10_000 && bundle.completedParts in 0..bundle.totalParts) { "字幕进度无效" }
        require(!bundle.transcriptionComplete || bundle.completedParts == bundle.totalParts) { "转写进度尚未完成" }
        require(bundle.translationModel.isEmpty() || MODEL.matches(bundle.translationModel)) { "翻译模型无效" }
        require(bundle.cues.size <= MAX_CUES && bundle.translations.size <= bundle.cues.size) { "字幕数量过多" }
        var previousStart = -1L
        var previousId = 0
        var textSize = 0L
        bundle.cues.forEach {
            require(it.id > previousId && it.startMs >= previousStart && it.startMs >= 0 &&
                it.endMs > it.startMs && it.endMs <= bundle.durationMs) { "字幕时间或编号无效" }
            validateText(it.text)
            textSize += it.text.length
            previousStart = it.startMs
            previousId = it.id
        }
        val ids = bundle.cues.mapTo(HashSet()) { it.id }
        bundle.translations.forEach { (id, text) ->
            require(id in ids) { "译文编号与原文不匹配" }
            validateText(text)
            textSize += text.length
        }
        require(textSize <= MAX_TOTAL_TEXT) { "字幕文本过大" }
    }

    private fun validateText(text: String) {
        require(text.isNotBlank() && text.length <= MAX_TEXT && '\u0000' !in text) { "字幕文本无效" }
    }

    fun toMap(bundle: SubtitleBundle): Map<String, Any?> {
        validate(bundle)
        return linkedMapOf("version" to 1, "audioHash" to bundle.audioHash, "method" to bundle.method.id,
            "durationMs" to bundle.durationMs, "requestSignature" to bundle.requestSignature,
            "completedParts" to bundle.completedParts, "totalParts" to bundle.totalParts,
            "transcriptionComplete" to bundle.transcriptionComplete, "translationModel" to bundle.translationModel,
            "updatedAt" to bundle.updatedAt,
            "cues" to bundle.cues.map { linkedMapOf("id" to it.id, "startMs" to it.startMs,
                "endMs" to it.endMs, "text" to it.text) },
            "translations" to bundle.translations.mapKeys { it.key.toString() })
    }

    fun fromMap(map: Map<String, Any?>): SubtitleBundle {
        require(map.number("version") == 1L) { "字幕存储版本不支持" }
        val cueList = map["cues"] as? List<*> ?: error("字幕列表缺失")
        require(cueList.size <= MAX_CUES) { "字幕数量过多" }
        val cues = cueList.map { item ->
            val cue = item as? Map<*, *> ?: error("字幕格式无效")
            SubtitleCue(cue.int("id"), cue.number("startMs"), cue.number("endMs"), cue.string("text"))
        }
        val rawTranslations = map["translations"] as? Map<*, *> ?: error("译文列表缺失")
        require(rawTranslations.size <= cues.size) { "译文数量无效" }
        val translations = rawTranslations.entries.associate { (key, value) ->
            val id = (key as? String)?.toIntOrNull() ?: error("译文编号无效")
            require(key == id.toString()) { "译文编号无效" }
            id to (value as? String ?: error("译文文本无效"))
        }
        return SubtitleBundle(map.string("audioHash"),
            SubtitleMethod.entries.firstOrNull { it.id == map.string("method") } ?: error("字幕方案无效"),
            map.number("durationMs"), map.string("requestSignature"), cues, translations,
            map.int("completedParts"), map.int("totalParts"),
            map["transcriptionComplete"] as? Boolean ?: error("字幕状态无效"),
            map.string("translationModel"), map.number("updatedAt")).also(::validate)
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: error("字幕字段缺失: $key")
    private fun Map<*, *>.number(key: String): Long {
        val value = this[key]
        require(value is Int || value is Long) { "字幕数字字段无效: $key" }
        return (value as Number).toLong()
    }
    private fun Map<*, *>.int(key: String): Int = number(key).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "字幕编号越界" }
    }.toInt()
}
