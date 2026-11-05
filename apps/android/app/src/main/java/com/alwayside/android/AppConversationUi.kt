package com.alwayside.android

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date

internal fun Context.appIcon(resource: Int, description: String, action: () -> Unit) =
    iconButton(resource, description, action)

internal fun conversationDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

/** Fixed selection actions with a separately scrolling archive. */
internal class HistoryListScreen(
    private val activity: Activity,
    private val onOpen: (String?) -> Unit,
    private val onSettings: () -> Unit,
    private val onToggleAssistant: () -> Unit,
    private val onSelectionChanged: () -> Unit,
    initialSelection: List<String> = emptyList(),
    private val onIntro: (LinearLayout) -> Unit = {},
) {
    private val store = HistoryStore(activity)
    private val media = ConversationMediaStore(activity)
    private val selection = HistorySelection(initialSelection)
    private var values = emptyList<StoredConversation>()
    private var readFailed = false
    private var pendingScrollY: Int? = null
    val toolbar:MaterialToolbar=activity.materialToolbar("全部对话")
    private val rows = activity.column().apply { setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24)) }
    val scrollView = android.widget.ScrollView(activity).apply {
        isFillViewport = true
        addView(rows)
    }
    val selecting: Boolean get() = selection.selecting

    init {
        refresh()
    }

    fun selectedIds(): List<String> = selection.snapshot()

    fun updateAssistantAction() {
        val running = ProbeState.serviceRunning
        val label = if (running) "关闭悬浮球" else "打开悬浮球"
        toolbar.menu.findItem(5)?.apply {
            title = label; contentDescription = label; tooltipText = label
            iconTintList = android.content.res.ColorStateList.valueOf(if (running) ACCENT else MUTED)
        }
    }

    fun restoreScroll(y: Int) {
        pendingScrollY = y
        scrollView.post {
            scrollView.scrollTo(0, y)
            pendingScrollY = null
        }
    }

    fun back(): Boolean {
        if (!selection.cancel()) return false
        render(); onSelectionChanged()
        return true
    }

    fun refresh() {
        val wasSelecting = selecting
        try {
            values = store.list()
            readFailed = false
            selection.retain(values.map { it.id })
        } catch (_: Exception) {
            values = emptyList()
            readFailed = true
            selection.cancel()
        }
        render()
        if (wasSelecting != selecting) onSelectionChanged()
    }

    private fun changed() { render(); onSelectionChanged() }
    private fun failed() = Toast.makeText(activity, "操作未全部完成，请重试并检查本机存储", Toast.LENGTH_LONG).show()

    private fun confirmDeletion() {
        val deletion = HistoryDeletion(selection.snapshot())
        if (deletion.ids.isEmpty()) return
        MaterialAlertDialogBuilder(activity).setTitle("删除选中的 ${deletion.ids.size} 段会话？")
            .setMessage("将删除这些会话的文字、已保存图片和音频，无法撤销。对应悬浮会话也会停止。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                val failedIds = deletion.execute { id ->
                    ActiveConversationBridge.detach(id)
                    ActiveConversationBridge.takeSession(id)?.dispose?.invoke()
                    store.delete(id)
                }
                refresh()
                if (failedIds.isNotEmpty()) failed()
            }.show()
    }

    private fun confirmCorruptCleanup() {
        MaterialAlertDialogBuilder(activity).setTitle("清空无法读取的本机记录？")
            .setMessage("将删除全部本机会话文字、已保存图片和音频，无法撤销。")
            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                runCatching { store.clear(); media.clear() }
                    .onSuccess { refresh() }.onFailure { failed() }
            }.show()
    }

    private fun render() {
        val targetY = pendingScrollY ?: scrollView.scrollY
        toolbar.menu.clear();rows.removeAllViews()
        toolbar.title=if(selecting)"已选择 ${selection.snapshot().size} 项" else "全部对话"
        toolbar.subtitle=if(selecting)null else "${values.size} 个对话"
        if (selecting) {
            toolbar.addIconAction(1,android.R.drawable.ic_menu_delete,"删除选中的会话",::confirmDeletion).isEnabled=selection.snapshot().isNotEmpty()
            toolbar.addIconAction(2,android.R.drawable.ic_menu_close_clear_cancel,"取消选择") { back() }
        } else {
            toolbar.addIconAction(3,android.R.drawable.ic_input_add,"新建文字对话") { onOpen(null) }
            toolbar.addIconAction(5,R.drawable.ic_line_power,"打开悬浮球",onToggleAssistant)
            updateAssistantAction()
            toolbar.addIconAction(4,android.R.drawable.ic_menu_manage,"设置",onSettings)
        }
        if (!selecting) onIntro(rows)
        if (readFailed) {
            rows.addView(activity.label("无法读取会话记录。可以重试，或明确清空损坏的本机记录。", 12f, true))
            rows.action("重试读取") { refresh() }
            rows.action("清空无法读取的记录") { confirmCorruptCleanup() }
        } else if (values.isEmpty()) {
            rows.addView(activity.label("还没有对话。\n从悬浮球截图、听一段，\n或点击上方加号，开始文字对话。", 12f, true).apply {
                gravity = Gravity.CENTER; setLineSpacing(activity.dp(5).toFloat(), 1f)
                setPadding(activity.dp(20), activity.dp(75), activity.dp(20), activity.dp(75))
            }, LinearLayout.LayoutParams(-1, -2))
        } else values.forEach { value -> addRow(value) }
        scrollView.post { scrollView.scrollTo(0, targetY) }
    }

    private fun addRow(value: StoredConversation) {
        val checked = selection.contains(value.id)
        val title = value.title.ifBlank { "待提问会话" }
        val kind = when (value.kind) { "audio" -> "音频"; "photo", "screen", "image" -> "截图"; else -> "文字" }
        val card = com.google.android.material.card.MaterialCardView(activity).apply {
            radius = activity.dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(if (checked) TONAL_SURFACE else BACKGROUND)
            strokeWidth = 0
            isCheckable = selecting; isClickable = true; isFocusable = true
            // Clear before checking: otherwise an active icon animation would later dereference null.
            // The visible checkbox below supplies the selection indicator.
            checkedIcon = null
            isChecked = checked
            accessible(if (selecting) "$title，${if (checked) "已选择" else "未选择"}，点按切换选择"
                else "打开会话：$title；长按选择")
            setOnLongClickListener { selection.select(value.id); changed(); true }
            setOnClickListener {
                if (selecting) { selection.toggle(value.id); changed() } else onOpen(value.id)
            }
        }
        val entry = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(12), activity.dp(16), activity.dp(12), activity.dp(16))
        }
        if (selecting) entry.addView(com.google.android.material.checkbox.MaterialCheckBox(activity).apply {
            isChecked = checked; isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        }, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { marginEnd = activity.dp(8) })
        else entry.addView(ImageView(activity).apply {
            setImageDrawable(activity.prototypeIcon(when (kind) {
                "音频" -> android.R.drawable.ic_btn_speak_now
                "截图" -> android.R.drawable.ic_menu_camera
                else -> R.drawable.ic_line_text
            }))
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)).apply {
            marginEnd = activity.dp(16)
        })
        entry.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(activity.label(title, 16f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            val preview = value.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                .ifBlank { value.messages.lastOrNull()?.text.orEmpty() }.ifBlank { value.evidence }
                .ifBlank { "等待你输入第一个问题。" }
            addView(activity.label(preview.take(180), 14f, true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(activity.dp(2).toFloat(), 1f); setPadding(0, activity.dp(5), 0, 0)
            })
            addView(activity.label("${conversationDate(value.updatedAt)} · $kind · ${value.status.ifBlank { "已保存" }}", 12f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, activity.dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!selecting) entry.addView(ImageView(activity).apply {
            setImageDrawable(activity.prototypeIcon(android.R.drawable.ic_media_next))
            imageTintList = android.content.res.ColorStateList.valueOf(MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(14), activity.dp(14)).apply {
            marginStart = activity.dp(8); topMargin = activity.dp(2)
        })
        card.addView(entry)
        rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
    }
}
