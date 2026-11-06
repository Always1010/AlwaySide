package com.alwayside.android

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    initialSelecting: Boolean = initialSelection.isNotEmpty(),
    private val onIntro: (LinearLayout) -> Unit = {},
    val browse: HistoryBrowse = HistoryBrowse(),
) {
    private val store = HistoryStore(activity)
    private val media = ConversationMediaStore(activity)
    private val selection = HistorySelection(initialSelection, initialSelecting)
    private var values = emptyList<StoredConversation>()
    private var tags = emptyList<String>()
    private var readFailed = false
    private var pendingScrollY: Int? = null
    private var drawerLayout: DrawerLayout? = null
    private val drawerRows = activity.column().apply { setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(12)) }
    private val tagDialogs = CollectionTagDialogs(activity, ::refresh)
    val toolbar:MaterialToolbar=activity.materialToolbar("全部对话")
    private val selectAll = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        applyActionStyle(false)
        minimumWidth = 0; minWidth = 0
        setOnClickListener { selection.toggleAll(browse.visible(values).map { it.id }); changed() }
    }
    private val selectionCount = activity.label("", 18f).apply { gravity = Gravity.CENTER }
    private val selectionHeader = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(selectAll, LinearLayout.LayoutParams(-2, activity.dp(48)))
        addView(selectionCount, LinearLayout.LayoutParams(0, -1, 1f))
    }
    val selectionActions = LinearLayout(activity).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(SURFACE)
        setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8))
        visibility = View.GONE
    }
    private val rows = activity.column().apply { setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24)) }
    val scrollView = android.widget.ScrollView(activity).apply {
        isFillViewport = true
        addView(rows)
    }
    private val searchField = TextInputLayout(activity).apply {
        isHintEnabled = false
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        boxBackgroundColor = SURFACE
        boxStrokeWidth = 0; boxStrokeWidthFocused = 0
        val radius = activity.dp(26).toFloat()
        setBoxCornerRadii(radius, radius, radius, radius)
        setStartIconDrawable(R.drawable.ic_line_search)
        setStartIconTintList(ColorStateList.valueOf(MUTED))
        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        setEndIconContentDescription("清空搜索")
        setEndIconTintList(ColorStateList.valueOf(MUTED))
    }
    private val searchInput = TextInputEditText(searchField.context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(INK); setHintTextColor(MUTED)
        setSingleLine(); minHeight = activity.dp(52)
        setPadding(activity.dp(48), activity.dp(12), activity.dp(48), activity.dp(12))
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideSearchKeyboard(); true } else false
        }
    }
    private val dateFilter = activity.iconButton(R.drawable.ic_line_sliders, "按时间筛选", ::openDateFilter).apply {
        cornerRadius = activity.dp(24)
        setToggleCheckedStateOnClick(false)
    }
    private val dateFilterIndicator = View(activity).apply {
        background = card(ACCENT, activity.dp(3).toFloat())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val searchView = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(8))
        }
        addView(searchField, LinearLayout.LayoutParams(0, -2, 1f))
        addView(FrameLayout(activity).apply {
            addView(dateFilter, FrameLayout.LayoutParams(-1, -1))
            addView(dateFilterIndicator, FrameLayout.LayoutParams(activity.dp(6), activity.dp(6), Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(8); marginEnd = activity.dp(8)
            })
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply { marginStart = activity.dp(8) })
    }
    private val newConversation = FloatingActionButton(activity).apply {
        setImageDrawable(activity.prototypeIcon(R.drawable.ic_line_plus))
        customSize = activity.dp(56)
        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(activity.dp(28).toFloat()).build()
        backgroundTintList = ColorStateList.valueOf(ACCENT)
        imageTintList = ColorStateList.valueOf(ON_ACCENT)
        compatElevation = activity.dp(4).toFloat()
        accessible("新建文字对话")
        setOnClickListener { onOpen(null) }
    }
    val selecting: Boolean get() = selection.selecting
    val canGoBack: Boolean get() = selecting || browse.canGoBack || drawerLayout?.isDrawerOpen(GravityCompat.START) == true

    fun wrap(content: View): View = DrawerLayout(activity).apply {
        addView(FrameLayout(activity).apply {
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(newConversation, FrameLayout.LayoutParams(activity.dp(56), activity.dp(56), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = activity.dp(28); bottomMargin = activity.dp(88)
            })
        }, DrawerLayout.LayoutParams(-1, -1))
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
        searchField.addView(searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (query == browse.query) return
                browse.query = query
                selection.cancel(); pendingScrollY = 0
                render(); onSelectionChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    private fun hideSearchKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun openDateFilter() {
        searchInput.clearFocus(); hideSearchKeyboard()
        activity.showHistoryDateFilter(browse.dateRange) { range ->
            browse.dateRange = range
            selection.cancel(); pendingScrollY = 0
            render(); onSelectionChanged()
        }
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
        if (selection.cancel()) { changed(); return true }
        if (browse.back()) {
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
                if (failedIds.isEmpty()) selection.cancel()
                refresh(); onSelectionChanged()
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
        newConversation.visibility = if (selecting) View.GONE else View.VISIBLE
        rows.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(if (selecting) 24 else 168))
        searchView.visibility = if (selecting) View.GONE else View.VISIBLE
        val range = browse.dateRange
        dateFilter.isSelected = range != null
        dateFilter.backgroundTintList = ColorStateList.valueOf(if (range != null) TONAL_SURFACE else Color.TRANSPARENT)
        dateFilter.iconTint = ColorStateList.valueOf(if (range != null) ACCENT else MUTED)
        dateFilter.accessible(if (range != null) "时间筛选：${range.displayLabel()}，点击修改" else "按时间筛选")
        ViewCompat.setStateDescription(dateFilter, if (range != null) "已设置时间范围" else "未筛选")
        dateFilterIndicator.visibility = if (range != null) View.VISIBLE else View.GONE
        searchInput.hint = when {
            browse.tag != null -> "搜索“${browse.tag}”标签内的对话"
            browse.untagged -> "搜索未标记的收藏"
            browse.favorites -> "搜索我的收藏"
            else -> "搜索全部对话"
        }
        searchInput.contentDescription = "${searchInput.hint}，标题、正文或标签"
        if (searchInput.text.toString() != browse.query) {
            searchInput.setText(browse.query)
            searchInput.setSelection(searchInput.length())
        }
        if (selecting) { searchInput.clearFocus(); hideSearchKeyboard() }
        toolbar.menu.clear();rows.removeAllViews()
        drawerLayout?.setDrawerLockMode(if (selecting) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
        toolbar.title=if(selecting)null else when {
            browse.tag != null -> browse.tag
            browse.untagged -> "未标记"
            browse.favorites -> "我的收藏"
            else -> "全部对话"
        }
        toolbar.subtitle=if(selecting)null else "${visible.size} 个对话"
        toolbar.navigationIcon = if (selecting) null else activity.prototypeIcon(R.drawable.ic_line_menu)
        toolbar.setNavigationIconTint(INK)
        toolbar.navigationContentDescription = "打开对话与收藏导航"
        toolbar.setNavigationOnClickListener { drawerLayout?.openDrawer(GravityCompat.START) }
        if (selectionHeader.parent == null) toolbar.addView(selectionHeader, androidx.appcompat.widget.Toolbar.LayoutParams(-1, -1, Gravity.START))
        selectionHeader.visibility = if (selecting) View.VISIBLE else View.GONE
        selectionCount.text = "已选 ${selection.snapshot().size} 项"
        selectAll.text = if (selection.allSelected(visible.map { it.id })) "取消全选" else "全选"
        selectAll.accessible(selectAll.text.toString())
        selectAll.isEnabled = visible.isNotEmpty()
        selectionActions.removeAllViews()
        selectionActions.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            val selected = values.filter { selection.contains(it.id) }
            val allFavorites = selected.isNotEmpty() && selected.all { it.isFavorite }
            fun action(icon: Int, label: String, enabled: Boolean = selected.isNotEmpty(), run: () -> Unit) {
                selectionActions.addView(activity.iconButton(icon, label, run).apply { isEnabled = enabled },
                    LinearLayout.LayoutParams(0, activity.dp(48), 1f))
            }
            action(if (allFavorites) R.drawable.ic_bookmark_filled else R.drawable.ic_line_bookmark,
                if (allFavorites) "取消选中会话的收藏" else "收藏选中的会话") {
                val ids = selection.snapshot()
                val failures = ids.filter { runCatching { store.setFavorite(it, !allFavorites) }.isFailure }
                if (failures.isEmpty()) selection.cancel()
                refresh(); onSelectionChanged()
                if (failures.isNotEmpty()) failed() else if (allFavorites) Toast.makeText(activity,
                    "已取消收藏，重新加入普通历史保留队列", Toast.LENGTH_SHORT).show()
            }
            action(R.drawable.ic_line_tag, if (allFavorites) "设置会话标签" else "收藏并设置标签") {
                tagDialogs.editTags(selection.snapshot(), collectMissing = !allFavorites) {
                    selection.cancel(); onSelectionChanged()
                }
            }
            action(R.drawable.ic_line_trash, "删除选中的会话", run = ::confirmDeletion)
            toolbar.addIconAction(2,android.R.drawable.ic_menu_close_clear_cancel,"取消选择") { back() }
        } else {
            toolbar.addIconAction(5,R.drawable.ic_line_power,"打开悬浮球",onToggleAssistant).apply {
                actionView = activity.iconButton(R.drawable.ic_line_power, "打开悬浮球", onToggleAssistant).apply {
                    isCheckable = false
                    cornerRadius = activity.dp(20)
                    insetTop = activity.dp(4); insetBottom = activity.dp(4)
                    insetLeft = activity.dp(4); insetRight = activity.dp(4)
                }
            }
            updateAssistantAction()
            toolbar.addIconAction(4, R.drawable.ic_line_settings, "设置", onSettings)
        }
        if (!selecting && !browse.favorites) onIntro(rows)
        if (readFailed) {
            rows.addView(activity.label("无法读取会话记录。可以重试，或明确清空损坏的本机记录。", 12f, true))
            rows.action("重试读取") { refresh() }
            rows.action("清空无法读取的记录") { confirmCorruptCleanup() }
        } else if (visible.isEmpty()) {
            val empty = when {
                browse.query.isNotBlank() || browse.dateRange != null -> "没有找到匹配的对话。"
                browse.tag != null || browse.untagged -> "这里还没有收藏会话。"
                browse.favorites -> "还没有收藏。\n在对话中点击五角星即可收藏。"
                else -> "还没有对话。\n从悬浮球截图、听一段，\n或点击右下方加号，开始文字对话。"
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
        HistoryRowTouch(card)
        rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
    }

    private fun navigate(favorites: Boolean, tag: String? = null, untagged: Boolean = false) {
        searchInput.clearFocus(); hideSearchKeyboard()
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

}
