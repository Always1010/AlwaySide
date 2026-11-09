package com.alwayside.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** File scans and cleanup run off main; deletion revalidates the displayed snapshot. */
internal class StorageSettingsUi(private val context: Context, private val parent: LinearLayout,
    private val show: (MaterialAlertDialogBuilder) -> AlertDialog) {
    private val manager = StorageManager(context.applicationContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private var busy = false
    private var snapshot: StorageManager.Snapshot? = null
    private val titles = linkedMapOf("history" to "聊天记录", "images" to "截图", "audio" to "原始录音",
        "subtitles" to "字幕", "reading" to "点读音频与标记", "exports" to "导出临时副本", "temporary" to "临时文件", "other" to "其他数据")
    init { refresh() }
    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    private fun group(entry: StorageEntry) = when {
        entry.temporary -> "temporary"
        entry.kind.startsWith("reading") -> "reading"
        else -> entry.kind
    }
    fun dispose() { disposed = true; main.removeCallbacksAndMessages(null); worker.shutdownNow() }
    private fun run(action: () -> Int) {
        if (busy || disposed) return
        busy = true
        worker.execute {
            val result = runCatching(action)
            main.post {
                if (disposed) return@post
                busy = false
                Toast.makeText(context, result.fold({ if (it == 0) "清理完成" else "部分文件尚未清理，可稍后重试" },
                    { it.message?.take(100) ?: "操作失败，请重试" }), Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }
    fun refresh() {
        if (busy || disposed) return
        busy = true
        parent.removeAllViews(); parent.addView(context.label("正在统计本机数据…", 14f, true))
        worker.execute {
            val result = runCatching { manager.scan() }
            main.post {
                if (disposed) return@post
                busy = false
                result.onSuccess { snapshot = it; render(it) }.onFailure {
                    parent.removeAllViews(); parent.addView(context.label("无法读取存储信息，请重试。", 14f, true))
                    parent.addView(context.settingsListRow("重新统计") { refresh() })
                }
            }
        }
    }
    private fun render(value: StorageManager.Snapshot) = with(context) {
        parent.removeAllViews()
        parent.addView(settingsSectionHeader("本机数据", help = "说明", onHelp = {
            show(MaterialAlertDialogBuilder(context).setTitle("存储管理")
                .setMessage("数据保存在本机，不设媒体总容量上限，受设备可用存储空间限制。\n\n正在使用的会话和文件暂不能清理。共享音频只计一次，需解除全部引用才能释放。\n\n原始录音和截图删除后无法恢复；点读重新生成及重新制作字幕可能收费。其他应用中已保存的导出文件需自行管理。")
                .setPositiveButton("知道了", null))
        }))
        parent.addView(label(size(value.inventory.bytes), 30f))
        parent.addView(label("设备可用 ${size(value.available)}", 13f, true))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.forEach { (kind, title) ->
            val entries = value.inventory.entries.filter { group(it) == kind }
            list.addView(settingsListRow(title, "${entries.size} 个文件 · ${size(entries.sumOf { it.bytes })}", inlineValue = true) { category(kind, entries) })
        }
        parent.addView(settingsListGroup(list))
        parent.addView(settingsSectionHeader("管理"))
        parent.addView(settingsListRow("按会话查看", "${value.conversations.size} 段会话") { conversations() })
        parent.addView(settingsListRow("可清理文件", size(value.inventory.entries.filter { it.safeCleanup || it.optionalCleanup }.sumOf { it.bytes })) { cleanup() })
        if (value.pending > 0) parent.addView(settingsListRow("重试未完成的清理", "${value.pending} 项") { run { manager.retry() } })
        val unknown = value.inventory.entries.count { it.unknown }
        if (unknown > 0) parent.addView(label("$unknown 个文件的用途尚未确认，未纳入可清理范围。", 12f, true))
    }
    private fun category(kind: String, entries: List<StorageEntry>) {
        val text = "${entries.size} 个文件 · ${size(entries.sumOf { it.bytes })}\n正在使用 ${size(entries.filter { it.inUse }.sumOf { it.bytes })}" +
            when (kind) {
                "reading" -> "\n闲置可复用 ${size(entries.filter { it.idle }.sumOf { it.bytes })}"
                "audio" -> "\n总时长 ${android.text.format.DateUtils.formatElapsedTime(entries.sumOf { (it.bytes - 44).coerceAtLeast(0) } / (RecordingLimits.SAMPLE_RATE * 2))}"
                else -> ""
            }
        val builder = MaterialAlertDialogBuilder(context).setTitle(titles[kind]).setMessage(text).setNegativeButton("关闭", null)
        if (kind in setOf("images", "audio", "subtitles", "reading", "history")) builder.setPositiveButton("按会话查看") { _, _ -> conversations(kind.takeUnless { it == "history" }) }
        if (kind in setOf("exports", "temporary")) builder.setPositiveButton("管理可清理文件") { _, _ -> cleanup() }
        show(builder)
    }
    private fun cleanup() {
        val value = snapshot ?: return
        val options = listOf("无效临时文件" to value.inventory.entries.filter { it.safeCleanup },
            "闲置点读音频" to value.inventory.entries.filter { it.idle && it.optionalCleanup },
            "导出临时副本" to value.inventory.entries.filter { it.kind == "exports" && it.optionalCleanup })
        val checked = booleanArrayOf(true, false, false)
        show(MaterialAlertDialogBuilder(context).setTitle("选择可清理文件")
            .setMultiChoiceItems(options.map { "${it.first} · ${size(it.second.sumOf { entry -> entry.bytes })}" }.toTypedArray(), checked) { _, i, selected -> checked[i] = selected }
            .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ ->
                val entries = options.flatMapIndexed { i, option -> if (checked[i]) option.second else emptyList() }.distinctBy { it.path }
                confirm("清理所选文件？", "预计释放 ${size(entries.sumOf { it.bytes })}。保留聊天记录及仍被引用的媒体。" +
                    if (checked[1]) "\n\n之后重新生成这些点读音频可能收费。" else "") { manager.clean(entries) }
            })
    }
    private fun conversations(kind: String? = null) {
        val value = snapshot ?: return
        fun files(owner: String) = value.inventory.entries.filter { owner in it.owners && (kind == null || group(it) == kind) }
        val sorted = value.conversations.sortedByDescending { files(it.id).sumOf { entry -> entry.bytes } }
        val checked = BooleanArray(sorted.size)
        val labels = sorted.map { owner ->
            "${owner.title}\n${size(files(owner.id).sumOf { it.bytes })}${if (files(owner.id).any { it.owners.size > 1 }) " · 含共享文件" else ""}${if (ConversationUsage.active(owner.id)) " · 使用中" else ""}"
        }.toTypedArray()
        show(MaterialAlertDialogBuilder(context).setTitle("按会话管理").setMultiChoiceItems(labels, checked) { _, i, selected -> checked[i] = selected }
            .setNegativeButton("取消", null).setPositiveButton("管理所选") { _, _ ->
                val owners = sorted.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                if (owners.isEmpty()) return@setPositiveButton
                if (owners.any(ConversationUsage::active)) Toast.makeText(context, "请先关闭所选会话，并结束播放或处理", Toast.LENGTH_LONG).show()
                else media(owners)
            })
    }
    private fun media(owners: Set<String>) {
        val choices = listOf("查看占用" to emptySet(), "删除截图" to setOf("images"), "删除录音和字幕" to setOf("audio", "subtitles"),
            "删除字幕" to setOf("subtitles"), "清理点读音频" to setOf("reading"))
        show(MaterialAlertDialogBuilder(context).setTitle("${owners.size} 段会话").setItems(choices.map { it.first }.toTypedArray()) { _, index ->
            val (title, kinds) = choices[index]
            val value = snapshot ?: return@setItems
            if (kinds.isEmpty()) {
                val message = titles.map { (kind, label) -> "$label：${size(value.inventory.entries.filter { it.owners.any(owners::contains) && group(it) == kind }.sumOf { it.bytes })}" }.joinToString("\n")
                show(MaterialAlertDialogBuilder(context).setTitle("所选会话占用").setMessage(message).setPositiveButton("关闭", null))
            } else {
                val consequence = if ("reading" in kinds) "保留点读标记，之后重新生成可能收费。" else "这些媒体删除后无法恢复；聊天文字保留。"
                confirm("$title？", "预计释放 ${size(value.inventory.releasable(owners, kinds))}。仍被其他会话引用的文件保留。\n\n$consequence") { manager.removeMedia(owners, kinds) }
            }
        }.setNegativeButton("关闭", null))
    }
    private fun confirm(title: String, message: String, action: () -> Int) {
        show(MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ -> run(action) })
    }
}
