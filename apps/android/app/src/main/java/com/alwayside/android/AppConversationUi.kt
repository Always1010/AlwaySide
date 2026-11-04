package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

internal fun Context.appIcon(resource: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
    setImageResource(resource)
    imageTintList = ColorStateList.valueOf(INK)
    background = card(SURFACE)
    accessible(description)
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) }
    setOnClickListener { action() }
}

internal fun conversationDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

internal fun LinearLayout.addConversationList(activity: Activity, refreshed: () -> Unit) {
    val store = HistoryStore(activity)
    val media = ConversationMediaStore(activity)
    fun failed() = Toast.makeText(activity, "操作未全部完成，请重试并检查本机存储", Toast.LENGTH_LONG).show()
    fun clear(values: List<StoredConversation>?) {
        AlertDialog.Builder(activity).setTitle("清空本机会话？")
            .setMessage("将删除全部会话文字和已保存图片，无法撤销。正在悬浮窗中打开的对应会话也会停止。")
            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                runCatching {
                    values.orEmpty().forEach {
                        ActiveConversationBridge.detach(it.id)
                        ActiveConversationBridge.takeSession(it.id)?.dispose?.invoke()
                    }
                    store.clear(); media.clear()
                }.onSuccess { refreshed() }.onFailure { failed() }
            }.show()
    }
    val values = try { store.list() } catch (_: Exception) {
        addView(activity.label("无法读取会话记录。可以重试，或明确清空损坏的本机记录。", 15f, true))
        action("重试读取") { refreshed() }
        action("清空无法读取的记录") { clear(null) }
        return
    }
    if (values.isEmpty()) {
        addView(activity.label("还没有会话\n从悬浮球截图、听一段，或创建文字对话后，记录会显示在这里。", 16f, true))
        return
    }
    values.forEach { value ->
        val entry = activity.column().apply {
            background = card(SURFACE)
            isClickable = true; isFocusable = true
            accessible("打开会话：${value.title.ifBlank { "未命名会话" }}")
            setOnClickListener {
                activity.startActivity(Intent(activity, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ChatActivity.EXTRA_ID, value.id))
            }
            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(activity.label(value.title.ifBlank { "待提问会话" }, 19f).apply { maxLines = 2 },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(activity.appIcon(android.R.drawable.ic_menu_delete, "删除此会话") {
                    AlertDialog.Builder(activity).setTitle("删除这段会话？")
                        .setMessage("将删除会话文字和已保存图片，无法撤销。对应悬浮会话会停止。")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runCatching {
                                ActiveConversationBridge.detach(value.id)
                                ActiveConversationBridge.takeSession(value.id)?.dispose?.invoke()
                                store.delete(value.id); media.deleteImage(value.id)
                            }.onSuccess { refreshed() }.onFailure { failed() }
                        }.show()
                })
            }
            addView(titleRow)
            val kind = when (value.kind) { "audio" -> "音频"; "photo", "screen", "image" -> "截图"; else -> "文字" }
            addView(activity.label("$kind · ${conversationDate(value.updatedAt)}", 12f, true))
            addView(activity.label(value.status.ifBlank { "已保存" }, 13f, true).apply { maxLines = 2 })
            val preview = value.messages.lastOrNull()?.text.orEmpty().ifBlank { value.evidence }
            if (preview.isNotBlank()) addView(activity.label(preview.take(180), 14f).apply { maxLines = 3 })
        }
        addView(entry, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
    }
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.END
        setPadding(0, activity.dp(12), 0, 0)
        addView(activity.appIcon(android.R.drawable.ic_menu_delete, "清空全部会话") { clear(values) })
    })
}
