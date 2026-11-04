package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen in-app conversation. This path never starts a service or capture. */
internal class ChatScreen(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val onSettings: () -> Unit,
    id: String? = null,
    savedState: Bundle? = null,
    retained: RetainedConversation? = null,
    autoAskText: Boolean = false,
) : ContextWrapper(activity) {
    val view: View
    private val requestedId = savedState?.getString(EXTRA_ID) ?: id
    private lateinit var session: ChatSession
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var mediaView: TextView
    private lateinit var messagesView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var composer: EditText
    private lateinit var send: ImageButton
    private lateinit var retry: ImageButton
    private lateinit var transcript: ImageButton
    private lateinit var reuse: CheckBox
    private lateinit var contextTools: LinearLayout
    private var image: Bitmap? = null
    private var ready = false
    private var closing = false
    private var conversationTitle = "文字对话"
    private var restoredStatus = ""
    private var handoff: HandedOffConversation? = null
    private var openedStoredRecord = false
    internal data class RetainedConversation(
        val session: ChatSession, val image: Bitmap?, val handoff: HandedOffConversation?,
        val title: String, val status: String, val openedStoredRecord: Boolean,
        val draft: String, val reuseImage: Boolean,
    )

    init {
        view = buildScreen()
        if (retained != null) {
            session = retained.session; image = retained.image; handoff = retained.handoff
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
            image = handoff?.image ?: image
            (handoff?.session?.snapshot() ?: HistoryStore(this).find(id)).also {
                if (image == null) image = runCatching { ConversationMediaStore(this).loadImage(id) }.getOrNull()
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
        bindConversation(draft.ifBlank { handoff?.draft.orEmpty() }, reuseImage)
    }

    private fun bindConversation(draft: String, reuseImage: Boolean) {
        session.setObserver { if (ready && !closing) render() }
        ready = true
        composer.setText(draft)
        reuse.isChecked = image != null && reuseImage
        send.isEnabled = true
        render()
    }

    private fun maybeAskSelectedText(explicitlyRequested: Boolean) {
        if (!explicitlyRequested || !ready || session.sourceText.isBlank() || session.busy ||
            session.messages.isNotEmpty() || session.question.isNotBlank()) return
        try {
            composer.setText(SettingsStore(this).load().textQuestion.ifBlank { "请将选中文字翻译成中文，并按需要解释。" })
            if (InteractionPreferences(this).load().autoAskText) submit()
        } catch (error: Exception) {
            toast(error.message?.take(160) ?: "无法读取默认提问，请输入问题后发送")
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(appIcon(android.R.drawable.ic_menu_revert, "返回对话列表") { onBack() })
            titleView = label("对话", 13f).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            addView(appIcon(R.drawable.ic_copy, "复制整段会话") { copyConversation() })
            addView(appIcon(android.R.drawable.ic_menu_manage, "设置") {
                onSettings()
            })
        })
        root.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
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
        contextTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(12), 0, dp(10), 0)
            reuse = CheckBox(this@ChatScreen).apply {
                text = "沿用原图"; textSize = 11f; setTextColor(MUTED); minHeight = dp(44)
                accessible("追问时附带这段会话的原图")
                buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, _ -> if (ready) renderEvidence() }
            }
            addView(reuse, LinearLayout.LayoutParams(0, -2, 1f))
            transcript = appIcon(android.R.drawable.ic_menu_info_details, "查看原始音频转写") { showTranscript() }
            addView(transcript)
            retry = appIcon(android.R.drawable.ic_popup_sync, "重试上次请求") {
                if (ready) { restoredStatus = ""; session.retry() }
            }
            addView(retry)
        }
        root.addView(contextTools)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(10), dp(10))
            composer = EditText(this@ChatScreen).apply {
                hint = "想了解什么？"; setHintTextColor(MUTED); setTextColor(INK); textSize = 13f
                background = card(SURFACE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                minHeight = dp(40); minLines = 1; maxLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(12000))
                accessible("问题输入框")
                applyInputStyle()
            }
            addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
            send = appIcon(android.R.drawable.ic_menu_send, "发送问题") {
                if (ready && session.busy) session.cancel() else submit()
            }.apply {
                applyIconActionStyle(primary=true)
            }
            addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(3) })
        })
        return root
    }

    private fun render() {
        titleView.text = conversationTitle
        val hasPending = session.question.isNotBlank()
        statusView.text = if (restoredStatus.isNotBlank()) "上次状态：$restoredStatus" else session.status
        statusView.visibility = if (statusView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        renderEvidence()
        val atBottom = scroll.getChildAt(0)?.let { it.height - scroll.height - scroll.scrollY <= dp(72) } ?: true
        messagesView.removeAllViews()
        if (session.sourceText.isNotBlank()) {
            messagesView.addView(column().apply {
                setPadding(dp(10), dp(6), dp(10), dp(10)); background = card(SURFACE)
                addView(LinearLayout(this@ChatScreen).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(label("选中文字", 11f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(appIcon(android.R.drawable.ic_menu_view, "查看完整选中文字") { showSourceText() })
                })
                addView(label("“${session.sourceText.take(240)}${if (session.sourceText.length > 240) "…" else ""}”", 12f)
                    .apply { maxLines = 5; setTextIsSelectable(true) })
            })
        }
        if (session.messages.isEmpty() && !hasPending) {
            messagesView.addView(label(if (session.transcript.isNotBlank()) "原始转写已恢复，可以继续询问这段内容。" else "输入问题，开始对话。", 12f, true).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        session.messages.forEach { message -> addMessage(message.role, message.text, message.evidence) }
        if (hasPending) {
            addMessage("user", session.question, session.evidence)
            if (session.partial.isNotBlank()) addMessage("assistant", session.partial, if (session.busy) "正在生成" else "未完成的回复，尚未加入上下文")
        }
        send.setImageDrawable(prototypeIcon(if (session.busy) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_send))
        send.accessible(if (session.busy) "取消当前请求" else "发送问题")
        retry.visibility = if (session.canRetry) View.VISIBLE else View.GONE
        transcript.visibility = if (session.transcript.isNotBlank()) View.VISIBLE else View.GONE
        contextTools.visibility = if (reuse.visibility == View.VISIBLE || retry.visibility == View.VISIBLE || transcript.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        reuse.isEnabled = image != null && !session.busy
        composer.isEnabled = !session.busy
        composer.hint = if (session.messages.isEmpty() && !hasPending) "想了解什么？" else "继续追问…"
        if (atBottom) scroll.post { if (!closing) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderEvidence() {
        mediaView.text = when {
            image != null && reuse.isChecked -> "追问附带原图与本段文字上下文"
            image != null -> "仅文字追问 · 原图仍可切换使用"
            session.kind in setOf("photo", "screen", "image") -> "原图未保留，当前仅使用文字；如需新画面，请从悬浮球重新截图。"
            session.transcript.isNotBlank() -> "追问复用原始转写文字，不重新上传音频"
            session.sourceText.isNotBlank() -> "追问复用本段选中文字"
            handoff?.audio != null -> if (session.busy) "正在处理已采集音频" else "本段音频尚未转写，发送问题后开始处理"
            session.kind == "audio" -> "原始音频未保留；当前只能使用已保存的文字消息"
            else -> "文字上下文"
        }
        mediaView.visibility = if (mediaView.text == "文字上下文") View.GONE else View.VISIBLE
        reuse.visibility = if (image != null || session.kind in setOf("photo", "screen", "image")) View.VISIBLE else View.GONE
        if (image == null) reuse.isChecked = false
    }

    private fun addMessage(role: String, value: String, evidence: String) {
        val isUser = role == "user"
        val block = column().apply {
            setPadding(if (isUser) dp(10) else 0, if (isUser) dp(7) else 0,
                if (isUser) dp(10) else 0, if (isUser) dp(7) else 0)
            if (isUser) background = card(SURFACE)
            if (role != "assistant" && role != "user") addView(label("上下文", 10f, true))
            if (evidence.isNotBlank()) addView(label(evidence, 10f, true).apply { setPadding(0, 0, 0, dp(4)) })
            addView(label("", if (isUser) 12f else 13f).apply {
                text = MarkdownText.render(value); setTextIsSelectable(true)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        messagesView.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
    }

    private fun submit() {
        if (!ready || session.busy) return
        val text = composer.text.toString().trim()
        if (text.isBlank()) { composer.error = "请输入问题"; return }
        try {
            val config = SettingsStore(this).load()
            SettingsStore.validate(config)
            val attachment = image?.takeIf { reuse.isChecked }
            val pendingAudio = handoff?.audio?.takeIf { session.transcript.isBlank() }
            if (pendingAudio != null) {
                require(pendingAudio.error == null) { pendingAudio.error ?: "音频采集失败，请重新录制" }
                require(pendingAudio.file.isFile && pendingAudio.file.length() > 44) { "原始音频已不可用，请重新录制" }
                require(pendingAudio.peak >= 0.0001) { "没有检测到可用的内部音频，请确认目标应用允许采集并重新录制" }
            }
            val label = when {
                pendingAudio != null -> "内部播放音频 · ${"%.1f".format(pendingAudio.seconds)} 秒"
                attachment != null -> "沿用会话原图"
                session.transcript.isNotBlank() -> "沿用音频转写文字"
                session.sourceText.isNotBlank() -> "系统选中文字"
                else -> "仅文字"
            }
            restoredStatus = ""
            session.submit(config, text, attachment, label, pendingAudio?.file)
            composer.text.clear()
        } catch (error: Exception) {
            toast(error.message?.take(180) ?: "发送失败，请检查 API 设置")
        }
    }

    private fun showTranscript() {
        if (!ready || session.transcript.isBlank()) return
        AlertDialog.Builder(this).setTitle("原始音频转写")
            .setView(ScrollView(this).apply {
                addView(column().apply { addView(label(session.transcript).apply { setTextIsSelectable(true) }) })
            }).setPositiveButton("返回对话", null).show()
    }

    private fun showSourceText() {
        if (!ready || session.sourceText.isBlank()) return
        AlertDialog.Builder(this).setTitle("选中文字")
            .setView(ScrollView(this).apply {
                addView(column().apply { addView(label(session.sourceText).apply { setTextIsSelectable(true) }) })
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
        titleView.text = "会话不可用"
        statusView.text = message
        statusView.visibility = View.VISIBLE
        mediaView.text = ""
        mediaView.visibility = View.GONE
        composer.isEnabled = false; send.isEnabled = false
        retry.visibility = View.GONE; transcript.visibility = View.GONE; reuse.visibility = View.GONE
        contextTools.visibility = View.GONE
    }

    fun saveState(outState: Bundle) {
        // A failed/deleted history entry must remain that entry after recreation, not become a new chat.
        if (!ready) requestedId?.let { outState.putString(EXTRA_ID, it) }
        if (ready) {
            if (openedStoredRecord || session.messages.isNotEmpty() || session.question.isNotBlank() || session.transcript.isNotBlank())
                outState.putString(EXTRA_ID, session.conversationId)
            outState.putString("draft", composer.text.toString())
            outState.putBoolean("reuse", reuse.isChecked)
        }
    }

    fun dispose(retaining: Boolean = false) {
        if (closing) return
        closing = true
        if (!retaining || !ready) releaseConversation()
    }

    fun retain(): RetainedConversation? {
        if (!ready) return null
        session.setObserver(null)
        return RetainedConversation(session, image, handoff, conversationTitle, restoredStatus,
            openedStoredRecord, composer.text.toString(), reuse.isChecked)
    }

    private fun releaseConversation() {
        if (::session.isInitialized) {
            session.setObserver(null)
            if (handoff != null) handoff?.dispose?.invoke() else session.close()
        } else handoff?.dispose?.invoke()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "conversationId"
        const val EXTRA_AUTO_TEXT = "autoAskSelectedText"
    }
}
