package com.alwayside.android

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    val browse: HistoryBrowse = HistoryBrowse(),
) {
    private val store = HistoryStore(activity)
    private val media = ConversationMediaStore(activity)
    private val selection = HistorySelection(initialSelection)
    private var values = emptyList<StoredConversation>()
    private var tags = emptyList<String>()
    private var readFailed = false
    private var pendingScrollY: Int? = null
    private var drawerLayout: DrawerLayout? = null
    private val drawerRows = activity.column().apply { setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(12)) }
    private val tagDialogs = CollectionTagDialogs(activity, ::refresh)
    val toolbar:MaterialToolbar=activity.materialToolbar("全部对话")
    private val rows = activity.column().apply { setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24)) }
    val scrollView = android.widget.ScrollView(activity).apply {
        isFillViewport = true
        addView(rows)
    }
    val selecting: Boolean get() = selection.selecting
    val canGoBack: Boolean get() = selecting || browse.canGoBack || drawerLayout?.isDrawerOpen(GravityCompat.START) == true

    fun wrap(content: View): View = DrawerLayout(activity).apply {
        addView(content, DrawerLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND)
            addView(activity.label("AlwaySide", 20f).apply {
                setPadding(activity.dp(28), activity.dp(30), activity.dp(24), activity.dp(24))
            })
            addView(ScrollView(activity).apply { addView(drawerRows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(panel, DrawerLayout.LayoutParams(minOf(activity.dp(320), (activity.resources.displayMetrics.widthPixels * .86f).toInt()), -1, GravityCompat.START))
        setDrawerTitle(GravityCompat.START, "对话与收藏")
        addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { onSelectionChanged() }
            override fun onDrawerClosed(drawerView: View) { onSelectionChanged() }
        })
        drawerLayout = this
        renderDrawer()
    }

    fun prepareToLeave() {
        drawerLayout?.closeDrawer(GravityCompat.START)
        if (selection.cancel()) { render(); onSelectionChanged() }
    }

    init {
        refresh()
    }

    fun selectedIds(): List<String> = selection.snapshot()

    fun updateAssistantAction() {
        val running = ProbeState.serviceRunning
        val label = if (running) "关闭悬浮球" else "打开悬浮球"
        toolbar.menu.findItem(5)?.apply {
            title = label; contentDescription = label; tooltipText = label
            (actionView as? MaterialButton)?.apply {
                accessible(label)
                isSelected = running
                backgroundTintList = ColorStateList.valueOf(if (running) ACCENT else Color.TRANSPARENT)
                iconTint = ColorStateList.valueOf(if (running) ON_ACCENT else INK)
                ViewCompat.setStateDescription(this, if (running) "已开启" else "已关闭")
            }
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
        if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
            drawerLayout?.closeDrawer(GravityCompat.START); return true
        }
        if (selection.cancel() || browse.back()) {
            scrollView.scrollTo(0, 0); render(); onSelectionChanged(); return true
        }
        return false
    }

    fun refresh() {
        val wasSelecting = selecting
        try {
            values = store.list()
            tags = store.tags()
            if (browse.tag != null && browse.tag !in tags) browse.showFavorites()
            readFailed = false
            selection.retain(browse.visible(values).map { it.id })
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
        val favorites = values.count { it.id in deletion.ids && it.isFavorite }
        MaterialAlertDialogBuilder(activity).setTitle("删除选中的 ${deletion.ids.size} 段会话？")
            .setMessage((if (favorites > 0) "包含 $favorites 段已收藏会话。\n" else "") +
                "将删除这些会话的文字、已保存图片和音频，无法撤销。对应悬浮会话也会停止。")
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
        pendingScrollY = null
        val visible = browse.visible(values)
        toolbar.menu.clear();rows.removeAllViews()
        toolbar.title=if(selecting)"已选择 ${selection.snapshot().size} 项" else when {
            browse.tag != null -> browse.tag
            browse.untagged -> "未标记"
            browse.favorites -> "我的收藏"
            else -> "全部对话"
        }
        toolbar.subtitle=if(selecting)null else "${visible.size} 个对话"
        toolbar.navigationIcon = activity.prototypeIcon(if (selecting) R.drawable.ic_line_back else R.drawable.ic_line_menu)
        toolbar.setNavigationIconTint(INK)
        toolbar.navigationContentDescription = if (selecting) "取消选择" else "打开对话与收藏导航"
        toolbar.setNavigationOnClickListener { if (selecting) back() else drawerLayout?.openDrawer(GravityCompat.START) }
        if (selecting) {
            toolbar.addIconAction(1,android.R.drawable.ic_menu_delete,"删除选中的会话",::confirmDeletion).isEnabled=selection.snapshot().isNotEmpty()
            val selected = values.filter { selection.contains(it.id) }
            val allFavorites = selected.isNotEmpty() && selected.all { it.isFavorite }
            toolbar.addIconAction(6, if (allFavorites) R.drawable.ic_bookmark_filled else R.drawable.ic_line_bookmark,
                if (allFavorites) "取消选中会话的收藏" else "收藏选中的会话") {
                val ids = selection.snapshot()
                val failures = ids.filter { runCatching { store.setFavorite(it, !allFavorites) }.isFailure }
                selection.cancel(); refresh(); onSelectionChanged()
                if (failures.isNotEmpty()) failed()
            }
            if (allFavorites) toolbar.addIconAction(7, R.drawable.ic_line_tag, "设置会话标签") {
                tagDialogs.editTags(selection.snapshot())
            }
            toolbar.addIconAction(2,android.R.drawable.ic_menu_close_clear_cancel,"取消选择") { back() }
        } else {
            toolbar.addIconAction(3,android.R.drawable.ic_input_add,"新建文字对话") { onOpen(null) }
            toolbar.addIconAction(5,R.drawable.ic_line_power,"打开悬浮球",onToggleAssistant).apply {
                actionView = activity.iconButton(R.drawable.ic_line_power, "打开悬浮球", onToggleAssistant).apply {
                    isCheckable = false
                    cornerRadius = activity.dp(20)
                    insetTop = activity.dp(4); insetBottom = activity.dp(4)
                    insetLeft = activity.dp(4); insetRight = activity.dp(4)
                }
            }
            updateAssistantAction()
            if (browse.favorites) toolbar.addIconAction(8, R.drawable.ic_line_search, "搜索收藏", ::searchFavorites)
            toolbar.addIconAction(4, R.drawable.ic_line_settings, "设置", onSettings)
        }
        if (!selecting && !browse.favorites) onIntro(rows)
        if (browse.query.isNotBlank()) rows.addView(activity.settingsListRow("搜索：${browse.query}", showArrow = false) {
            searchFavorites()
        })
        if (readFailed) {
            rows.addView(activity.label("无法读取会话记录。可以重试，或明确清空损坏的本机记录。", 12f, true))
            rows.action("重试读取") { refresh() }
            rows.action("清空无法读取的记录") { confirmCorruptCleanup() }
        } else if (visible.isEmpty()) {
            val empty = when {
                browse.query.isNotBlank() -> "没有找到匹配的收藏。"
                browse.tag != null || browse.untagged -> "这里还没有收藏会话。"
                browse.favorites -> "还没有收藏。\n在对话中点击五角星即可收藏。"
                else -> "还没有对话。\n从悬浮球截图、听一段，\n或点击上方加号，开始文字对话。"
            }
            rows.addView(activity.label(empty, 12f, true).apply {
                gravity = Gravity.CENTER; setLineSpacing(activity.dp(5).toFloat(), 1f)
                setPadding(activity.dp(20), activity.dp(75), activity.dp(20), activity.dp(75))
            }, LinearLayout.LayoutParams(-1, -2))
        } else visible.forEach { value -> addRow(value) }
        renderDrawer()
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
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(activity.label(title, 16f).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (value.isFavorite) addView(ImageView(activity).apply {
                    setImageDrawable(activity.prototypeIcon(R.drawable.ic_bookmark_filled))
                    imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    contentDescription = "已收藏"
                }, LinearLayout.LayoutParams(activity.dp(14), activity.dp(14)).apply { marginStart = activity.dp(6) })
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
            if (browse.favorites && value.tags.isNotEmpty()) addView(activity.label(value.tags.joinToString(" · "), 12f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(ACCENT)
                setPadding(0, activity.dp(5), 0, 0)
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

    private fun navigate(favorites: Boolean, tag: String? = null, untagged: Boolean = false) {
        selection.cancel()
        if (favorites) browse.showFavorites(tag, untagged) else browse.showAll()
        drawerLayout?.closeDrawer(GravityCompat.START)
        pendingScrollY = 0; scrollView.scrollTo(0, 0); render(); onSelectionChanged()
    }

    private fun renderDrawer() {
        drawerRows.removeAllViews()
        fun entry(title: String, resource: Int, count: Int, selected: Boolean, action: () -> Unit) {
            drawerRows.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = activity.dp(52)
                setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
                background = if (selected) card(TONAL_SURFACE, activity.dp(14).toFloat()) else activity.touchFeedback()
                isClickable = true; isFocusable = true; accessible("$title，$count 个对话")
                setOnClickListener { action() }
                addView(ImageView(activity).apply {
                    setImageDrawable(activity.prototypeIcon(resource))
                    imageTintList = android.content.res.ColorStateList.valueOf(if (selected) ACCENT else MUTED)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)).apply { marginEnd = activity.dp(16) })
                addView(activity.label(title, 14f).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    if (selected) setTextColor(ACCENT)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(activity.label(count.toString(), 12f, true))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(3) })
        }
        entry("全部对话", R.drawable.ic_line_history, values.size, !browse.favorites) { navigate(false) }
        val favorites = values.filter { it.isFavorite }
        entry("我的收藏", R.drawable.ic_line_bookmark, favorites.size,
            browse.favorites && browse.tag == null && !browse.untagged) { navigate(true) }
        drawerRows.addView(activity.settingsSectionHeader("收藏标签", addDescription = "新建标签", onAdd = tagDialogs::createTag).apply {
            setPadding(activity.dp(16), activity.dp(16), 0, activity.dp(4))
            addView(activity.iconButton(R.drawable.ic_line_more, "管理标签", tagDialogs::manageTags))
        })
        entry("未标记", R.drawable.ic_line_tag, favorites.count { it.tags.isEmpty() }, browse.untagged) { navigate(true, untagged = true) }
        tags.forEach { tag ->
            entry(tag, R.drawable.ic_line_tag, favorites.count { tag in it.tags }, browse.tag == tag) { navigate(true, tag) }
        }
    }

    private fun searchFavorites() {
        val layout = TextInputLayout(activity).apply {
            hint = "标题、正文或标签"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(layout.context).apply { setSingleLine(); setText(browse.query) }
        layout.addView(input)
        MaterialAlertDialogBuilder(activity).setTitle("搜索收藏")
            .setView(activity.column().apply { addView(layout) })
            .setNegativeButton("取消", null).setNeutralButton("清除") { _, _ ->
                browse.query = ""; selection.cancel(); pendingScrollY = 0; render(); onSelectionChanged()
            }.setPositiveButton("搜索") { _, _ ->
                browse.query = input.text.toString().trim(); selection.cancel(); pendingScrollY = 0
                render(); onSelectionChanged()
            }.show()
    }
}
