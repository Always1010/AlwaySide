package com.alwayside.android

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.IdentityHashMap

/** Full-screen in-app conversation. Capture remains exclusively user-started through the assistant. */
internal class ChatScreen(
    private val activity: Activity,
    private val onBack: () -> Unit,
    id: String? = null,
    savedState: Bundle? = null,
    retained: RetainedConversation? = null,
    autoAskText: Boolean = false,
) : ContextWrapper(activity) {
    val view: View
    private val requestedId = savedState?.getString(EXTRA_ID) ?: id
    private lateinit var session: ChatSession
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusView: TextView
    private lateinit var mediaView: TextView
    private lateinit var messagesView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var composer: TextInputEditText
    private lateinit var composerLayout: TextInputLayout
    private lateinit var send: MaterialButton
    private lateinit var retry: MaterialButton
    private lateinit var webSearch: MaterialButton
    private lateinit var reuse: MaterialCheckBox
    private lateinit var contextTools: LinearLayout
    private var webSearchOverride: Boolean? = retained?.webSearchOverride
        ?: savedState?.takeIf { it.containsKey("webSearchOverride") }?.getBoolean("webSearchOverride")
    private class MessageRow(val role: String, val block: LinearLayout, val text: TextView, val notice: TextView) {
        var value: String? = null
        var suggestions = ""
        var suggestionView: SearchSuggestionsView? = null
        fun dispose() { suggestionView?.destroy(); suggestionView = null }
    }
    private data class EvidencePreview(val value: Any, val view: View)
    private val evidencePreviews = linkedMapOf<String, EvidencePreview>()
    private var messageRows = IdentityHashMap<ChatMessage, MessageRow>()
    private var historyRows = emptyList<MessageRow>()
    private var renderedHistorySize = -1
    private var renderedHistoryFirst: ChatMessage? = null
    private var renderedHistoryLast: ChatMessage? = null
    private var renderedConversationId: String? = null
    private var pendingQuestionRow: MessageRow? = null
    private var partialAnswerRow: MessageRow? = null
    private var emptyMessageView: TextView? = null
    private var activeConnection = runCatching { SettingsStore(this).load() }.getOrDefault(AppConfig())
    private var defaultWebSearch = activeConnection.webSearch
    private var image: Bitmap? = null
    private var originalImage: Bitmap? = null
    private var ready = false
    private var closing = false
    private var conversationTitle = "文字对话"
    private var restoredStatus = ""
    private var handoff: HandedOffConversation? = null
    private var returnedToFloating = false
    private var openedStoredRecord = false
    private var favorite = false
    private val collectionStore = HistoryStore(this)
    private val collectionTags = CollectionTagDialogs(activity) { refreshCollectionState() }
    private val playbackIcon = AudioPlaybackIcon(this)
    private val playback = ChatAudioPlayback.get(this)
    private var playbackForeground = true
    private var readingEnabled = false
    private var readingBar: ReadingBar? = null
    private var readingImageDialog: androidx.appcompat.app.AlertDialog? = null
    private var subtitleDialog: androidx.appcompat.app.AlertDialog? = null
    private val subtitles = SubtitleController.get(this)
    private var pendingSubtitleAction = false
    private var pendingSubtitleOverlay = false
    private val subtitleObserver: () -> Unit = {
        if (ready && !closing) {
            updateSubtitleAction()
            if (pendingSubtitleAction && subtitles.record(session.conversationId, SubtitleMethod.WHISPER).loaded) {
                pendingSubtitleAction = false
                updateSubtitleAction()
                activateSubtitles()
            }
        }
    }
    private val subtitlePreferences = SubtitlePreferences(this)
    private val subtitleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (ready && !closing) updateSubtitleAction()
    }
    private val readingPreferences = InteractionPreferences(this)
    private val readingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("chatPointReading", "backgroundPlaybackSubtitles") && ready && !closing) render()
    }
    private val playbackObserver: () -> Unit = {
        if (ready && !closing) {
            updatePlayback(playback.stateFor(session.conversationId))
            if (pendingSubtitleOverlay && !playback.currentState.preparing) view.post { finishSubtitleOverlay() }
        }
    }
    internal data class RetainedConversation(
        val session: ChatSession, val image: Bitmap?, val handoff: HandedOffConversation?,
        val title: String, val status: String, val openedStoredRecord: Boolean,
        val draft: String, val reuseImage: Boolean, val webSearchOverride: Boolean?, val originalImage: Bitmap?,
    )

    init {
        view = buildScreen()
        if (retained != null) {
            session = retained.session; image = retained.image; originalImage = retained.originalImage; handoff = retained.handoff
            conversationTitle = retained.title; restoredStatus = retained.status
            openedStoredRecord = retained.openedStoredRecord
            bindConversation(retained.draft, retained.reuseImage)
        } else {
            openConversation(requestedId, savedState?.getString("draft").orEmpty(), savedState?.getBoolean("reuse", true) ?: true)
            if (savedState == null) maybeAskSelectedText(autoAskText)
        }
    }

    fun matches(id: String?): Boolean = !closing &&
        (if (id == null) requestedId == null else if (ready) id == session.conversationId else id == requestedId)

    private fun openConversation(id: String?, draft: String = "", reuseImage: Boolean = true) {
        val saved = if (id.isNullOrBlank()) null else try {
            // Transfer before reading: the overlay no longer owns or writes this conversation.
            image = ActiveConversationBridge.detach(id)
            handoff = ActiveConversationBridge.takeSession(id)
            if (handoff != null) webSearchOverride = handoff?.webSearchOverride
            image = handoff?.image ?: image
            originalImage = handoff?.originalImage
            (handoff?.session?.snapshot() ?: HistoryStore(this).find(id)).also {
                if (image == null) {
                    runCatching { ConversationMediaStore(this).loadImages(id) }
                        .onSuccess { images -> originalImage = images.first; image = images.second }
                        .onFailure { toast("历史图片暂不可读，仍可查看文字对话") }
                }
            }
        } catch (_: Exception) {
            showUnavailable("无法读取这段会话，请返回列表重试。")
            return
        }
        if (!id.isNullOrBlank() && saved == null) {
            showUnavailable("这段会话已被删除或不再保留。")
            return
        }
        // Only the replaceable observer captures an Activity; retention must not retain an old view tree.
        session = handoff?.session ?: ChatSession(applicationContext) {}
        conversationTitle = saved?.title?.ifBlank { "待提问会话" } ?: "文字对话"
        openedStoredRecord = saved != null
        restoredStatus = if (handoff == null) saved?.status.orEmpty() else ""
        if (handoff == null) session.newConversation(saved)
        bindConversation(handoff?.draft ?: draft, handoff?.reuseImage ?: reuseImage)
    }

    private fun bindConversation(draft: String, reuseImage: Boolean) {
        session.setObserver { if (ready && !closing) render() }
        ready = true
        composer.setText(draft)
        reuse.isChecked = image != null && reuseImage
        send.isEnabled = true
        refreshCollectionState()
        render()
    }

    private fun maybeAskSelectedText(explicitlyRequested: Boolean) {
        if (!explicitlyRequested || !ready || session.sourceText.isBlank() || session.busy ||
            session.messages.isNotEmpty() || session.question.isNotBlank()) return
        try {
            composer.setText(SettingsStore(this).load().textQuestion.ifBlank { "请将选中文字翻译成中文，并按需要解释。" })
            if (InteractionPreferences(this).load().autoAsk("text")) submit()
        } catch (error: Exception) {
            toast(error.message?.take(160) ?: "无法读取默认提问，请输入问题后发送")
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    subtitlePreferences.addListener(subtitleListener)
                    readingPreferences.addListener(readingListener)
                    subtitles.addObserver(subtitleObserver)
                    if (ready && session.kind == "audio") subtitles.refresh(session.conversationId)
                    playback.addObserver(playbackObserver)
                    setPlaybackForeground(playbackForeground)
                    // Reload configuration when the existing chat view is reattached.
                    runCatching { SettingsStore(this@ChatScreen).load() }.getOrNull()?.let { fresh ->
                        if (fresh.connectionId != activeConnection.connectionId) webSearchOverride = null
                        activeConnection = fresh; defaultWebSearch = fresh.webSearch
                    }
                    if (ready) { refreshCollectionState(); render() } else renderWebSearch()
                }
                override fun onViewDetachedFromWindow(view: View) {
                    pendingSubtitleAction = false; pendingSubtitleOverlay = false
                    subtitleDialog?.dismiss(); subtitleDialog = null
                    subtitles.removeObserver(subtitleObserver)
                    subtitlePreferences.removeListener(subtitleListener)
                    readingPreferences.removeListener(readingListener)
                    playback.removeObserver(playbackObserver)
                    playback.setVisible(this@ChatScreen, null, false, retaining = activity.isChangingConfigurations)
                }
            })
        }
        toolbar = materialToolbar("对话", onBack).apply {
            overflowIcon = prototypeIcon(R.drawable.ic_line_more).apply { setTint(INK) }
            addIconAction(4, R.drawable.ic_line_star, "收藏会话") { toggleFavorite() }.isEnabled = false
            addOverflowIconAction(1, R.drawable.ic_line_copy, "复制整段会话") { copyConversation() }
            addOverflowIconAction(10, R.drawable.ic_line_edit, "重命名") { renameConversation() }.isEnabled = false
            addIconAction(3, R.drawable.ic_line_play, "回听原始音频") { playAudio() }.apply {
                icon = playbackIcon; isVisible = false
            }
            addIconAction(8, R.drawable.ic_line_subtitles, "音频字幕") { activateSubtitles() }.isVisible = false
            addOverflowIconAction(9, R.drawable.ic_line_subtitles, "完整字幕") { showSubtitles() }.isVisible = false
            addOverflowIconAction(5, R.drawable.ic_line_tag, "设置会话标签") {
                if (ready && favorite) collectionTags.editTags(listOf(session.conversationId))
            }.apply {
                isVisible = false
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        statusView = label("", 11f, true).apply { setPadding(dp(22), dp(8), dp(22), 0) }
        root.addView(statusView)
        mediaView = label("", 10f, true).apply { setPadding(dp(22), dp(4), dp(22), 0) }
        root.addView(mediaView)
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; addView(messagesView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            composerLayout = TextInputLayout(this@ChatScreen, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "想了解什么？"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            composer = TextInputEditText(composerLayout.context).apply {
                enableTextActions(selectable = false)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                minHeight = dp(56); minLines = 1; maxLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(12000))
                accessible("问题输入框")
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { composerLayout.error = null }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            composerLayout.addView(composer, LinearLayout.LayoutParams(-1, -2))
            addView(composerLayout, LinearLayout.LayoutParams(-1, -2))
        })
        contextTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(10), dp(10))
            // Reserve only the space left by the action buttons, including on 320dp screens.
            addView(LinearLayout(this@ChatScreen).apply {
                gravity = Gravity.CENTER_VERTICAL
                reuse = MaterialCheckBox(this@ChatScreen).apply {
                    text = "附带图片"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                    setTextColor(MUTED); minHeight = dp(48); minWidth = 0
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    accessible("追问时附带当前会话图片")
                    setOnCheckedChangeListener { _, _ -> if (ready) renderEvidence() }
                }
                addView(reuse, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            webSearch = iconButton(R.drawable.ic_globe, "联网搜索：关闭") {
                webSearchOverride = !(webSearchOverride ?: defaultWebSearch)
                renderWebSearch()
            }.apply { isCheckable = true }
            addView(webSearch)
            retry = appIcon(android.R.drawable.ic_popup_sync, "重试上次请求") {
                if (ready) { restoredStatus = ""; session.retry() }
            }
            addView(retry)
            send = appIcon(android.R.drawable.ic_menu_send, "发送问题") {
                if (ready && session.busy) session.cancel() else submit()
            }.apply {
                applyIconActionStyle(primary=true)
            }
            addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        root.addView(contextTools)
        renderWebSearch()
        return root
    }

    private fun render() {
        val nextReading=readingPreferences.load().chatPointReading
        if(readingEnabled!=nextReading) {
            readingEnabled=nextReading
            if(readingEnabled) {
                readingBar=ReadingBar(this,session.conversationId,ReadingOrigin.CHAT).also {
                    val root=view as LinearLayout
                    root.addView(it,root.indexOfChild(scroll)+1,LinearLayout.LayoutParams(-1,-2))
                }
            } else {
                (readingBar?.parent as? ViewGroup)?.removeView(readingBar);readingBar=null
            }
        }
        conversationTitle = session.title
        toolbar.title = conversationTitle
        toolbar.menu.findItem(3)?.isVisible = availableAudio() != null
        updateSubtitleAction()
        renderCollectionActions()
        val hasPending = session.question.isNotBlank()
        val providerLabel = if (session.busy || session.question.isNotBlank()) session.requestProviderLabel else ProviderCapabilities.label(activeConnection)
        statusView.text = providerLabel + "\n" +
            (if (restoredStatus.isNotBlank()) "上次状态：$restoredStatus" else session.status)
        statusView.visibility = if (statusView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        renderEvidence()
        renderMessages(hasPending)
        send.icon = prototypeIcon(if (session.busy) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_send)
        send.accessible(if (session.busy) "取消当前请求" else "发送问题")
        retry.visibility = if (session.canRetry) View.VISIBLE else View.GONE
        retry.accessible(session.retryLabel)
        reuse.isEnabled = image != null && !session.busy
        webSearch.isEnabled = !session.busy
        renderWebSearch()
        composerLayout.isEnabled = !session.busy
        composerLayout.hint = if (session.messages.isEmpty() && !hasPending) "想了解什么？" else "继续追问…"
    }

    /** Stream ticks update only the pending rows; completed text and WebViews remain attached. */
    private fun renderMessages(hasPending: Boolean) {
        val atBottom = messagesView.height - scroll.height - scroll.scrollY <= dp(72)
        var structureChanged = renderedConversationId != session.conversationId
        if (structureChanged) {
            disposeMessageRows()
            evidencePreviews.clear()
            emptyMessageView = null
            renderedHistorySize = -1
            renderedConversationId = session.conversationId
        }
        val previews = ArrayList<View>(4)
        val previewKeys = mutableSetOf<String>()
        fun preview(key: String, value: Any, create: () -> View) {
            previewKeys.add(key)
            var cached = evidencePreviews[key]
            if (cached == null || cached.value != value) {
                cached = EvidencePreview(value, create())
                evidencePreviews[key] = cached
                structureChanged = true
            }
            previews.add(cached.view)
        }
        originalImage?.let { bitmap -> preview("original", bitmap) { createImagePreview("会话原图", bitmap) } }
        image?.takeIf { it !== originalImage }?.let { bitmap ->
            preview("image", bitmap) { createImagePreview("当前追问图片", bitmap) }
        }
        if (session.sourceText.isNotBlank()) preview("source", session.sourceText) {
            createEvidencePreview("选中文字", session.sourceText) { showSourceText() }
        }
        if (session.transcript.isNotBlank()) preview("transcript", session.transcript) {
            createEvidencePreview("音频转写", session.transcript)
        }
        if (evidencePreviews.keys.retainAll(previewKeys)) structureChanged = true

        // ChatSession appends immutable messages or replaces the history on restoration. Checking
        // its boundaries avoids walking an unbounded history for every 80 ms streaming update.
        val messages = session.messages
        val historyChanged = messages.size != renderedHistorySize ||
            messages.firstOrNull() !== renderedHistoryFirst || messages.lastOrNull() !== renderedHistoryLast
        var contentChanged = false
        if (historyChanged) {
            val previousRows = messageRows.values.toSet() + listOfNotNull(pendingQuestionRow, partialAnswerRow)
            val nextRows = IdentityHashMap<ChatMessage, MessageRow>()
            val committedQuestion = !hasPending && pendingQuestionRow != null && messages.size >= 2 &&
                messages[messages.lastIndex - 1].let { it.role == "user" && it.text == pendingQuestionRow?.value }
            historyRows = messages.mapIndexed { index, message ->
                val existing = messageRows[message]
                val row = existing ?: when {
                    committedQuestion && index == messages.lastIndex - 1 -> pendingQuestionRow
                    committedQuestion && index == messages.lastIndex && message.role == "assistant" -> partialAnswerRow
                    else -> null
                } ?: createMessageRow(message.role)
                if (existing == null) updateMessageRow(row, message.text, message.evidence, message.searchSuggestions,
                    readingBlock = ReadingAnchor.message(index, message.role))
                nextRows[message] = row
                row
            }
            messageRows = nextRows
            if (!hasPending) { pendingQuestionRow = null; partialAnswerRow = null }
            val retainedRows = historyRows.toSet() + listOfNotNull(pendingQuestionRow, partialAnswerRow)
            previousRows.filterNot { it in retainedRows }.forEach { it.dispose() }
            renderedHistorySize = messages.size
            renderedHistoryFirst = messages.firstOrNull()
            renderedHistoryLast = messages.lastOrNull()
            structureChanged = true
        }
        if (hasPending) {
            if (pendingQuestionRow == null) {
                pendingQuestionRow = createMessageRow("user")
                structureChanged = true
            }
            contentChanged = updateMessageRow(pendingQuestionRow!!, session.question, session.evidence,
                readingBlock = ReadingAnchor.message(messages.size, "user")) || contentChanged
        } else if (pendingQuestionRow != null) {
            pendingQuestionRow?.dispose(); pendingQuestionRow = null
            structureChanged = true
        }
        if (hasPending && session.partial.isNotBlank()) {
            if (partialAnswerRow == null) {
                partialAnswerRow = createMessageRow("assistant")
                structureChanged = true
            }
            contentChanged = updateMessageRow(partialAnswerRow!!, session.partial,
                if (session.busy) "正在生成" else "未完成的回复，尚未加入上下文", complete=false) || contentChanged
        } else if (partialAnswerRow != null) {
            partialAnswerRow?.dispose(); partialAnswerRow = null
            structureChanged = true
        }
        val showEmpty = messages.isEmpty() && !hasPending
        if (showEmpty) {
            if (emptyMessageView == null) {
                emptyMessageView = label("", 12f, true).apply { setPadding(0, dp(12), 0, dp(12)) }
                structureChanged = true
            }
            val text = if (session.transcript.isNotBlank()) "原始转写已恢复，可以继续询问这段内容。" else "输入问题，开始对话。"
            if (emptyMessageView?.text != text) { emptyMessageView?.text = text; contentChanged = true }
        } else if (emptyMessageView != null) { emptyMessageView = null; structureChanged = true }

        var anchor: View? = null
        var anchorOffset = 0
        if (structureChanged) {
            val desired = previews + historyRows.map { it.block } + listOfNotNull(
                emptyMessageView, pendingQuestionRow?.block, partialAnswerRow?.block)
            val retained = desired.toSet()
            if (!atBottom) {
                anchor = (0 until messagesView.childCount).asSequence().map { messagesView.getChildAt(it) }
                    .firstOrNull { it.bottom > scroll.scrollY && it in retained }
                anchorOffset = scroll.scrollY - (anchor?.top ?: 0)
            }
            // Remove obsolete rows first so inserting evidence or trimming history never detaches
            // unchanged selectable text or a retained search WebView just to shift its position.
            for (index in messagesView.childCount - 1 downTo 0) {
                if (messagesView.getChildAt(index) !in retained) messagesView.removeViewAt(index)
            }
            desired.forEachIndexed { index, child ->
                if (messagesView.getChildAt(index) !== child) {
                    (child.parent as? ViewGroup)?.removeView(child)
                    messagesView.addView(child, index)
                }
            }
        }
        if (structureChanged || contentChanged) scroll.post {
            if (!closing) {
                // scrollTo leaves focus and text selection with their existing owners.
                if (atBottom) scroll.scrollTo(0, messagesView.height)
                else anchor?.takeIf { it.parent === messagesView }?.let { scroll.scrollTo(0, it.top + anchorOffset) }
            }
        }
    }

    private fun disposeMessageRows() {
        (messageRows.values.toSet() + listOfNotNull(pendingQuestionRow, partialAnswerRow)).forEach { it.dispose() }
        messageRows.clear(); historyRows = emptyList()
        pendingQuestionRow = null; partialAnswerRow = null
    }

    private fun refreshCollectionState() {
        if (!ready) return
        runCatching { collectionStore.summary(session.conversationId) }
            .onSuccess { favorite = it?.isFavorite == true }
        renderCollectionActions()
    }

    private fun renderCollectionActions() {
        toolbar.menu.findItem(10)?.isEnabled = ready
        val label = if (favorite) "取消收藏" else "收藏会话"
        toolbar.menu.findItem(4)?.apply {
            icon = prototypeIcon(if (favorite) R.drawable.ic_star_filled else R.drawable.ic_line_star)
            iconTintList = android.content.res.ColorStateList.valueOf(if (favorite) ACCENT else INK)
            title = label; contentDescription = label; tooltipText = label
            isEnabled = ready && (openedStoredRecord || session.messages.isNotEmpty() ||
                session.question.isNotBlank() || session.transcript.isNotBlank() || session.sourceText.isNotBlank())
        }
        toolbar.menu.findItem(5)?.isVisible = ready && favorite
    }

    private fun toggleFavorite() {
        if (!ready) return
        runCatching {
            var stored = collectionStore.summary(session.conversationId)
            if (stored == null) {
                collectionStore.save(session.snapshot())
                stored = collectionStore.summary(session.conversationId)
            }
            collectionStore.setFavorite(session.conversationId, stored?.isFavorite != true)
            favorite = stored?.isFavorite != true
        }.onSuccess {
            renderCollectionActions()
            toast(if (favorite) "已收藏" else "已取消收藏")
        }.onFailure { toast(it.message?.take(120) ?: "无法保存收藏，请重试") }
    }

    private fun renameConversation() {
        if (!ready || closing) return
        activity.showConversationTitleDialog(session.title) { title ->
            if (closing) error("请重新打开聊天后修改标题")
            if (collectionStore.summary(session.conversationId) == null) collectionStore.save(session.snapshot())
            collectionStore.rename(session.conversationId, title)
            render()
        }
    }

    private fun renderWebSearch() {
        val selected = webSearchOverride ?: defaultWebSearch
        val supported = ProviderCapabilities.search(activeConnection)
        // The screen also renders before a conversation is bound or when its record is unavailable.
        webSearch.isEnabled = ready && !session.busy && (supported || selected)
        webSearch.tooltipText = if (supported) "允许模型搜索网页" else "当前模型或协议不支持搜索，请关闭已开启的搜索或更换模型"
        webSearch.isChecked = selected
        webSearch.applySearchToggleStyle(selected)
        webSearch.accessible(if (selected) "联网搜索：开启，点按关闭" else "联网搜索：关闭，点按开启")
    }

    private fun createEvidencePreview(title: String, text: String, expand: (() -> Unit)? = null): View =
        MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE); strokeWidth = 0; cardElevation = 0f
            radius = dp(12).toFloat(); isClickable = true; isFocusable = true
            accessible("展开完整$title")
            val heading = label("$title · 点按展开", 11f, true)
            val content = label(text, 12f).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            var expanded = false
            setOnClickListener {
                if (expand != null) expand() else {
                    expanded = !expanded
                    content.maxLines = if (expanded) Int.MAX_VALUE else 3
                    content.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                    if (expanded) content.enableChatReading(session.conversationId, block = "transcript") else {
                        content.setTextIsSelectable(false)
                        content.isClickable = false; content.isLongClickable = false
                    }
                    heading.text = "$title · 点按${if (expanded) "收起" else "展开"}"
                    accessible(if (expanded) "收起$title" else "展开完整$title")
                }
            }
            heading.setOnClickListener { performClick() }
            addView(column().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(heading)
                addView(content)
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }

    private fun createImagePreview(title: String, bitmap: Bitmap): View =
        MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE); strokeWidth = 0; cardElevation = 0f; radius = dp(12).toFloat()
            isClickable = true; isFocusable = true; accessible("查看$title，可双指缩放")
            setOnClickListener {
                if(InteractionPreferences(this@ChatScreen).load().imagePointReading) {
                    readingImageDialog?.dismiss()
                    readingImageDialog=showImagePointReading(this@ChatScreen,bitmap,session.conversationId)
                    return@setOnClickListener
                }
                val dialog = MaterialAlertDialogBuilder(this@ChatScreen).setTitle(title)
                    .setView(ScreenshotView(this@ChatScreen, bitmap))
                    .setPositiveButton("返回对话", null).create()
                dialog.show()
                dialog.window?.setLayout(-1, -1)
            }
            addView(column().apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(label("$title · 点按放大", 11f, true))
                addView(ImageView(this@ChatScreen).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bitmap)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(180)))
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }

    private fun renderEvidence() {
        mediaView.text = when {
            image != null && reuse.isChecked -> "追问附带${if (image === originalImage) "会话原图" else "当前图片"}与本段文字上下文"
            image != null -> "仅文字追问 · 图片仍可切换使用"
            session.kind in setOf("photo", "screen", "image") -> "原图未保留，当前仅使用文字；如需新画面，请从悬浮球重新截图。"
            session.transcript.isNotBlank() -> "后续提问使用转写文字"
            session.sourceText.isNotBlank() -> "后续提问使用本段选中文字"
            handoff?.audio != null -> if (session.busy) "正在处理已采集音频" else "本段音频尚未转写，发送问题后开始处理"
            availableAudio() != null -> "原始音频已保存，可点击上方播放图标回听；发送问题后开始转写"
            session.kind == "audio" -> "原始音频未保留；当前只能使用已保存的文字消息"
            else -> "文字上下文"
        }
        mediaView.visibility = if (mediaView.text == "文字上下文") View.GONE else View.VISIBLE
        reuse.visibility = if (image != null || session.kind in setOf("photo", "screen", "image")) View.VISIBLE else View.GONE
        if (image == null) reuse.isChecked = false
    }

    private fun createMessageRow(role: String): MessageRow {
        val isUser = role == "user"
        val messageText = label("", if (isUser) 12f else 13f).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            enableTextActions()
            setLineSpacing(dp(4).toFloat(), 1f)
            if (isUser) { maxWidth = dp(320); setTextColor(ON_TONAL_SURFACE) }
        }
        val notice = label("", 10f, true).apply { setPadding(0, dp(6), 0, 0); visibility = View.GONE }
        val block = column().apply {
            setPadding(if (isUser) dp(10) else 0, if (isUser) dp(7) else 0,
                if (isUser) dp(10) else 0, if (isUser) dp(7) else 0)
            if (isUser) background = card(TONAL_SURFACE, dp(20).toFloat())
            if (role != "assistant" && role != "user") addView(label("上下文", 10f, true))
            addView(messageText)
            addView(notice)
        }
        block.layoutParams = LinearLayout.LayoutParams(if (isUser) -2 else -1, -2).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            if (isUser) marginStart = dp(32)
            topMargin = dp(4); bottomMargin = dp(if (isUser) 8 else 12)
        }
        return MessageRow(role, block, messageText, notice)
    }

    private fun updateMessageRow(row: MessageRow, value: String, evidence: String, suggestions: String = "", complete: Boolean = true,
        readingBlock: String = "partial"): Boolean {
        var changed = false
        if (row.value != value) {
            row.text.text = MarkdownText.render(value)
            row.value = value
            changed = true
        }
        row.text.enableChatReading(session.conversationId, complete, block = readingBlock)
        val html = suggestions.takeIf { row.role != "user" && it.isNotBlank() && it.length <= 32768 }.orEmpty()
        if (row.suggestions != html) {
            row.suggestionView?.let { row.block.removeView(it); it.destroy() }
            row.suggestionView = html.takeIf { it.isNotEmpty() }?.let { searchSuggestionsView(it) }
            row.suggestionView?.let { row.block.addView(it, row.block.indexOfChild(row.notice)) }
            row.suggestions = html
            changed = true
        }
        val notice = evidence.takeIf { row.role != "user" && it.contains("未完成") }.orEmpty()
        if (row.notice.text != notice) {
            row.notice.text = notice
            row.notice.visibility = if (notice.isEmpty()) View.GONE else View.VISIBLE
            changed = true
        }
        return changed
    }

    private fun submit() {
        if (!ready || session.busy) return
        val text = composer.text.toString().trim()
        if (text.isBlank()) { composerLayout.error = "请输入问题"; return }
        try {
            val defaults = SettingsStore(this).load()
            if (defaults.connectionId != activeConnection.connectionId) webSearchOverride = null
            activeConnection = defaults
            defaultWebSearch = defaults.webSearch
            val config = defaults.copy(webSearch = webSearchOverride ?: defaults.webSearch)
            SettingsStore.validate(config)
            val attachment = image?.takeIf { reuse.isChecked }
            val pendingAudio = handoff?.audio?.takeIf { session.transcript.isBlank() }
            val pendingAudioFile = if (session.transcript.isBlank()) availableAudio() else null
            if (pendingAudio != null) {
                require(pendingAudio.error == null) { pendingAudio.error ?: "音频采集失败，请重新录制" }
                require(pendingAudio.file.isFile && pendingAudio.file.length() > 44) { "原始音频已不可用，请重新录制" }
                require(pendingAudio.peak >= 0.0001) { "没有检测到可用的内部音频，请确认目标应用允许采集并重新录制" }
            }
            val label = when {
                pendingAudio != null -> "内部播放音频 · ${"%.1f".format(pendingAudio.seconds)} 秒"
                pendingAudioFile != null -> "已保存的内部播放音频"
                attachment != null -> if (image === originalImage) "沿用会话原图" else "沿用当前追问图片"
                session.transcript.isNotBlank() -> "沿用音频转写文字"
                session.sourceText.isNotBlank() -> "系统选中文字"
                else -> "仅文字"
            }
            restoredStatus = ""
            session.submit(config, text, attachment, label, pendingAudioFile, useDefaultAudioOutput = false)
            composer.text?.clear()
        } catch (error: Exception) {
            toast(error.message?.take(180) ?: "发送失败，请检查 API 设置")
        }
    }

    private fun availableAudio(): java.io.File? =
        handoff?.audio?.file?.takeIf { it.isFile && it.length() > 44 }
            ?: if (::session.isInitialized && session.kind == "audio")
                runCatching { ConversationMediaStore(this).loadAudio(session.conversationId) }.getOrNull()
                    ?: playback.fileFor(session.conversationId) else null

    private fun playAudio() {
        if (playback.isActive(session.conversationId) && playback.readingKey.isEmpty()) { playback.toggle(); return }
        val file = availableAudio() ?: run { toast("原始音频未保留或已被删除"); return }
        playback.play(session.conversationId, file)
    }

    fun showSubtitles() {
        if (!ready || closing || session.kind != "audio" || !subtitlesEnabled()) return
        pendingSubtitleAction = false; pendingSubtitleOverlay = false
        updateSubtitleAction()
        subtitleDialog?.dismiss()
        subtitleDialog = SubtitlePanel(activity, { session.snapshot() }, ::availableAudio).show()
    }

    private fun subtitlesEnabled() = subtitlePreferences.anyEnabled() || readingPreferences.load().backgroundPlaybackSubtitles ||
        !subtitles.record(session.conversationId, SubtitleMethod.WHISPER).playable?.cues.isNullOrEmpty()

    private fun activateSubtitles() {
        if (!ready || closing || session.kind != "audio" || !subtitlesEnabled()) return
        if (pendingSubtitleAction || pendingSubtitleOverlay) return
        val owner = session.conversationId
        val record = subtitles.record(owner, SubtitleMethod.WHISPER)
        if (!record.loaded) {
            pendingSubtitleAction = true
            updateSubtitleAction(); subtitles.refresh(owner)
            return
        }
        if (record.playable?.cues.isNullOrEmpty()) { showSubtitles(); return }
        if (SubtitleOverlayController.showing(activity, owner)) {
            SubtitleOverlayController.toggle(activity); return
        }
        if (!SubtitleOverlayController.canDisplay(activity)) { showSubtitles(); return }
        if (SubtitleOverlayController.available(activity, owner)) {
            if (!SubtitleOverlayController.restore(activity)) showSubtitles()
            return
        }
        val file = availableAudio()
        if (file == null) { toast("原始音频不可用，可查看完整字幕"); showSubtitles(); return }
        pendingSubtitleOverlay = true
        updateSubtitleAction()
        playback.prepareForSubtitles(owner, file)
        finishSubtitleOverlay()
    }

    private fun finishSubtitleOverlay() {
        if (!ready || closing || !view.isAttachedToWindow || !pendingSubtitleOverlay || playback.currentState.preparing) return
        pendingSubtitleOverlay = false
        if (!SubtitleOverlayController.available(activity, session.conversationId) || !SubtitleOverlayController.restore(activity)) showSubtitles()
        updateSubtitleAction()
    }

    private fun updateSubtitleAction() {
        val visible = session.kind == "audio" && subtitlesEnabled()
        toolbar.menu.findItem(9)?.isVisible = visible
        toolbar.menu.findItem(8)?.apply {
            isVisible = visible
            isEnabled = !pendingSubtitleAction && !pendingSubtitleOverlay
            val showing = visible && SubtitleOverlayController.showing(activity, session.conversationId)
            val hasCues = !subtitles.record(session.conversationId, SubtitleMethod.WHISPER).playable?.cues.isNullOrEmpty()
            val label = if (showing) "隐藏字幕" else if (hasCues) "显示字幕" else "音频字幕"
            title = label; contentDescription = label; tooltipText = label
            iconTintList = android.content.res.ColorStateList.valueOf(if (showing) ACCENT else INK)
        }
    }

    private fun updatePlayback(state: ChatAudioPlayback.State) {
        playbackIcon.update(state)
        updateSubtitleAction()
        toolbar.menu.findItem(3)?.apply {
            val enabled = !state.preparing
            if (isEnabled != enabled) isEnabled = enabled
            val label = when {
                state.preparing -> "正在读取音频"
                state.playing -> "暂停回听"
                state.remaining != null -> "继续回听"
                else -> "回听原始音频"
            }
            if (title != label) {
                title = label; contentDescription = label; tooltipText = label
            }
        }
    }

    private fun showSourceText() {
        if (!ready || session.sourceText.isBlank()) return
        MaterialAlertDialogBuilder(this).setTitle("选中文字")
            .setView(ScrollView(this).apply {
                addView(column().apply { addView(label(session.sourceText).apply { enableChatReading(session.conversationId) }) })
            }).setPositiveButton("返回对话", null).show()
    }

    private fun copyConversation() {
        if (!ready) return
        val value = session.snapshot()
        val text = buildString {
            append(conversationTitle).append('\n')
            if (session.sourceText.isNotBlank()) append("\n选中文字\n").append(session.sourceText).append('\n')
            if (value.transcript.isNotBlank()) append("\n原始转写\n").append(value.transcript).append('\n')
            value.messages.forEach {
                append('\n').append(if (it.role == "user") "你" else if (it.role == "assistant") "助手" else "上下文")
                    .append('\n').append(it.text).append('\n')
            }
            if (session.partial.isNotBlank()) append("\n未完成的回复\n").append(session.partial)
        }
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide 会话", text))
        }.onSuccess { toast("已复制文字会话") }.onFailure { toast("复制失败，可长按文字选择复制") }
    }

    private fun showUnavailable(message: String) {
        toolbar.title = "会话不可用"
        statusView.text = message
        statusView.visibility = View.VISIBLE
        mediaView.text = ""
        mediaView.visibility = View.GONE
        composerLayout.isEnabled = false; send.isEnabled = false
        retry.visibility = View.GONE; reuse.visibility = View.GONE
        contextTools.visibility = View.GONE
    }

    fun saveState(outState: Bundle) {
        // A failed/deleted history entry must remain that entry after recreation, not become a new chat.
        if (!ready) requestedId?.let { outState.putString(EXTRA_ID, it) }
        if (ready) {
            if (handoff != null || openedStoredRecord || session.messages.isNotEmpty() || session.question.isNotBlank() || session.transcript.isNotBlank())
                outState.putString(EXTRA_ID, session.conversationId)
            outState.putString("draft", composer.text.toString())
            outState.putBoolean("reuse", reuse.isChecked)
            webSearchOverride?.let { outState.putBoolean("webSearchOverride", it) }
        }
    }

    fun dispose(retaining: Boolean = false) {
        pendingSubtitleAction = false; pendingSubtitleOverlay = false
        subtitles.removeObserver(subtitleObserver)
        subtitleDialog?.dismiss(); subtitleDialog = null
        subtitlePreferences.removeListener(subtitleListener)
        readingImageDialog?.dismiss();readingImageDialog=null
        disposeMessageRows()
        evidencePreviews.clear()
        if (closing) return
        closing = true
        playback.removeObserver(playbackObserver)
        readingPreferences.removeListener(readingListener)
        playback.setVisible(this, null, false, retaining = retaining)
        if (!retaining || !ready) releaseConversation()
    }

    fun retain(): RetainedConversation? {
        if (!ready) return null
        session.setObserver(null)
        return RetainedConversation(session, image, handoff, conversationTitle, restoredStatus,
            openedStoredRecord, composer.text.toString(), reuse.isChecked, webSearchOverride, originalImage)
    }

    fun setPlaybackForeground(foreground: Boolean) {
        playbackForeground = foreground
        if (foreground && !view.isAttachedToWindow) return
        playback.setVisible(this, if (ready) session.conversationId else null,
            foreground && view.isAttachedToWindow && !closing)
    }

    /** Keep a single owner when Home leaves this Activity alive behind the overlay. */
    fun returnToFloating(): Boolean {
        if (!ready || returnedToFloating) return false
        val state = AppConversationState(composer.text.toString(), webSearchOverride, reuse.isChecked)
        if (handoff?.returnToFloating(state) != true) return false
        session.setObserver(null)
        returnedToFloating = true
        return true
    }

    private fun releaseConversation() {
        if (::session.isInitialized) {
            session.setObserver(null)
            if (handoff != null) {
                if (!returnedToFloating && !returnToFloating()) handoff?.dispose?.invoke()
            } else session.close()
        } else handoff?.dispose?.invoke()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "conversationId"
        const val EXTRA_AUTO_TEXT = "autoAskSelectedText"
    }
}
