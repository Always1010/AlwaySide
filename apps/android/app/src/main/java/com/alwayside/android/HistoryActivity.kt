package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.text.DateFormat
import java.util.Date

class HistoryActivity : Activity() {
    private lateinit var store: HistoryStore
    private var opened: StoredConversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HistoryStore(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
        showList()
    }

    private fun showList() {
        opened = null
        val body = column()
        body.addView(header("会话历史") { finish() })
        body.addView(label("仅在本机保存最近 50 段文字会话，每段保留最多 100 条消息；较长文本会截断。原始图片、录音和密钥不进入历史。", 14f, true))
        try {
            val values = store.list()
            if (values.isEmpty()) body.addView(label("还没有文字会话。使用助手完成问答后，可在这里查看和继续。"))
            values.forEach { value ->
                val entry = column().apply {
                    background = card(SURFACE)
                    addView(label(value.title.ifBlank { "未命名会话" }, 20f))
                    addView(label(date(value.updatedAt), 13f, true))
                    addView(label(value.messages.lastOrNull()?.text.orEmpty().take(160), 14f))
                    action("打开会话") { showDetails(value) }
                }
                body.addView(entry, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            if (values.isNotEmpty()) body.action("清空全部历史") {
                AlertDialog.Builder(this).setTitle("清空本机文字历史？")
                    .setMessage("将删除全部已归档会话，无法撤销。当前悬浮助手中的会话不受影响。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                        try { store.clear(); showList() } catch (_: Exception) { toast("删除失败，请检查本机存储") }
                    }.show()
            }
        } catch (_: Exception) {
            body.addView(label("无法读取历史，未覆盖现有文件。请检查设备可用空间，或明确清空损坏的本机历史。"))
            body.action("重试读取") { showList() }
            body.action("清空无法读取的历史") {
                AlertDialog.Builder(this).setTitle("清空历史文件？").setMessage("此操作无法撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                        try { store.clear(); showList() } catch (_: Exception) { toast("删除失败") }
                    }.show()
            }
        }
        display(body)
    }

    private fun showDetails(value: StoredConversation) {
        opened = value
        val body = column()
        body.addView(header("会话内容") { showList() })
        body.addView(label(value.title.ifBlank { "未命名会话" }, 24f))
        body.addView(label(date(value.updatedAt), 14f, true))
        body.addView(label("媒体不可用：历史不保存图片或录音。继续时只恢复文字；如需新画面或声音，请在助手中重新采集。", 14f, true))
        if (value.evidence.isNotBlank()) body.addView(label("原证据记录：${value.evidence}", 14f, true).apply { setTextIsSelectable(true) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(icon(R.drawable.ic_copy, "复制整段文字会话") { copy(value) })
            addView(icon(android.R.drawable.ic_menu_delete, "删除此会话") { delete(value) })
        }
        body.addView(actions)
        body.action("仅恢复文字并继续追问", true) { resume(value) }
        body.addView(label("打开悬浮面板，不会自动发送请求，也不会自动恢复屏幕共享或录音。", 13f, true))
        if (value.transcript.isNotBlank()) {
            body.addView(label("转写原文", 22f))
            body.addView(label(value.transcript).apply { setTextIsSelectable(true) })
        }
        value.messages.forEach { message ->
            if (message.evidence.isNotBlank()) body.addView(label(message.evidence,12f,true))
            val role = when (message.role) { "user" -> "你"; "assistant" -> "助手"; else -> "上下文" }
            body.addView(label(role, 20f))
            body.addView(label("").apply { text = markdown(message.text); setTextIsSelectable(true) })
        }
        display(body)
    }

    private fun resume(value: StoredConversation) {
        if (!Settings.canDrawOverlays(this)) { toast("请先返回主页允许悬浮显示，再继续历史会话"); return }
        try {
            startForegroundService(Intent(this, AssistantService::class.java).setAction("resume_history")
                .putExtra("historyId", value.id))
            toast("已请求打开文字会话；媒体需要重新采集")
        } catch (_: RuntimeException) { toast("无法打开悬浮助手，请返回主页重新开启") }
    }

    private fun delete(value: StoredConversation) {
        AlertDialog.Builder(this).setTitle("删除这段文字历史？")
            .setMessage("删除后无法从历史恢复。当前悬浮助手中的会话不受影响。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                try { store.delete(value.id); showList() } catch (_: Exception) { toast("删除失败，未覆盖历史") }
            }.show()
    }

    private fun copy(value: StoredConversation) {
        val text = buildString {
            append(value.title).append('\n').append(date(value.updatedAt)).append('\n')
            if (value.evidence.isNotBlank()) append("证据：").append(value.evidence).append('\n')
            if (value.transcript.isNotBlank()) append("\n转写原文\n").append(value.transcript).append('\n')
            value.messages.forEach { append('\n').append(if (it.role == "user") "你" else if (it.role == "assistant") "助手" else "上下文")
                .append("\n").append(it.text).append('\n') }
        }
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide 会话", text))
            toast("已复制文字会话")
        } catch (_: RuntimeException) { toast("复制失败，可长按文字选择复制") }
    }

    private fun header(title: String, close: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(title, 28f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(android.R.drawable.ic_menu_close_clear_cancel, if (opened == null) "关闭历史" else "返回历史列表", close))
    }

    private fun icon(resource: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = android.content.res.ColorStateList.valueOf(INK)
        background = card(SURFACE); accessible(description)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
        setOnClickListener { action() }
    }

    private fun display(body: LinearLayout) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true; isFillViewport = true; addView(body)
        })
    }

    private fun navigateBack() { if (opened != null) showList() else finish() }

    // Android 10–12 have no OnBackInvokedDispatcher; only those releases use this fallback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    @Deprecated("Legacy fallback for Android API 29–32")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) navigateBack() else super.onBackPressed()
    }

    /** Small, inert Markdown display: headings, bullets and bold, with no HTML or link execution. */
    private fun markdown(value: String): CharSequence {
        val result = SpannableStringBuilder()
        value.lineSequence().forEach { line ->
            val heading = Regex("^#{1,6}\\s+").find(line)
            val start = result.length
            val content = if (heading != null) line.substring(heading.value.length)
                else if (line.startsWith("- ") || line.startsWith("* ")) "• ${line.substring(2)}" else line
            var position = 0
            Regex("\\*\\*(.+?)\\*\\*").findAll(content).forEach { match ->
                result.append(content.substring(position, match.range.first))
                val boldStart = result.length
                result.append(match.groupValues[1])
                result.setSpan(StyleSpan(Typeface.BOLD), boldStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                position = match.range.last + 1
            }
            result.append(content.substring(position))
            if (heading != null && result.length > start) {
                result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(RelativeSizeSpan(1.15f), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            result.append('\n')
        }
        return result
    }

    private fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
