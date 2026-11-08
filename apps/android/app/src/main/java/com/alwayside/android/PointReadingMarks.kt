package com.alwayside.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Positions refer to displayed text, never Markdown source offsets or synthesized audio timings. */
internal data class ReadingAnchor(
    val block: String, val document: String, val start: Int, val end: Int,
    val quote: String, val before: String, val after: String,
) {
    val id: String = readingDigest(listOf(block, document, start.toString(), end.toString()).joinToString("\u0000"))

    fun locate(displayed: String, digest: String = readingDigest(displayed)): IntRange? {
        if (document == digest) {
            return if (start >= 0 && end <= displayed.length && start < end && displayed.substring(start, end) == quote)
                start until end else null
        }
        // A truncated overlay can later open as a complete message. Only reattach with exact context.
        val matches = mutableListOf<Int>()
        var from = 0
        while (from <= displayed.length - quote.length) {
            val at = displayed.indexOf(quote, from)
            if (at < 0) break
            if (at >= before.length && displayed.regionMatches(at - before.length, before, 0, before.length) &&
                displayed.regionMatches(at + quote.length, after, 0, after.length)) matches.add(at)
            if (matches.size > 1) return null
            from = at + 1
        }
        return matches.singleOrNull()?.let { it until it + quote.length }
    }

    companion object {
        fun message(index: Int, role: String) = "message:$index:$role"
        fun create(block: String, displayed: String, first: Int, last: Int): ReadingAnchor? {
            if (block.isBlank() || first < 0 || last > displayed.length || first >= last) return null
            var start = first
            var end = last
            while (start < end && displayed[start].isWhitespace()) start++
            while (end > start && displayed[end - 1].isWhitespace()) end--
            if (start == end || end - start > 3500) return null
            return ReadingAnchor(block, readingDigest(displayed), start, end, displayed.substring(start, end),
                displayed.substring((start - 32).coerceAtLeast(0), start),
                displayed.substring(end, (end + 32).coerceAtMost(displayed.length)))
        }
    }
}

internal data class ReadingMark(val anchor: ReadingAnchor, val key: String, val voice: ReadingVoice) {
    val id: String get() = anchor.id
}

/** Local sidecar records: excluded from chat prompts, copied text and the conversation archive. */
internal class PointReadingMarks(private val directory: File) {
    private val loaded = object : LinkedHashMap<String, List<ReadingMark>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReadingMark>>?) = size > 16
    }

    @Synchronized fun list(owner: String, block: String? = null): List<ReadingMark> {
        val values = loaded[owner] ?: read(owner).also { loaded[owner] = it }
        return if (block == null) values else values.filter { it.anchor.block == block }
    }

    @Synchronized fun put(owner: String, mark: ReadingMark) {
        require(mark.key == mark.voice.key(mark.anchor.quote)) { "点读记录与音频不一致" }
        // Re-reading an identical location in a different voice updates its replay target.
        val previous = list(owner)
        val same = previous.firstOrNull { it.id == mark.id }
        if (same == mark) return
        val next = previous.filterNot { it.id == same?.id } + mark
        require(next.size <= 4096) { "本会话点读标记已达上限，请先移除部分标记" }
        write(owner, next)
    }

    @Synchronized fun remove(owner: String, id: String) = write(owner, list(owner).filterNot { it.id == id })
    @Synchronized fun removeOwner(owner: String) {
        val target = file(owner)
        check(!target.exists() || target.delete()) { "无法删除点读标记" }
        loaded.remove(owner)
    }
    @Synchronized fun clear() {
        directory.listFiles()?.filter { it.extension == "marks" }?.forEach { check(it.delete()) { "无法删除点读标记" } }
        loaded.clear()
    }

    private fun file(owner: String) = File(directory, "${readingDigest(owner)}.marks")
    private fun read(owner: String): List<ReadingMark> {
        val target = file(owner)
        if (!target.isFile) return emptyList()
        require(target.length() <= 32L * 1024 * 1024) { "点读标记文件过大" }
        return DataInputStream(target.inputStream().buffered()).use { input ->
            require(input.readInt() == 1) { "不支持的点读标记版本" }
            val count = input.readInt()
            require(count in 0..4096) { "点读标记数量无效" }
            List(count) {
                val anchor = ReadingAnchor(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(),
                    input.readUTF(), input.readUTF(), input.readUTF())
                val mark = ReadingMark(anchor, input.readUTF(), ReadingVoice(input.readUTF(), input.readUTF(), input.readUTF()))
                require(anchor.quote.isNotBlank() && anchor.quote.length <= 3500 && anchor.start >= 0 &&
                    anchor.end - anchor.start == anchor.quote.length && mark.key == mark.voice.key(anchor.quote)) { "点读标记损坏" }
                mark
            }.also { require(input.read() == -1) { "点读标记格式无效" } }
        }
    }
    private fun write(owner: String, values: List<ReadingMark>) {
        if (values.isEmpty()) { removeOwner(owner); return }
        check(directory.isDirectory || directory.mkdirs()) { "无法保存点读标记" }
        val target = file(owner)
        val temporary = File(directory, "${target.name}.part")
        try {
            temporary.outputStream().use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(1); output.writeInt(values.size)
                values.forEach { mark ->
                    val anchor = mark.anchor
                    output.writeUTF(anchor.block); output.writeUTF(anchor.document)
                    output.writeInt(anchor.start); output.writeInt(anchor.end)
                    output.writeUTF(anchor.quote); output.writeUTF(anchor.before); output.writeUTF(anchor.after)
                    output.writeUTF(mark.key); output.writeUTF(mark.voice.connection)
                    output.writeUTF(mark.voice.model); output.writeUTF(mark.voice.voice)
                }
                output.flush(); stream.fd.sync()
            }
            require(temporary.length() <= 32L * 1024 * 1024) { "本会话点读标记过多，请先移除部分标记" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            loaded[owner] = values.toList()
        } finally { temporary.delete() }
    }
}
