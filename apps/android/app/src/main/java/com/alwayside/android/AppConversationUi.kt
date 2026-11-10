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
import android.view.MotionEvent
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
    private val playback = ChatAudioPlayback.get(activity)
    private data class AudioControls(val title: String, val button: MaterialButton, val icon: AudioPlaybackIcon)
    private val playbackButtons = mutableMapOf<String, AudioControls>()
    private var playbackForeground = true
    private val playbackObserver: () -> Unit = { updatePlaybackButtons() }
    private val selection = HistorySelection(initialSelection, initialSelecting)
    private var values = emptyList<StoredConversation>()
    private var visibleValues = emptyList<StoredConversation>()
    private var playableValues = emptyList<AudioQueueItem>()
    private var loading = false
    private var disposed = false
    @Volatile private var requestVersion = 0
    private var pendingRead: Future<*>? = null
    private var tags = emptyList<String>()
    private var readFailed = false
    private var pendingScrollY: Int? = null
    private var drawerLayout: DrawerLayout? = null
    private var openedSwipe: HistorySwipeRow? = null
    private var dismissedSwipeOnTouch = false
    private val drawerRows = activity.column().apply { setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(12)) }
    private val tagDialogs = CollectionTagDialogs(activity, ::refresh)
    val toolbar:MaterialToolbar=activity.materialToolbar("全部对话")
    private val selectAll = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        applyActionStyle(false)
        minimumWidth = 0; minWidth = 0
        setOnClickListener { selection.toggleAll(visibleValues.map { it.id }); changed() }
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
    val canGoBack: Boolean get() = openedSwipe != null || selecting || browse.canGoBack || drawerLayout?.isDrawerOpen(GravityCompat.START) == true

    fun wrap(content: View): View = object : DrawerLayout(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dismissedSwipeOnTouch = false
                openedSwipe?.let { if (!it.contains(event.rawX, event.rawY)) dismissedSwipeOnTouch = closeSwipe() }
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dismissedSwipeOnTouch = false
            return handled
        }
    }.apply {
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
        setDrawerLockMode(if (selecting) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
        renderDrawer()
    }

    fun prepareToLeave() {
        closeSwipe()
        cancelRead()
        drawerLayout?.closeDrawer(GravityCompat.START)
        if (selection.cancel()) { render(); onSelectionChanged() }
    }

    fun dispose() {
        disposed = true
        cancelRead()
        playback.removeObserver(playbackObserver)
        playbackButtons.clear()
        playback.setLibraryVisible(this, false, retaining = activity.isChangingConfigurations)
    }

    fun setPlaybackForeground(foreground: Boolean) {
        playbackForeground = foreground
        playback.setLibraryVisible(this, foreground && scrollView.isAttachedToWindow && !disposed)
    }

    private fun cancelRead() {
        requestVersion++; pendingRead?.cancel(false); pendingRead = null
    }

    init {
        scrollView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                setPlaybackForeground(playbackForeground)
                playback.addObserver(playbackObserver)
            }
            override fun onViewDetachedFromWindow(view: View) {
                playback.removeObserver(playbackObserver)
                playback.setLibraryVisible(this@HistoryListScreen, false, retaining = activity.isChangingConfigurations)
            }
        })
        scrollView.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, oldY: Int ->
            if (y != oldY) closeSwipe()
        }
        searchField.addView(searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (query == browse.query) return
                browse.query = query
                selection.cancel(); pendingScrollY = 0; browse.page = 0
                visibleValues = emptyList()
                requestRefresh(250); onSelectionChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        scrollView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                cancelRead()
            }
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
            selection.cancel(); pendingScrollY = 0; browse.page = 0
            visibleValues = emptyList()
            refresh(); onSelectionChanged()
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
        if (!loading) render()
    }

    fun back(): Boolean {
        if (closeSwipe()) return true
        if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
            drawerLayout?.closeDrawer(GravityCompat.START); return true
        }
        if (selection.cancel()) { changed(); return true }
        if (browse.back()) {
            pendingScrollY = 0; visibleValues = emptyList()
            refresh(); onSelectionChanged(); return true
        }
        return false
    }

    fun refresh() { requestRefresh(0) }

    private fun requestRefresh(delayMillis: Long) {
        if (disposed || activity.isDestroyed || activity.isFinishing) return
        val version = ++requestVersion
        pendingRead?.cancel(false)
        loading = true; readFailed = false
        playableValues = emptyList()
        val requested = HistoryBrowse(browse.favorites, browse.tag, browse.untagged, browse.query, browse.dateRange)
        render()
        pendingRead = reader.schedule({
            val result = runCatching {
                val state = store.index()
                if (requested.tag != null && requested.tag !in state.tags) requested.showFavorites()
                val found = HistorySearch.run(requested, state.conversations, store::find) { requestVersion != version }
                val playable = AudioPlaybackQueue.recordings(found) { id ->
                    requestVersion == version && runCatching { media.loadAudio(id) != null }.getOrDefault(false)
                }
                Triple(state, found, playable)
            }
            activity.runOnUiThread {
                if (disposed || activity.isDestroyed || activity.isFinishing || requestVersion != version) return@runOnUiThread
                val wasSelecting = selecting
                loading = false
                result.onSuccess { (state, found, playable) ->
                    values = state.conversations; tags = state.tags; visibleValues = found
                    playableValues = playable
                    if (browse.tag != null && browse.tag !in tags) browse.showFavorites()
                    selection.retain(found.map { it.id })
                    browse.page = browse.page.coerceIn(0, ((found.size - 1).coerceAtLeast(0) / PAGE_SIZE))
                    readFailed = false
                }.onFailure {
                    values = emptyList(); visibleValues = emptyList()
                    readFailed = true; selection.cancel()
                }
                render()
                if (wasSelecting != selecting) onSelectionChanged()
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun changed() { render(); onSelectionChanged() }
    private fun closeSwipe(): Boolean {
        val row = openedSwipe ?: return false
        openedSwipe = null
        row.close(); onSelectionChanged()
        return true
    }

    private fun favoriteConversation(value: StoredConversation) {
        closeSwipe()
        runCatching { store.setFavorite(value.id, !value.isFavorite) }.onSuccess {
            Toast.makeText(activity, if (value.isFavorite) "已取消收藏" else "已收藏", Toast.LENGTH_SHORT).show()
            refresh()
        }.onFailure { failed() }
    }

    private fun moreConversation(value: StoredConversation) {
        closeSwipe()
        val dialog = BottomSheetDialog(activity)
        val body = activity.column()
        body.addView(activity.label("会话操作", 20f))
        fun entry(icon: Int, title: String, run: () -> Unit) {
            body.addView(activity.settingsListRow(title,
                leading = activity.iconButton(icon, title) { dialog.dismiss(); run() }.apply {
                    isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, showArrow = false, onClick = { dialog.dismiss(); run() }))
        }
        entry(R.drawable.ic_line_edit, "重命名") {
            activity.showConversationTitleDialog(value.title) { title ->
                store.rename(value.id, title)
                refresh()
            }
        }
        entry(R.drawable.ic_line_tag, if (value.isFavorite) "设置标签" else "收藏并设置标签") {
            tagDialogs.editTags(listOf(value.id), collectMissing = !value.isFavorite)
        }
        entry(R.drawable.ic_line_check, "选择此会话") { selection.select(value.id); changed() }
        dialog.setContentView(body); dialog.show()
    }
    private fun failed() = Toast.makeText(activity, "操作未全部完成，请重试并检查本机存储", Toast.LENGTH_LONG).show()

    private fun confirmDeletion(ids: List<String> = selection.snapshot()) {
        closeSwipe()
        val deletion = HistoryDeletion(ids)
        if (deletion.ids.isEmpty()) return
        if (deletion.ids.any(ConversationUsage::active)) {
            MaterialAlertDialogBuilder(activity).setTitle("会话正在使用")
                .setMessage("请先关闭对应会话，并结束播放或处理，再删除。")
                .setPositiveButton("知道了", null).show()
            return
        }
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
                runCatching { store.clear() }
                    .onSuccess { refresh() }.onFailure { failed() }
            }.show()
    }

    private fun render() {
        closeSwipe()
        playbackButtons.clear()
        val targetY = pendingScrollY ?: scrollView.scrollY
        if (!loading) pendingScrollY = null
        val visible = visibleValues
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
        toolbar.subtitle=if(selecting)null else if (loading) "正在读取…" else "${visible.size} 个对话"
        toolbar.navigationIcon = if (selecting) null else activity.prototypeIcon(R.drawable.ic_line_menu)
        toolbar.setNavigationIconTint(INK)
        toolbar.navigationContentDescription = "打开对话与收藏导航"
        toolbar.setNavigationOnClickListener { drawerLayout?.openDrawer(GravityCompat.START) }
        if (selectionHeader.parent == null) toolbar.addView(selectionHeader, androidx.appcompat.widget.Toolbar.LayoutParams(-1, -1, Gravity.START))
        selectionHeader.visibility = if (selecting) View.VISIBLE else View.GONE
        selectionCount.text = "已选 ${selection.snapshot().size} 项"
        selectAll.text = if (selection.allSelected(visible.map { it.id })) "取消全选" else "全选"
        selectAll.accessible(selectAll.text.toString())
        selectAll.isEnabled = !loading && visible.isNotEmpty()
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
                    "已取消收藏", Toast.LENGTH_SHORT).show()
            }
            action(R.drawable.ic_line_tag, if (allFavorites) "设置会话标签" else "收藏并设置标签") {
                tagDialogs.editTags(selection.snapshot(), collectMissing = !allFavorites) {
                    selection.cancel(); onSelectionChanged()
                }
            }
            action(R.drawable.ic_line_trash, "删除选中的会话") { confirmDeletion() }
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
                loading -> if (browse.query.isNotBlank()) "正在搜索…" else "正在读取…"
                browse.query.isNotBlank() || browse.dateRange != null -> "没有找到匹配的对话。"
                browse.tag != null || browse.untagged -> "这里还没有收藏会话。"
                browse.favorites -> "还没有收藏。\n在对话中点击五角星即可收藏。"
                else -> "还没有对话。\n从悬浮球截图、听一段，\n或点击右下方加号，开始文字对话。"
            }
            rows.addView(activity.label(empty, 12f, true).apply {
                gravity = Gravity.CENTER; setLineSpacing(activity.dp(5).toFloat(), 1f)
                setPadding(activity.dp(20), activity.dp(75), activity.dp(20), activity.dp(75))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            if (!selecting && browse.favorites && !loading && playableValues.isNotEmpty()) {
                rows.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val prefix = if (browse.query.isNotBlank()) "搜索结果 · " else if (browse.dateRange != null) "筛选结果 · " else ""
                    addView(activity.label("$prefix${playableValues.size} 段可播放录音", 13f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(activity.iconButton(R.drawable.ic_line_playlist_play, "播放此列表的全部录音", {
                        if (!loading && playableValues.isNotEmpty()) {
                            val origin = browse.tag ?: if (browse.untagged) "未标记" else "我的收藏"
                            val source = origin + if (browse.query.isNotBlank()) " · 搜索结果" else if (browse.dateRange != null) " · 筛选结果" else ""
                            playback.playQueue(playableValues, source)
                        }
                    }))
                })
            }
            val first = browse.page * PAGE_SIZE
            visible.subList(first, minOf(first + PAGE_SIZE, visible.size)).forEach(::addRow)
            addPaging(visible.size)
        }
        renderDrawer()
        if (!loading) scrollView.post { scrollView.scrollTo(0, targetY) }
    }

    private fun addPaging(count: Int) {
        if (count <= PAGE_SIZE) return
        val pages = (count - 1) / PAGE_SIZE + 1
        rows.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(16), 0, activity.dp(8))
            fun move(delta: Int) {
                browse.page = (browse.page + delta).coerceIn(0, pages - 1)
                pendingScrollY = 0; render()
            }
            addView(activity.iconButton(android.R.drawable.ic_media_previous, "上一页对话") { move(-1) }
                .apply { isEnabled = !loading && browse.page > 0 })
            addView(activity.label("${browse.page + 1} / $pages", 14f, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "第 ${browse.page + 1} 页，共 $pages 页"
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.iconButton(android.R.drawable.ic_media_next, "下一页对话") { move(1) }
                .apply { isEnabled = !loading && browse.page + 1 < pages })
        }, LinearLayout.LayoutParams(-1, -2))
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
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                closeSwipe(); selection.select(value.id); changed(); true
            }
            setOnClickListener {
                if (selecting) { selection.toggle(value.id); changed() }
                else if (!closeSwipe() && !dismissedSwipeOnTouch) onOpen(value.id)
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
        else if (value.kind == "audio" && (playback.fileFor(value.id) ?: media.loadAudio(value.id)) != null) {
            // The negative start margin places the playback ring inside the row padding.
            entry.clipToPadding = false
            val drawable = AudioPlaybackIcon(activity)
            val button = activity.iconButton(R.drawable.ic_line_play, "播放录音：$title") {
                if (!closeSwipe() && !dismissedSwipeOnTouch) toggleAudio(value.id)
            }.apply {
                icon = drawable; iconSize = activity.dp(32)
                // Fit the enlarged progress icon inside the 48dp touch target.
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                iconTint = ColorStateList.valueOf(ACCENT)
                isCheckable = false
            }
            val controls = AudioControls(title, button, drawable)
            playbackButtons[value.id] = controls
            updatePlaybackButton(value.id, controls)
            entry.addView(button, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                marginStart = -activity.dp(12); marginEnd = activity.dp(4)
            })
        } else entry.addView(ImageView(activity).apply {
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
            addView(activity.label(ConversationPreview.text(preview, title), 14f, true).apply {
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
        val row = if (selecting) card else HistorySwipeRow(activity, card, value.isFavorite,
            onFavorite = { favoriteConversation(value) }, onMore = { moreConversation(value) },
            onDelete = { confirmDeletion(listOf(value.id)) },
            onOpened = { opened ->
                if (openedSwipe !== opened) {
                    closeSwipe(); openedSwipe = opened; onSelectionChanged()
                }
            }, onClosed = { closed ->
                if (openedSwipe === closed) { openedSwipe = null; onSelectionChanged() }
            })
        HistoryRowTouch(card, row as? HistorySwipeRow)
        if (!selecting) {
            ViewCompat.addAccessibilityAction(card, if (value.isFavorite) "取消收藏" else "收藏") { _, _ -> favoriteConversation(value); true }
            ViewCompat.addAccessibilityAction(card, "更多会话操作") { _, _ -> moreConversation(value); true }
            ViewCompat.addAccessibilityAction(card, "删除会话") { _, _ -> confirmDeletion(listOf(value.id)); true }
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
    }

    private fun toggleAudio(id: String) {
        if (playback.isActive(id) && playback.readingKey.isEmpty()) {
            playback.toggle()
            return
        }
        val source = media.loadAudio(id)
        if (source == null) {
            Toast.makeText(activity, "原始录音已不可用", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        playback.play(id, source)
    }

    private fun updatePlaybackButtons() {
        playbackButtons.forEach { (id, controls) -> updatePlaybackButton(id, controls) }
    }

    private fun updatePlaybackButton(id: String, controls: AudioControls) {
        val state = playback.stateFor(id)
        val button = controls.button
        controls.icon.update(state)
        button.isEnabled = !state.preparing
        val title = controls.title
        val description = when {
            state.preparing -> "正在准备录音：$title"
            state.playing -> "暂停录音：$title"
            else -> "播放录音：$title"
        }
        if (button.contentDescription != description) button.accessible(description)
    }

    private fun navigate(favorites: Boolean, tag: String? = null, untagged: Boolean = false) {
        searchInput.clearFocus(); hideSearchKeyboard()
        selection.cancel()
        if (favorites) browse.showFavorites(tag, untagged) else browse.showAll()
        drawerLayout?.closeDrawer(GravityCompat.START)
        pendingScrollY = 0; visibleValues = emptyList(); refresh(); onSelectionChanged()
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

    private companion object {
        const val PAGE_SIZE = 50
        val reader = Executors.newSingleThreadScheduledExecutor()
    }

}
