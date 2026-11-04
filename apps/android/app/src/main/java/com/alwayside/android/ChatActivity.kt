package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen in-app conversation. This path never starts a service or capture. */
class ChatActivity : Activity() {
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
    private var image: Bitmap? = null
    private var ready = false
    private var closing = false
    private var conversationTitle = "文字对话"
    private var restoredStatus = ""
    private var handoff: HandedOffConversation? = null
    private var openedStoredRecord = false
    private data class RetainedConversation(
        val session: ChatSession, val image: Bitmap?, val handoff: HandedOffConversation?,
        val title: String, val status: String, val openedStoredRecord: Boolean,
        val draft: String, val reuseImage: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildScreen()
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedConversation
        if (retained != null) {
            session = retained.session; image = retained.image; handoff = retained.handoff
            conversationTitle = retained.title; restoredStatus = retained.status
            openedStoredRecord = retained.openedStoredRecord
            bindConversation(retained.draft, retained.reuseImage)
            return
        }
        val id = savedInstanceState?.getString(EXTRA_ID) ?: intent.getStringExtra(EXTRA_ID)
        openConversation(id, savedInstanceState?.getString("draft").orEmpty(), savedInstanceState?.getBoolean("reuse", true) ?: true)
        if (savedInstanceState == null) maybeAskSelectedText()
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedId = intent.getStringExtra(EXTRA_ID)
        if (ready && requestedId == session.conversationId) return
        releaseConversation()
        ready = false; image = null; handoff = null; restoredStatus = ""; openedStoredRecord = false
        composer.text.clear()
        messagesView.removeAllViews()
        openConversation(requestedId)
        maybeAskSelectedText()
    }

    private fun maybeAskSelectedText() {
        val explicitlyRequested = intent.getBooleanExtra(EXTRA_AUTO_TEXT, false)
        intent.removeExtra(EXTRA_AUTO_TEXT)
        if (!explicitlyRequested || !ready || session.sourceText.isBlank() || session.busy ||
            session.messages.isNotEmpty() || session.question.isNotBlank()) return
        try {
            composer.setText(SettingsStore(this).load().textQuestion.ifBlank { "请将选中文字翻译成中文，并按需要解释。" })
            if (InteractionPreferences(this).load().autoAskText) submit()
        } catch (error: Exception) {
            toast(error.message?.take(160) ?: "无法读取默认提问，请输入问题后发送")
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(appIcon(android.R.drawable.ic_menu_revert, "返回对话列表") { finish() })
            titleView = label("对话", 22f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(appIcon(R.drawable.ic_copy, "复制整段会话") { copyConversation() })
            addView(appIcon(android.R.drawable.ic_menu_manage, "设置") {
                startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
            })
        })
        statusView = label("", 13f, true)
        root.addView(statusView)
        mediaView = label("", 13f, true)
        root.addView(mediaView)
        messagesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(messagesView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            reuse = CheckBox(this@ChatActivity).apply {
                text = "沿用原图"; setTextColor(INK); accessible("追问时附带这段会话的原图")
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
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            composer = EditText(this@ChatActivity).apply {
                hint = "继续追问…"; setHintTextColor(MUTED); setTextColor(INK); textSize = 16f
                background = card(SURFACE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minLines = 1; maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(12000))
                accessible("问题输入框")
            }
            addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
            send = appIcon(android.R.drawable.ic_menu_send, "发送问题") {
                if (ready && session.busy) session.cancel() else submit()
            }
            addView(send)
        })
        setContentView(root)
    }

    private fun render() {
        titleView.text = conversationTitle
        val hasPending = session.question.isNotBlank()
        statusView.text = if (restoredStatus.isNotBlank()) "上次状态：$restoredStatus" else session.status
        renderEvidence()
        val atBottom = scroll.getChildAt(0)?.let { it.height - scroll.height - scroll.scrollY <= dp(72) } ?: true
        messagesView.removeAllViews()
        if (session.sourceText.isNotBlank()) {
            messagesView.addView(column().apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); background = card(SURFACE)
                addView(LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(label("选中文字", 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(appIcon(android.R.drawable.ic_menu_view, "查看完整选中文字") { showSourceText() })
                })
                addView(label("“${session.sourceText.take(240)}${if (session.sourceText.length > 240) "…" else ""}”", 14f)
                    .apply { maxLines = 5; setTextIsSelectable(true) })
            })
        }
        if (session.messages.isEmpty() && !hasPending) {
            messagesView.addView(label(if (session.transcript.isNotBlank()) "原始转写已恢复，可以继续询问这段内容。" else "输入问题开始对话。发送时才会请求你配置的 API。", 16f, true))
        }
        session.messages.forEach { message -> addMessage(message.role, message.text, message.evidence) }
        if (hasPending) {
            addMessage("user", session.question, session.evidence)
            if (session.partial.isNotBlank()) addMessage("assistant", session.partial, if (session.busy) "正在生成" else "未完成的回复，尚未加入上下文")
        }
        send.setImageResource(if (session.busy) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_send)
        send.accessible(if (session.busy) "取消当前请求" else "发送问题")
        retry.visibility = if (session.canRetry) View.VISIBLE else View.GONE
        transcript.visibility = if (session.transcript.isNotBlank()) View.VISIBLE else View.GONE
        reuse.isEnabled = image != null && !session.busy
        composer.isEnabled = !session.busy
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
        reuse.visibility = if (image != null || session.kind in setOf("photo", "screen", "image")) View.VISIBLE else View.INVISIBLE
        if (image == null) reuse.isChecked = false
    }

    private fun addMessage(role: String, value: String, evidence: String) {
        val block = column().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = card(SURFACE)
            addView(label(when (role) { "user" -> "你"; "assistant" -> "助手"; else -> "上下文" }, 13f, true))
            if (evidence.isNotBlank()) addView(label(evidence, 12f, true))
            addView(label("").apply { text = MarkdownText.render(value); setTextIsSelectable(true) })
        }
        messagesView.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
        mediaView.text = ""
        composer.isEnabled = false; send.isEnabled = false
        retry.visibility = View.GONE; transcript.visibility = View.GONE; reuse.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) {
            if (openedStoredRecord || session.messages.isNotEmpty() || session.question.isNotBlank() || session.transcript.isNotBlank())
                outState.putString(EXTRA_ID, session.conversationId)
            outState.putString("draft", composer.text.toString())
            outState.putBoolean("reuse", reuse.isChecked)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closing = true
        if (!isChangingConfigurations || !ready) releaseConversation()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? {
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
