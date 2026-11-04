package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

internal fun Context.appIcon(resource: Int, description: String, action: () -> Unit) =
    iconButton(resource, description, action)

internal fun Context.appHeader(back: (() -> Unit)? = null, assistant: (() -> Unit)? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(if (back == null) 22 else 8), dp(12), dp(10), dp(10))
    if (back != null) addView(appIcon(android.R.drawable.ic_menu_revert, "返回", back))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("AlwaySide", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(label("留在当下，随手问一句。", 11f, true).apply { setPadding(0, dp(3), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (back != null) marginStart = dp(6) })
    if (assistant != null) addView(appIcon(android.R.drawable.ic_menu_more, "悬浮助手与采集控制", assistant))
}

internal fun LinearLayout.addHistoryTabs(activity: Activity) {
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(22), 0, activity.dp(22), 0)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(activity.label("对话记录", 13f).apply {
                gravity = Gravity.CENTER; setTextColor(ACCENT); minHeight = activity.dp(44)
                accessible("对话记录，当前页面")
            })
            addView(View(activity).apply { setBackgroundColor(ACCENT) }, LinearLayout.LayoutParams(-1, activity.dp(2)))
        }, LinearLayout.LayoutParams(activity.dp(56), -2))
        addView(activity.label("设置", 13f, true).apply {
            gravity = Gravity.CENTER; minHeight = activity.dp(44)
            isClickable = true; isFocusable = true; accessible("设置")
            setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(activity.dp(44), -2).apply { marginStart = activity.dp(14) })
    })
    addView(View(activity).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, activity.dp(1)))
}

internal fun conversationDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

internal fun LinearLayout.addConversationList(activity: Activity, archiveShortcut: Boolean = false, refreshed: () -> Unit) {
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
    val count = activity.label("", 11f, true)
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(activity.label("全部对话", 15f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(count)
        addView(activity.appIcon(android.R.drawable.ic_input_add, "新建文字对话") {
            activity.startActivity(Intent(activity, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        if (archiveShortcut) addView(activity.appIcon(android.R.drawable.ic_menu_recent_history, "全部会话记录") {
            activity.startActivity(Intent(activity, HistoryActivity::class.java))
        })
    })
    val values = try { store.list() } catch (_: Exception) {
        addView(activity.label("无法读取会话记录。可以重试，或明确清空损坏的本机记录。", 12f, true))
        action("重试读取") { refreshed() }
        action("清空无法读取的记录") { clear(null) }
        return
    }
    count.text = "${values.size} 个对话"
    if (values.isEmpty()) {
        addView(activity.label("还没有对话。\n从悬浮球截图、听一段，\n或点击上方加号，开始文字对话。", 12f, true).apply {
            gravity = Gravity.CENTER; setLineSpacing(activity.dp(5).toFloat(), 1f)
            setPadding(activity.dp(20), activity.dp(75), activity.dp(20), activity.dp(75))
        }, LinearLayout.LayoutParams(-1, -2))
        return
    }
    values.forEach { value ->
        fun delete() {
            AlertDialog.Builder(activity).setTitle("删除这段会话？")
                .setMessage("将删除会话文字和已保存图片，无法撤销。对应悬浮会话会停止。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    runCatching {
                        ActiveConversationBridge.detach(value.id)
                        ActiveConversationBridge.takeSession(value.id)?.dispose?.invoke()
                        store.delete(value.id); media.deleteImage(value.id)
                    }.onSuccess { refreshed() }.onFailure { failed() }
                }.show()
        }
        val entry = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            setPadding(0, activity.dp(15), 0, activity.dp(15))
            isClickable = true; isFocusable = true
            background = activity.touchFeedback()
            accessible("打开会话：${value.title.ifBlank { "未命名会话" }}；长按删除")
            setOnLongClickListener { delete(); true }
            setOnClickListener {
                activity.startActivity(Intent(activity, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ChatActivity.EXTRA_ID, value.id))
            }
            val kind = when (value.kind) { "audio" -> "音频"; "photo", "screen", "image" -> "截图"; else -> "文字" }
            addView(ImageView(activity).apply {
                setImageDrawable(activity.prototypeIcon(when (kind) {
                    "音频" -> android.R.drawable.ic_btn_speak_now
                    "截图" -> android.R.drawable.ic_menu_camera
                    else -> android.R.drawable.ic_menu_edit
                }))
                imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(17), activity.dp(17)).apply {
                marginEnd = activity.dp(11); topMargin = activity.dp(2)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.label(value.title.ifBlank { "待提问会话" }, 13f).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                val preview = value.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                    .ifBlank { value.messages.lastOrNull()?.text.orEmpty() }.ifBlank { value.evidence }
                    .ifBlank { "等待你输入第一个问题。" }
                addView(activity.label(preview.take(180), 11f, true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(activity.dp(2).toFloat(), 1f); setPadding(0, activity.dp(5), 0, 0)
                })
                addView(activity.label("${conversationDate(value.updatedAt)} · $kind · ${value.status.ifBlank { "已保存" }}", 10f, true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, activity.dp(5), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(activity.prototypeIcon(android.R.drawable.ic_media_next))
                imageTintList = android.content.res.ColorStateList.valueOf(MUTED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(14), activity.dp(14)).apply {
                marginStart = activity.dp(8); topMargin = activity.dp(2)
            })
        }
        addView(entry, LinearLayout.LayoutParams(-1, -2))
        addView(View(activity).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, activity.dp(1)))
    }
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.END
        setPadding(0, activity.dp(12), 0, 0)
        addView(activity.appIcon(android.R.drawable.ic_menu_delete, "清空全部会话") { clear(values) })
    })
}
