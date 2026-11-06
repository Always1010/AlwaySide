package com.alwayside.android

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Main-thread state; each request owns an immutable configuration and evidence snapshot. */
internal class ChatSession(
    private val provider: AiGateway,
    private val dispatch: (() -> Unit) -> Unit,
    private val worker: ExecutorService,
    private val saveHistory: (StoredConversation) -> Unit,
    private val changed: () -> Unit,
    private val credentialsCurrent: (AppConfig, Boolean, Boolean) -> Boolean = { _, _, _ -> true },
) {
    constructor(context: Context, changed: () -> Unit) : this(
        AiProvider(), androidDispatcher(), Executors.newSingleThreadExecutor(),
        HistoryStore(context)::save, changed,
        credentialValidator(context),
    )
    private var observer: (() -> Unit)? = null
    fun setObserver(observer: (() -> Unit)?) { this.observer = observer }
    private fun notifyChanged() { changed(); observer?.invoke() }
    private fun post(action: () -> Unit) = dispatch(action)
    private var epoch = 0L
    private var cancellation: RequestCancellation? = null
    private var retry: Request? = null
    private var id = UUID.randomUUID().toString()
    val conversationId: String get() = id
    var kind = "text"; private set
    private var updatedAt = System.currentTimeMillis()
    private var cachedAudio: Pair<String, String>? = null
    val messages = mutableListOf<ChatMessage>()
    var phase = ChatRequestPhase.READY; private set
    val busy: Boolean get() = phase == ChatRequestPhase.RUNNING
    var notice: RequestNotice? = null; private set
    var partial = ""; private set
    var question = ""; private set
    var status = "等待提问"; private set
    var requestProviderLabel = ""; private set
    var evidence = "未附媒体"; private set
    var transcript = ""; private set
    private var selectedText = ""
    private var answerTitle = ""
    val title: String get() = answerTitle.ifBlank {
        val label = when (kind) { "photo" -> "截图"; "audio" -> "音频"; else -> "文字" }
        label + if (busy) "处理中" else "待提问"
    }
    val sourceText: String get() = selectedText
    fun setSourceText(text: String) {
        ChatPrompt.validateSourceText(text)
        check(!busy) { "请先等待或取消当前请求，再更新选中文字" }
        selectedText = text
        notifyChanged()
    }
    val canRetry get() = !busy && retry != null
    private data class Request(val config: AppConfig, val history: List<ChatMessage>, val question: String,
        val image: Bitmap?, val evidence: String, val audio: File?, val transcript: String, val sourceText: String, val continuationCount: Int = 0, val continuationPrefix: String = "")

    fun submit(config: AppConfig, text: String, image: Bitmap?, label: String, audio: File? = null,
               useDefaultAudioOutput: Boolean = true) {
        if (busy) return
        // A question entered by the user must also reach the response model for transcript/translation presets.
        val requestConfig = if (audio != null && !useDefaultAudioOutput) config.copy(audioOutput = "interpret") else config
        val effectiveQuestion = if (audio != null && requestConfig.audioOutput == "translate") AUDIO_TRANSLATION_QUESTION else text
        try {
            ChatPrompt.validateQuestion(effectiveQuestion)
            if (audio == null || requestConfig.audioOutput != "transcript") require(config.apiKey.isNotBlank()) { "请先为问答连接填写 API Key" }
            if (audio != null) require(ProviderConnections.transcription(requestConfig).apiKey.isNotBlank()) { "请先为转写连接填写 API Key" }
        } catch (error: Exception) {
            requestProviderLabel = ProviderCapabilities.label(requestConfig)
            reportSubmissionFailure(error)
            throw error
        }
        val audioKey = audio?.let { audioKey(it, requestConfig) }
        val known = if (audioKey != null) {
            if (cachedAudio?.first == audioKey) cachedAudio!!.second else ""
        } else transcript
        if (audio != null) kind = "audio" else if (image != null) kind = "photo"
        start(Request(requestConfig.copy(), messages.takeLast(24).toList(), effectiveQuestion, image, label, audio, known, sourceText))
    }
    val retryLabel: String get() = if (retry?.config?.continuationMessages?.isNotEmpty() == true) "继续生成" else "重试上次请求"
    fun retry() { if (!busy) retry?.let { request ->
        val needsAnswer = request.audio == null || request.config.audioOutput != "transcript"
        val needsAudio = request.audio != null && request.transcript.isEmpty()
        if (!credentialsCurrent(request.config, needsAnswer, needsAudio)) {
            retry = null; phase = ChatRequestPhase.FAILED
            status = "连接或密钥已更新，请重新发送问题"
            notice = RequestNotice("连接或密钥已更新", status)
            archive(); notifyChanged()
        } else start(request)
    } }
    private fun start(request: Request) {
        val token = ++epoch
        val cancel = RequestCancellation()
        cancellation = cancel; retry = request; phase = ChatRequestPhase.RUNNING; notice = null
        requestProviderLabel = ProviderCapabilities.label(request.config)
        partial = request.continuationPrefix; question = request.question; evidence = request.evidence
        transcript = request.transcript
        selectedText = request.sourceText
        status = if (request.audio != null && transcript.isEmpty()) "正在转写音频…" else "正在连接…"
        archive()
        notifyChanged()
        worker.execute {
            val accumulated = StringBuilder(request.continuationPrefix)
            try {
                var words = request.transcript
                if (request.audio != null && words.isEmpty()) {
                    words = provider.transcribe(request.config, request.audio, cancel)
                    cancel.throwIfCancelled()
                    val fixedWords = words
                    post {
                        if (epoch == token && busy) {
                            cachedAudio = audioKey(request.audio, request.config) to fixedWords
                            transcript = fixedWords
                            retry = request.copy(transcript = fixedWords)
                            status = if (request.config.audioOutput == "transcript") "转写完成" else "转写完成，正在回答…"
                            archive(); notifyChanged()
                        }
                    }
                }
                val imageUrl = request.image?.let { encodeImage(it) }
                cancel.throwIfCancelled()
                val prompt = ChatPrompt.compose(request.question, words, request.sourceText)
                var lastUpdate = 0L
                val generated = if (request.audio != null && request.config.audioOutput == "transcript") words else
                    provider.respond(request.config, request.history, prompt, imageUrl, cancel) { delta ->
                    accumulated.append(delta)
                    val now = System.nanoTime()
                    if (now - lastUpdate > 80_000_000L) {
                        lastUpdate = now
                        val value = accumulated.toString()
                        post { if (epoch == token && busy) { partial = value; status = "正在回答…"; notifyChanged() } }
                    }
                }
                val answer = request.continuationPrefix + generated
                val suggestions = if (request.audio != null && request.config.audioOutput == "transcript") "" else provider.searchSuggestions()
                cancel.throwIfCancelled()
                post {
                    if (epoch != token) return@post
                    if (answerTitle.isBlank()) answerTitle = ConversationTitle.fromAnswer(answer)
                    messages.add(ChatMessage("user", request.question, request.evidence))
                    messages.add(ChatMessage("assistant", answer, searchSuggestions = suggestions))
                    while (messages.size > 40) { messages.removeAt(0); messages.removeAt(0) }
                    transcript = words; partial = ""; question = ""; phase = ChatRequestPhase.COMPLETED; notice = null; retry = null; cancellation = null
                    status = if (messages.size >= 24) "已完成 · 后续追问最多参考最近 24 条文字消息" else "已完成"
                    archive()
                    notifyChanged()
                }
            } catch (e: ProviderPausedException) {
                post {
                    if (epoch != token) return@post
                    phase = ChatRequestPhase.PAUSED; cancellation = null
                    partial = accumulated.toString()
                    if (request.continuationCount >= 3) {
                        retry = null; phase = ChatRequestPhase.FAILED
                        status = "搜索续接已达 3 次，回答仍未完成，请缩小问题范围后重新发送"
                        notice = RequestNotice("回答未完成，请缩小问题范围", status)
                    } else {
                        retry = (retry ?: request).copy(config = request.config.copy(continuationMessages = e.messages),
                            continuationCount = request.continuationCount + 1, continuationPrefix = accumulated.toString())
                        status = "搜索尚未完成，点击继续生成；继续会再次调用原服务"
                        notice = RequestNotice("搜索暂停，可继续生成", status, isError = false)
                    }
                    archive(); notifyChanged()
                }
            } catch (e: Exception) {
                post {
                    if (epoch != token) return@post
                    cancellation = null
                    partial = accumulated.toString()
                    val pending = retry ?: request
                    val valid = !cancelled(e) || credentialsCurrent(pending.config,
                        pending.audio == null || pending.config.audioOutput != "transcript", pending.audio != null && pending.transcript.isEmpty())
                    if (!valid) retry = null
                    phase = if (valid && cancelled(e)) ChatRequestPhase.CANCELLED else ChatRequestPhase.FAILED
                    notice = if (!valid) RequestNotice("服务密钥已撤销或替换", "服务密钥已撤销或替换，请重新发送问题")
                        else if (cancelled(e)) RequestNotice("已取消，可手动重试", "已取消；可以手动重试", isError = false)
                        else RequestNotice.failure(e)
                    status = notice!!.detail
                    archive()
                    notifyChanged()
                }
            }
        }
    }
    fun cancel() {
        if (!busy) return
        epoch++; cancellation?.cancel(); cancellation = null; phase = ChatRequestPhase.CANCELLED
        status = "已取消；部分文字未加入上下文，可手动重试"
        notice = RequestNotice("已取消，可手动重试", status, isError = false)
        archive(); notifyChanged()
    }
    fun reportSubmissionFailure(error: Exception) {
        if (busy) return
        retry = null; phase = ChatRequestPhase.FAILED
        notice = RequestNotice.failure(error, beforeSubmission = true)
        status = notice!!.detail
        notifyChanged()
    }
    fun dismissNotice() { notice = null; notifyChanged() }
    fun newConversation(saved: StoredConversation? = null, kind: String = "text") {
        if (busy) cancel()
        epoch++; cancellation?.cancel(); cancellation = null; retry = null; notice = null
        phase = if (saved == null) ChatRequestPhase.READY else ChatRequestPhase.RESTORED
        id = saved?.id ?: UUID.randomUUID().toString()
        this.kind = saved?.kind ?: kind
        updatedAt = saved?.updatedAt ?: System.currentTimeMillis()
        messages.clear(); messages.addAll(saved?.messages.orEmpty())
        answerTitle = if (saved != null && saved.messages.any { it.role == "assistant" })
            ConversationTitle.resolve(saved.title, saved.messages) else ""
        partial = ""; question = ""; transcript = saved?.transcript.orEmpty(); cachedAudio = null
        selectedText = saved?.sourceText.orEmpty().take(ChatPrompt.MAX_SOURCE_CHARS)
        evidence = "未附媒体"
        status = when {
            saved == null -> "新会话"
            transcript.isNotBlank() -> "已恢复对话与原始转写；追问复用转写文字"
            sourceText.isNotBlank() -> "已恢复对话与选中文字；追问复用原文资料"
            else -> "已恢复文字记录；追问时请选择图片上下文方式"
        }
        notifyChanged()
    }
    fun snapshot(): StoredConversation {
        val pending = if (question.isNotBlank()) listOf(ChatMessage("user", question, evidence)) else emptyList()
        val all = messages.toList() + pending
        return StoredConversation(id, updatedAt, title, all, evidence, transcript, kind, status, sourceText)
    }
    private fun archive() {
        updatedAt = maxOf(System.currentTimeMillis(), updatedAt + 1)
        runCatching { saveHistory(snapshot()) }.onFailure { status += " · 本地历史保存失败" }
    }
    fun close() { if (busy) cancel(); epoch++; cancellation?.cancel(); observer = null; worker.shutdownNow() }
    private fun audioKey(file: File, config: AppConfig): String {
        val target = ProviderConnections.transcription(config)
        return "${file.absolutePath}:${file.length()}:${file.lastModified()}:${target.connectionId}:${target.endpointId}:${target.credentialId}:${target.baseUrl}:${target.apiProtocol}:${target.transcriptionModel}:${target.credentialRevision}:${target.transcriptionPrompt}:${target.transcriptionLanguage}"
    }
    private fun cancelled(e: Exception) = e is java.util.concurrent.CancellationException || e is InterruptedException
    private fun encodeImage(bitmap: Bitmap): String {
        val ratio = minOf(1.0, 2400.0 / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val bytes = ByteArrayOutputStream().use { stream ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "无法处理图片" }
                stream.toByteArray()
            }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }
    private companion object {
        const val AUDIO_TRANSLATION_QUESTION = "请将以下音频转写内容翻译成中文，忠实保留原意，仅输出译文，不添加总结、分析或解读。"

        fun credentialValidator(context: Context): (AppConfig, Boolean, Boolean) -> Boolean = { snapshot, answer, audio ->
            kotlin.runCatching { ProviderConnections.credentialsCurrent(snapshot, SettingsStore(context).load(), answer, audio) }.getOrDefault(false)
        }

        fun androidDispatcher(): (() -> Unit) -> Unit {
            val main = Handler(Looper.getMainLooper())
            return { action -> main.post(action); Unit }
        }
    }
}
