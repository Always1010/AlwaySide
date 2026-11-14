package com.alwayside.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** The bubble dispatches actions. Each evidence type owns a separate compact conversation. */
class AssistantService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private lateinit var preferences: InteractionPreferences
    private lateinit var uiContext: MutableContextWrapper
    private var appearancePending = false
    private var lastConfiguration = Configuration()
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { requestAppearanceRefresh() }
    }
    private var config = InteractionConfig()
    private val nodes = linkedMapOf<String, FloatingConversation>()
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private val recordingGate = RecordingGate()
    private var alive = true
    private var bubble: FrameLayout? = null
    private var bubbleVisual: AssistantBubbleView? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var bubbleTouch: BubbleTouchController? = null
    private var gestureHints: BubbleGestureHintsView? = null
    private var menu: View? = null
    private var menuBlurListener: java.util.function.Consumer<Boolean>? = null
    private val apiPreferences by lazy { getSharedPreferences("assistant_settings", MODE_PRIVATE) }
    private val apiPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post {
            if(alive) {
                val default=runCatching { SettingsStore(this).load().webSearch }.getOrDefault(false)
                nodes.values.forEach { node -> node.searchDefault=default;updateSearch(node) }
            }
        }
    }
    private var selector: View? = null
    private var projection: MediaProjection? = null
    private var frames: FrameSource? = null
    private var gathering = false
    private var captureEpoch = 0
    private var captureOwner: FloatingConversation? = null
    private var restoreCaptureWindows: (() -> Unit)? = null
    private var recorder: PlaybackRecorder? = null
    private var pendingRecordingStart: Runnable? = null
    private var recordingStartAt = 0L
    // Use recording bindings while waiting, so end/cancel gestures can revoke the start.
    private val recordingActive: Boolean get() = recorder != null || pendingRecordingStart != null
    private val recordingScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A delay must not survive sleep and unexpectedly start recording on wake.
            if (intent?.action == Intent.ACTION_SCREEN_OFF && pendingRecordingStart != null) cancelRecording(false)
        }
    }
    private var finishingAudio = false
    private var audioManual = false
    private var audioForPreview = false
    private var overlaysSuspended = false
    private var previewWindows = emptyList<Triple<FloatingConversation, AssistantConversationWindow, WindowManager.LayoutParams>>()
    private var previewSelector: Pair<View, WindowManager.LayoutParams>? = null
    private val deferredPreviewWindows = linkedMapOf<String, Pair<FloatingConversation, Boolean>>()
    private val previewListener: () -> Unit = {
        if (AssistantPreview.overlays.active) suspendPreviewWindows()
        else if (overlaysSuspended) restorePreviewWindows()
    }
    private val capturePermissionGate = CapturePermissionGate()
    private val requestingConsent: Boolean get() = capturePermissionGate.isPending
    private val playback by lazy { ChatAudioPlayback.get(this) }
    private val subtitlePreferences by lazy { SubtitlePreferences(this) }
    private val subtitleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { if(alive)nodes.values.toList().forEach { refresh(it) } }
    }
    private var lastKind = "photo"
    private val taps = TapSequence()
    private var burstRecording = false
    private var burstConfig = InteractionConfig()
    private var lastFinished: String? = null
    private val settleTaps = Runnable {
        val count = taps.settle()
        if (count > 0 && alive) {
            if (recordingActive != burstRecording) toast("录音状态已改变，请重新操作")
            else dispatchAction(burstConfig.gestureAction(BubbleGesture.taps(count), burstRecording))
        }
    }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = preferences.load()
        if (updated != config) {
            val readingChanged=updated.chatPointReading!=config.chatPointReading || updated.imagePointReading!=config.imagePointReading
            config = updated
            cancelGestures()
            removeMenu()
            removeBubble(); showBubble()
            if(readingChanged)nodes.values.toList().forEach { refresh(it) }
        }
    }
    private val captureCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!alive) return
            clearCapture()
            ProbeState.lastEvent = "屏幕共享已结束，下次截图或录音时会重新申请"
            toast(ProbeState.lastEvent)
            foreground(false)
            showBubble()
        }
        override fun onCapturedContentResize(width: Int, height: Int) { frames?.resize(width, height) }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            applyPendingAppearance()
            bubbleVisual?.recording = recorder != null
            bubbleVisual?.working = pendingRecordingStart != null || nodes.values.any { it.chat.busy }
            bubbleVisual?.invalidate()
            val recording = recordingActive
            val waiting = pendingRecordingStart != null
            val delayStatus = if (waiting) String.format(Locale.getDefault(), "%.1f 秒后开始录音",
                ((recordingStartAt - SystemClock.uptimeMillis()).coerceAtLeast(0L) + 99) / 100 / 10.0) else ""
            bubble?.contentDescription = (if(waiting)"AlwaySide，$delayStatus。" else if(recording)"AlwaySide 正在录音。" else "AlwaySide。") +
                BubbleGesture.entries.filter { !it.directional || config.longPressSlide }.joinToString("，") { gesture ->
                    "${gesture.title}：${GestureBindings.title(config.gestureAction(gesture, recording))}"
                }
            val current = recorder
            if (waiting) ProbeState.lastEvent = delayStatus
            if (current != null) ProbeState.lastEvent = String.format(Locale.getDefault(), "正在听 %.1f 秒%s", current.seconds, if (finishingAudio) "，正在结束" else "")
            main.postDelayed(this, 250)
        }
    }

    private inner class FloatingConversation(val kind: String) {
        lateinit var chat: ChatSession
        var frame: CapturedFrame? = null
        var originalFrame: CapturedFrame? = null
        var bounds: PixelCrop? = null
        var sourceSize: Pair<Int, Int>? = null
        var rotation: Int? = null
        var clip: AudioClip? = null
        var awaitingAudioApproval = false
        var audioApproval: View? = null
        var imageMode = 0
        var searchDefault = false
        var apiSnapshot: AppConfig? = null
        val suggestionViews = mutableMapOf<String, SearchSuggestionsView>()
        var webSearchOverride: Boolean? = null
        var draft = ""
        var composerExpanded = false
        var pinned = false
        var automaticFirstQuestion = false
        var originalExpanded = false
        var detached = false
        var openingInApp = false
        var discarding = false
        var root: AssistantConversationWindow? = null
        var layout: WindowManager.LayoutParams? = null
        var response: LinearLayout? = null
        var status: TextView? = null
        var requestNotice: AssistantRequestNoticeView? = null
        var source: TextView? = null
        var sourceImage: ImageView? = null
        var transcript: TextView? = null
        var transcriptCopy: View? = null
        var input: TextInputEditText? = null
        var composer: View? = null
        var composerToggle: MaterialButton? = null
        var send: View? = null
        var cancel: View? = null
        var retry: View? = null
        var copy: View? = null
        var discard: View? = null
        var pin: MaterialButton? = null
        var favorite: MaterialButton? = null
        var isFavorite = false
        var imageChoice: MaterialButton? = null
        var search: MaterialButton? = null
        var scroll: ScrollView? = null
        var content: LinearLayout? = null
        var readingBar: ReadingBar? = null
        var readingPresentation: AssistantWindowViews? = null
        var readingImageDialog: androidx.appcompat.app.AlertDialog? = null
        var imageReadingBound = false
        init {
            chat = ChatSession(this@AssistantService) { refresh(this) }
            chat.newConversation(kind = kind)
            pinned = config.defaultPinned
            apiSnapshot = runCatching { SettingsStore(this@AssistantService).load() }.getOrNull()
            searchDefault = apiSnapshot?.webSearch ?: false
            webSearchOverride = config.menuWebSearch
            imageMode = if (kind == "photo" && runCatching { SettingsStore(this@AssistantService).load().autoUpdateImage }.getOrDefault(false)) 1 else if (kind == "audio") 2 else 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        uiContext = MutableContextWrapper(AppAppearance.themedContext(this))
        lastConfiguration = Configuration(resources.configuration)
        AppAppearance.addListener(this, appearanceListener)
        windows = getSystemService(WindowManager::class.java)
        preferences = InteractionPreferences(this)
        config = preferences.load()
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recordingScreenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(recordingScreenReceiver, screenFilter)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮助手与采集", NotificationManager.IMPORTANCE_LOW))
        preferences.addListener(preferencesListener)
        subtitlePreferences.addListener(subtitleListener)
        apiPreferences.registerOnSharedPreferenceChangeListener(apiPreferencesListener)
        ActiveConversationBridge.detachHandler = ::detachToApplication
        AssistantPreview.finishRecording = {
            if (recorder != null) { audioForPreview = true; finishRecording(true) }
        }
        AssistantPreview.overlays.addListener(previewListener)
        ProbeState.serviceRunning = true
        cacheDir.listFiles()?.filter { it.name.startsWith("listening-") && it.extension == "wav" && !ActiveConversationBridge.isAudioRetained(it) && !ChatAudioPlayback.isUsingFile(it) }?.forEach { it.delete() }
        main.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            AssistantControl.setEnabled(this, false)
            cancelRecording(false)
            stopSelf(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val permissionResponse = intent?.action == SHARE ||
                (intent?.action == START && intent.hasExtra(CapturePermissionGate.EXTRA_REQUEST_ID))
            if (permissionResponse && !AssistantControl.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
            val permissionRequest = if (permissionResponse)
                capturePermissionGate.consume(intent?.getStringExtra(CapturePermissionGate.EXTRA_REQUEST_ID)) else null
            if (permissionResponse && permissionRequest == null) {
                // Delayed replies must not reuse consent belonging to a previous request or service.
                foreground(projection != null);showBubble();return START_NOT_STICKY
            }
            if (intent?.action == START || intent?.action == TEXT) AssistantControl.setEnabled(this, true)
            if (AssistantPreview.overlays.active) { foreground(projection != null); return START_NOT_STICKY }
            foreground(intent?.action == SHARE || projection != null)
            if (intent?.action == SHARE && projection == null) {
                @Suppress("DEPRECATION")
                val consent: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java) else intent.getParcelableExtra("consent")
                if (consent != null) {
                    val acquired = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("resultCode", 0), consent)
                        ?: error("没有取得屏幕共享授权")
                    projection = acquired
                    acquired.registerCallback(captureCallback, main)
                    val size = screenSize()
                    frames = FrameSource(acquired, size.first, size.second, resources.displayMetrics.densityDpi, main)
                    ProbeState.sharing = true
                    ProbeState.lastEvent = "已共享屏幕，返回目标应用后使用小球"
                }
            }
            showBubble()
            val requestedAction=if(permissionRequest!=null)intent?.getStringExtra("requestedAction") else null
            val continuation=permissionRequest?.continuation
            if(requestedAction in setOf(AssistantActions.SCREEN,AssistantActions.CROP,AssistantActions.AUDIO,AssistantActions.START_AUDIO)) {
                // The permission bridge delivers only as it leaves, with its close animation disabled.
                // A screenshot additionally waits for two compositor frames before sampling a fresh frame.
                main.post { if(alive) { if(continuation!=null)continuation() else dispatchAction(requestedAction!!) } }
            }
            if (intent?.action == TEXT) {
                val selected = intent.getStringExtra("selectedText").orEmpty().trim().take(12000)
                if(selected.isNotBlank()) {
                    val node=fresh("text");node.chat.setSourceText(selected);showPrepared(node,config.autoAsk("text"))
                } else toast("没有取得选中的文字")
            }
            if (intent?.action == "resume_history") {
                val id = intent.getStringExtra("historyId")
                if (id != null) startActivity(AppEntry.intent(this,AppPage.CHAT,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            ProbeState.lastEvent = "开启失败：${e.javaClass.simpleName}"
            toast("无法开启助手，请检查悬浮与屏幕共享权限")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun foreground(capturing: Boolean) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle(if (capturing) "AlwaySide · 屏幕共享中" else "AlwaySide · 悬浮球已开启")
            .setContentText("点击进入对话与设置；录音可通过配置的手势取消")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            else if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        startForeground(100, notification, type)
    }
    private fun params(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE), PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
    }
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    private fun remove(view: View?) { if (view != null) runCatching { windows.removeView(view) } }
    private fun removeBubble() {
        cancelGestures(); bubbleTouch?.dispose(); bubbleTouch = null; removeGestureHints()
        remove(bubble); bubble = null; bubbleVisual = null; bubbleLayout = null
    }
    private fun removeMenu() {
        if(Build.VERSION.SDK_INT>=31)menuBlurListener?.let { runCatching { windows.removeCrossWindowBlurEnabledListener(it) } }
        menuBlurListener=null
        remove(menu);menu=null
    }
    private fun removeSelector() { remove(selector); selector = null }
    private fun installOverlayBack(view:View,onBack:()->Unit) {
        view.isFocusableInTouchMode=true
        view.setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP)onBack();true } else false
        }
        if(Build.VERSION.SDK_INT>=33)view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            private var dispatcher:android.window.OnBackInvokedDispatcher?=null
            private val back=android.window.OnBackInvokedCallback { onBack() }
            override fun onViewAttachedToWindow(root:View) {
                dispatcher=root.findOnBackInvokedDispatcher()
                dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,back)
            }
            override fun onViewDetachedFromWindow(root:View) { dispatcher?.unregisterOnBackInvokedCallback(back);dispatcher=null }
        })
    }
    private fun cancelGestures() { main.removeCallbacks(settleTaps); taps.cancel(); bubbleTouch?.cancel(); removeGestureHints() }

    private fun removeGestureHints(animated: Boolean = false) {
        val hints = gestureHints ?: return
        if (animated) hints.hideAnimated {
            if (gestureHints === hints) { remove(hints); gestureHints = null }
        } else { remove(hints); gestureHints = null }
    }

    private fun constrainBubbleY(y: Int, height: Int): Int {
        val maxTop = (height - dp(48)).coerceAtLeast(0)
        if (!config.longPressSlide) {
            val minimum = dp(8).coerceAtMost(maxTop)
            return y.coerceIn(minimum, (height-dp(100)).coerceIn(minimum, maxTop))
        }
        // Leave room for the same physical up/down directions, even with the largest bubble.
        val inset = dp(config.actionCircleDistanceDp + 28) - dp(24)
        val minimum = inset.coerceIn(0, maxTop / 2)
        val maximum = (maxTop - inset).coerceIn(minimum, maxTop)
        return y.coerceIn(minimum, maximum)
    }

    private fun directionHint(snapshot: InteractionConfig, gesture: BubbleGesture, recording: Boolean): BubbleGestureHint {
        val action = snapshot.gestureAction(gesture, recording)
        val other = snapshot.gestureAction(gesture, !recording)
        val shown = if (action == AssistantActions.NONE) other else action
        val icon = when (shown) {
            AssistantActions.SCREEN -> R.drawable.ic_line_scan
            AssistantActions.CROP -> R.drawable.ic_line_crop
            AssistantActions.START_AUDIO -> R.drawable.ic_line_audio
            AssistantActions.END_AUDIO -> R.drawable.ic_line_stop
            AssistantActions.CANCEL_AUDIO -> R.drawable.ic_line_trash
            AssistantActions.MENU -> R.drawable.ic_line_menu
            else -> null
        }
        val reason = if (action != AssistantActions.NONE || icon == null) null
            else if (recording) "录音中不可用" else "当前未录音"
        return BubbleGestureHint(if (icon == null) "未设置" else GestureBindings.title(shown), icon, reason)
    }

    private fun showGestureHints(snapshot: InteractionConfig, recording: Boolean) {
        if (AssistantPreview.overlays.active) return
        removeGestureHints()
        val ball = bubbleLayout ?: return
        val size = screenSize()
        val left = ball.x + ball.width / 2 < size.first / 2
        val hints = BubbleGestureHintsView(uiContext,
            listOf(BubbleGesture.UP, BubbleGesture.DOWN, BubbleGesture.INWARD).associateWith { directionHint(snapshot, it, recording) },
            left, GestureBindings.title(snapshot.gestureAction(BubbleGesture.HOLD, recording)), snapshot)
        hints.measure(View.MeasureSpec.makeMeasureSpec((size.first - dp(16)).coerceAtLeast(dp(48)), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((size.second - dp(16)).coerceAtLeast(dp(48)), View.MeasureSpec.AT_MOST))
        val layout = params(hints.measuredWidth, hints.measuredHeight, false).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = (ball.x + ball.width / 2 - hints.anchorX).coerceIn(0, (size.first - width).coerceAtLeast(0))
            y = (ball.y + ball.height / 2 - hints.anchorY).coerceIn(0, (size.second - height).coerceAtLeast(0))
        }
        hints.alignAnchor(ball.x + ball.width / 2 - layout.x, ball.y + ball.height / 2 - layout.y)
        try { windows.addView(hints, layout); gestureHints = hints; hints.showAnimated() }
        catch (_: RuntimeException) { remove(hints) }
    }

    private fun requestAppearanceRefresh() {
        appearancePending = true
        applyPendingAppearance()
    }

    /** Rebuild only the views, retaining capture, recording and conversation owners. */
    private fun applyPendingAppearance() {
        // A new overlay during snapshot's two hidden compositor frames would contaminate the image.
        // Keep a live crop selector intact too; its next completed/cancelled action applies the change.
        if (!appearancePending || !alive || AssistantPreview.overlays.active || gathering || selector != null || requestingConsent) return
        appearancePending = false
        uiContext.baseContext = AppAppearance.themedContext(this)
        val hadMenu = menu != null
        val ball = bubbleLayout?.let { WindowManager.LayoutParams().apply { copyFrom(it) } }
        val recentKind = lastKind
        val shown = nodes.values.filter { it.root != null }.map { node ->
            val layout = WindowManager.LayoutParams().apply { copyFrom(node.layout!!) }
            Triple(node, layout, node.scroll?.scrollY ?: 0)
        }
        removeMenu(); removeBubble()
        shown.forEach { (node, layout, scrollY) ->
            val selectionStart = node.input?.selectionStart ?: -1
            val selectionEnd = node.input?.selectionEnd ?: -1
            val focused = node.input?.hasFocus() == true
            val imageVisible = node.sourceImage?.visibility == View.VISIBLE
            val transcriptVisible = node.transcript?.visibility == View.VISIBLE
            showWindow(node, layout)
            node.sourceImage?.visibility = if (imageVisible) View.VISIBLE else View.GONE
            node.transcript?.visibility = if (transcriptVisible) View.VISIBLE else View.GONE
            updateTranscriptCopy(node)
            node.input?.let { input ->
                if (selectionStart >= 0 && selectionEnd >= 0) input.setSelection(selectionStart.coerceAtMost(input.length()), selectionEnd.coerceAtMost(input.length()))
                if (focused) { node.root?.requestInputFocus(); input.requestFocus() }
            }
            node.scroll?.let { scroll -> scroll.post { if (node.scroll === scroll) scroll.scrollTo(0, scrollY) } }
        }
        lastKind = recentKind
        showBubble()
        if (ball != null) bubble?.let { view ->
            bubbleLayout?.let { layout -> layout.x = ball.x; layout.y = constrainBubbleY(ball.y, screenSize().second); runCatching { windows.updateViewLayout(view, layout) } }
        }
        if (hadMenu) showMenu(preserveWindows = true)
    }

    private fun suspendPreviewWindows() {
        if (!alive || overlaysSuspended) return
        overlaysSuspended = true
        // Invalidate pending real capture/consent and touch callbacks before displaying a simulation.
        capturePermissionGate.cancel(); captureEpoch++
        frames?.cancelSnapshot()
        cancelPendingRecordingStart()
        gathering = false; captureOwner = null; restoreCaptureWindows = null
        removeMenu(); removeBubble()
        nodes.values.forEach { playback.setVisible(it, null, false) }
        previewWindows = nodes.values.mapNotNull { node ->
            val root = node.root ?: return@mapNotNull null
            val layout = node.layout ?: return@mapNotNull null
            Triple(node, root, WindowManager.LayoutParams().apply { copyFrom(layout) })
        }
        previewSelector = selector?.let { view ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { layout ->
                view to WindowManager.LayoutParams().apply { copyFrom(layout) }
            }
        }
        previewWindows.forEach { (_, root, _) ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            root.clearFocus()
            root.detach()
        }
        previewSelector?.first?.let { root ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            root.clearFocus()
            if (root.isAttachedToWindow) windows.removeViewImmediate(root)
        }
    }

    private fun restorePreviewWindows() {
        overlaysSuspended = false
        val shown = previewWindows; previewWindows = emptyList()
        val crop = previewSelector; previewSelector = null
        val deferred = deferredPreviewWindows.values.toList(); deferredPreviewWindows.clear()
        if (!alive) return
        if (!AssistantControl.isEnabled(this) || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        shown.forEach { (node, root, layout) ->
            if (!node.detached && nodes[node.kind] === node && node.root === root && !root.isAttachedToWindow) {
                clamp(layout)
                layout.softInputMode = (layout.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                runCatching { root.attach(layout); node.layout = layout; playback.setVisible(node, node.chat.conversationId, true); refresh(node) }
                    .onFailure { hideWindow(node, false); toast("小窗恢复失败，可从悬浮球重新打开") }
            }
        }
        crop?.let { (root, layout) ->
            if (selector === root && !root.isAttachedToWindow) {
                runCatching { windows.addView(root, layout) }.onFailure { removeSelector() }
            }
        }
        deferred.forEach { (node, focusQuestion) ->
            if (!node.detached && nodes[node.kind] === node) showWindow(node, focusQuestion = focusQuestion)
        }
        applyPendingAppearance(); showBubble()
    }

    private fun showBubble() {
        if (!alive || AssistantPreview.overlays.active || bubble != null || selector != null || gathering || requestingConsent) return
        val size = screenSize()
        val position = preferences.ballPosition()
        val view = FrameLayout(uiContext).apply { isClickable = true; isFocusable = true; accessible("AlwaySide，快捷手势可在设置中修改") }
        val glyph = AssistantBubbleView(uiContext,config.ballSize,config.ballOpacity,config.ballColor).also { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val layout = params(dp(48), dp(48), false).apply {
            x = if (position.first) 0 else (size.first - dp(48)).coerceAtLeast(0)
            y = constrainBubbleY((position.second * size.second).toInt(), size.second)
        }
        var startX = layout.x; var startY = layout.y
        var touchRecording = recordingActive
        var touchConfig = config
        view.setOnClickListener { dispatchAction(config.action(1, recordingActive)) }
        view.setOnLongClickListener { dispatchAction(config.gestureAction(BubbleGesture.HOLD, recordingActive)); true }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            view.systemGestureExclusionRects = if (config.longPressSlide) listOf(Rect(0, 0, view.width, view.height)) else emptyList()
        }
        bubbleTouch = BubbleTouchController(view, glyph, config = {
            main.removeCallbacks(settleTaps)
            config = preferences.load(); touchConfig = config; touchRecording = recordingActive
            startX = layout.x; startY = layout.y
            config
        }, onLeft = { layout.x + layout.width / 2 < size.first / 2 }, onGesture = { gesture ->
            if (alive && bubble === view) {
                if (touchRecording != recordingActive) {
                    cancelGestures(); toast("录音状态已改变，请重新操作")
                } else if (gesture == BubbleGesture.SINGLE) {
                    if (taps.pending && burstRecording != touchRecording) taps.cancel()
                    val count = taps.tap(SystemClock.uptimeMillis())
                    if (count == 1) { burstRecording = touchRecording; burstConfig = touchConfig }
                    if (count == 3) settleTaps.run() else main.postDelayed(settleTaps, taps.gapMillis)
                } else {
                    cancelGestures()
                    val action = touchConfig.gestureAction(gesture, touchRecording)
                    if (action != AssistantActions.NONE) dispatchAction(action)
                }
            }
        }, onMove = { dx, dy ->
            main.removeCallbacks(settleTaps); taps.cancel(); removeMenu()
            if (bubble === view) {
                layout.x = (startX + dx).toInt().coerceIn(0, (size.first - layout.width).coerceAtLeast(0))
                layout.y = constrainBubbleY((startY + dy).toInt(), size.second)
                runCatching { windows.updateViewLayout(view, layout) }
            }
        }, onMoveEnd = {
            layout.x = if (layout.x + layout.width / 2 < size.first / 2) 0 else (size.first - layout.width).coerceAtLeast(0)
            preferences.saveBall(layout.x == 0, layout.y.toFloat() / size.second)
            if (bubble === view) runCatching { windows.updateViewLayout(view, layout) }
        }, onArmedChanged = { armed ->
            if (armed) { main.removeCallbacks(settleTaps); taps.cancel(); showGestureHints(touchConfig, touchRecording) }
            else removeGestureHints(animated = true)
        }, onPreview = { preview -> gestureHints?.preview(preview) },
            canSelect = { directionHint(touchConfig, it, touchRecording).available }, onCancel = {
            main.removeCallbacks(settleTaps); taps.cancel(); removeGestureHints()
        })
        try { windows.addView(view, layout); bubble = view; bubbleVisual = glyph; bubbleLayout = layout }
        catch (_: RuntimeException) { toast("悬浮权限不可用，请从应用重新开启"); stopSelf() }
    }
    private fun dispatchAction(action: String) {
        if (!alive || AssistantPreview.overlays.active) return
        removeMenu()
        when (action) {
            AssistantActions.SCREEN -> captureNew(false)
            AssistantActions.CROP -> captureNew(true)
            AssistantActions.AUDIO -> if (recordingActive) finishRecording(false) else startRecording()
            AssistantActions.START_AUDIO -> if (recorder == null) startRecording() else toast("已经在录音中")
            AssistantActions.END_AUDIO -> if (recordingActive) finishRecording(false) else toast("当前没有录音")
            AssistantActions.CANCEL_AUDIO -> cancelRecording(true)
            AssistantActions.MENU -> showMenu()
            AssistantActions.LAST -> nodes[lastKind]?.let { showWindow(it) } ?: toast("还没有悬浮会话，先截图或听一段")
            AssistantActions.HISTORY -> openApplication(AppPage.HISTORY)
            AssistantActions.SETTINGS -> openApplication(AppPage.SETTINGS)
        }
    }
    private fun openApplication(page: AppPage) {
        hideUnpinned()
        startActivity(AppEntry.intent(this,page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun requestCaptureConsent(action: String, continuation: (() -> Unit)? = null) {
        if(!alive || AssistantPreview.overlays.active || requestingConsent)return
        val requestId=capturePermissionGate.begin(continuation)
        removeMenu();hideUnpinned()
        try {
            // Keep the visible overlay until startActivity: Android permits this user-initiated
            // launch through SYSTEM_ALERT_WINDOW only while an overlay is actually visible.
            startActivity(Intent(this,CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("requestedAction",action).putExtra("needsCapture",projection==null)
                .putExtra(CapturePermissionGate.EXTRA_REQUEST_ID,requestId))
            removeBubble()
        } catch(_:RuntimeException) {
            capturePermissionGate.cancel();showBubble();toast("无法打开系统授权，请返回应用后重试")
        }
    }
    private fun showMenu(preserveWindows: Boolean = false) {
        if (AssistantPreview.overlays.active || gathering || selector != null) return
        if (!preserveWindows) hideUnpinned()
        removeMenu(); showBubble()
        val body = OutsideFrame { removeMenu() }.apply {
            setOnClickListener { removeMenu() }
        }
        installOverlayBack(body,::removeMenu)
        val menuApi=runCatching { SettingsStore(this).load() }.getOrNull()
        val defaultSearch=menuApi?.webSearch ?: false
        val presentation = AssistantMenuViews(uiContext,recordingActive,nodes["audio"]?.clip != null,
            recordingPending=pendingRecordingStart != null,
            webSearch=config.menuWebSearch ?: defaultSearch,useDefaultQuestion=config.useDefaultQuestion,
            webSearchAvailable=menuApi?.let(ProviderCapabilities::search) ?: false,
            items=config.menuItems,appearance=config.menuAppearance,
            onCustomize={
                removeMenu();hideUnpinned()
                startActivity(AppEntry.intent(this,AppPage.SETTINGS,settingsPage="MENU").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onWebSearchChanged={saveMenuConfig(config.copy(menuWebSearch=it))},
            onDefaultQuestionChanged={saveMenuConfig(config.copy(useDefaultQuestion=it))}) { action ->
            removeMenu()
            when(action) {
                AssistantMenuAction.SCREEN -> captureNew(false)
                AssistantMenuAction.AUDIO -> if(recordingActive)finishRecording(false) else startRecording()
                AssistantMenuAction.CANCEL_AUDIO -> cancelRecording(true)
                AssistantMenuAction.CROP -> captureNew(true)
                AssistantMenuAction.LAST -> dispatchAction(AssistantActions.LAST)
                AssistantMenuAction.PLAY -> playClip()
                AssistantMenuAction.HISTORY -> openApplication(AppPage.HISTORY)
                AssistantMenuAction.SETTINGS -> openApplication(AppPage.SETTINGS)
                AssistantMenuAction.APPEARANCE -> {
                    hideUnpinned()
                    startActivity(AppEntry.intent(this,AppPage.SETTINGS,settingsPage="MENU_APPEARANCE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                AssistantMenuAction.STOP -> AssistantControl.stop(this)
            }
        }
        val items=presentation.view
        val size=screenSize(); val ball=bubbleLayout
        val w=minOf(dp(presentation.preferredWidthDp),size.first-dp(16))
        items.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val h=minOf(items.measuredHeight,size.second-dp(100))
        val menuX=(if ((ball?.x ?: 0)<size.first/2)dp(47) else size.first-w-dp(47))
            .coerceIn(dp(8),(size.first-w-dp(8)).coerceAtLeast(dp(8)))
        val menuY=((ball?.y ?: dp(120))-dp(50)).coerceIn(dp(8),(size.second-h-dp(60)).coerceAtLeast(dp(8)))
        body.addView(ScrollView(uiContext).apply {
            isVerticalScrollBarEnabled=false;isFillViewport=true;addView(items)
            background=card(android.graphics.Color.TRANSPARENT,dp(config.menuAppearance.cornerRadiusDp)*config.menuAppearance.scale)
            clipToOutline=true;elevation=0f
        },FrameLayout.LayoutParams(w,h,Gravity.TOP or Gravity.START).apply { leftMargin=menuX;topMargin=menuY })
        val layout=params(-1,-1,true).apply {
            flags=flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        fun updateBackdrop(blur:Boolean) {
            val appearance=config.menuAppearance
            val effectiveBlur=blur && appearance.blurStrength>0
            layout.dimAmount=appearance.effectiveDimPercent(blur)/100f
            if(Build.VERSION.SDK_INT>=31) {
                layout.flags=if(effectiveBlur)layout.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    else layout.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                layout.setBlurBehindRadius(if(effectiveBlur)(appearance.blurRadiusDp*resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0)
            }
            if(menu===body)runCatching { windows.updateViewLayout(body,layout) }
        }
        updateBackdrop(Build.VERSION.SDK_INT>=31 && runCatching { windows.isCrossWindowBlurEnabled }.getOrDefault(false))
        try {
            windows.addView(body,layout);menu=body;body.requestFocus()
            if(Build.VERSION.SDK_INT>=31) {
                val listener=java.util.function.Consumer<Boolean> { enabled -> if(menu===body)updateBackdrop(enabled) }
                menuBlurListener=listener
                runCatching { windows.addCrossWindowBlurEnabledListener(mainExecutor,listener) }
            }
        } catch (_: RuntimeException) { removeMenu();toast("无法打开菜单") }
    }
    private fun saveMenuConfig(updated:InteractionConfig) {
        // Publish first so the preferences listener leaves this still-open menu in place.
        config=updated
        runCatching { preferences.save(updated) }.onFailure {
            config=preferences.load();toast("无法保存菜单选项，请重试");showMenu(preserveWindows=true)
        }
    }
    private fun fresh(kind: String): FloatingConversation {
        nodes.remove(kind)?.let { old ->
            hideWindow(old)
            if (old.chat.busy) { old.chat.cancel(); toast("已停止上一条同类请求，开始新会话") }
            old.chat.close(); old.clip?.file?.let { ActiveConversationBridge.releaseAudio(it); ChatAudioPlayback.deleteTemporaryAudio(it) }
        }
        return FloatingConversation(kind).also { nodes[kind]=it;lastKind=kind }
    }
    private fun hideUnpinned(except: FloatingConversation?=null) { nodes.values.filter { it!==except && !it.pinned }.forEach { hideWindow(it) } }
    private fun hideWindow(node: FloatingConversation, stopAudio: Boolean = true) {
        node.readingImageDialog?.dismiss();node.readingImageDialog=null
        node.root?.let { if(stopAudio)getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0);runCatching { it.detach() } }
        node.suggestionViews.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() };node.suggestionViews.clear()
        node.root=null;node.layout=null;node.response=null;node.status=null;node.requestNotice=null;node.source=null;node.sourceImage=null;node.transcript=null
        node.transcriptCopy=null
        node.audioApproval=null
        node.input=null;node.send=null;node.cancel=null;node.retry=null;node.copy=null;node.discard=null;node.pin=null;node.favorite=null;node.imageChoice=null;node.search=null;node.scroll=null;node.content=null
        node.composer=null;node.composerToggle=null
        node.readingBar=null;node.readingPresentation=null
        node.imageReadingBound=false
        playback.setVisible(node, null, false, retaining = !stopAudio)
    }
    private fun icon(resource: Int, description: String, run: () -> Unit) = uiContext.iconButton(resource,description,run)
    private fun showWindow(node: FloatingConversation, restoredLayout: WindowManager.LayoutParams? = null, focusQuestion: Boolean = false) {
        if (!alive || node.detached || gathering) return
        if (AssistantPreview.overlays.active) { deferredPreviewWindows[node.kind] = node to focusQuestion; return }
        removeMenu();removeSelector();if(restoredLayout==null)hideUnpinned(node);hideWindow(node,stopAudio=restoredLayout==null);lastKind=node.kind
        config=preferences.load()
        // Theme/configuration rebuilds retain the current state; a fresh invocation uses the preference.
        if(restoredLayout==null)node.composerExpanded=focusQuestion||!config.collapseFollowUpByDefault
        val size=screenSize();val saved=preferences.window(node.kind)
        val width=minOf(dp(if(config.rememberWindowSize)saved?.widthDp ?: 320 else 320),size.first-dp(24)).coerceAtLeast(dp(180))
        val defaultHeight=uiContext.assistantWindowHeightDp()
        val height=minOf(dp(if(config.rememberWindowSize)saved?.heightDp ?: defaultHeight else defaultHeight),size.second-dp(100)).coerceAtLeast(dp(220))
        val layout=restoredLayout ?: params(width,height,true).apply {
            x=if(config.rememberWindowPosition&&saved!=null)((size.first-width)*saved.xFraction).toInt() else (size.first-width)/2
            y=if(config.rememberWindowPosition&&saved!=null)((size.second-height-dp(60))*saved.yFraction).toInt()+dp(8) else (size.second-height-dp(32))/2
        }
        clamp(layout)
        val root=AssistantConversationWindow(uiContext,node.pinned) { if(!node.pinned && node.readingImageDialog?.isShowing!=true) hideWindow(node) }.apply { background=outlinedCard(BACKGROUND,15);elevation=dp(8).toFloat();clipToOutline=true }
        root.isFocusableInTouchMode=true
        val presentation=AssistantWindowViews(uiContext,when(node.kind){"photo"->"截图问答";"audio"->"听一段";else->"文字问答"},
            node.kind=="photo",AssistantWindowActions(
                pin={node.pinned=!node.pinned;updatePin(node)},expand={openConversationInApp(node)},
                favorite={toggleFavorite(node)},
                discard={discardConversation(node)},
                toggle={setComposerExpanded(node,!node.composerExpanded,fromUser=true)},
                cancel={node.chat.cancel()},retry={if(!gathering&&!node.discarding)node.chat.retry()},
                copy={
                    val text=node.chat.partial.ifBlank { node.chat.messages.lastOrNull{it.role=="assistant"}?.text.orEmpty() }
                    if(text.isNotBlank()){getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide",text));toast("已复制")}
                },
                search={node.webSearchOverride=!(node.webSearchOverride ?: node.searchDefault);updateSearch(node)},
                send={sendQuestion(node,node.draft)},
            ))
        val body=presentation.body;val header=presentation.header;val sourceContent=presentation.sourceContent
        node.readingPresentation=presentation
        node.pin=presentation.pin;node.content=presentation.content;node.response=presentation.response;node.status=presentation.status
        node.favorite=presentation.favorite
        runCatching { HistoryStore(this).summary(node.chat.conversationId) }
            .onSuccess { node.isFavorite=it?.isFavorite==true }
            .onFailure { node.favorite?.isEnabled=false;toast("无法读取收藏状态，请重新打开小窗") }
        updateFavorite(node)
        node.requestNotice=AssistantRequestNoticeView(uiContext) { node.chat.dismissNotice() }.also {
            presentation.noticeSlot.addView(it,FrameLayout.LayoutParams(-1,-2).apply { setMargins(dp(8),0,dp(8),dp(4)) })
        }
        node.scroll=presentation.scroll;node.composerToggle=presentation.toggle;node.composer=presentation.composerSection
        node.cancel=presentation.cancel;node.retry=presentation.retry;node.copy=presentation.copy
        node.discard=presentation.discard
        node.imageChoice=presentation.imageChoice;node.search=presentation.search;node.send=presentation.send
        val resize=presentation.resize
        if(node.awaitingAudioApproval) {
            node.audioApproval=LinearLayout(uiContext).apply {
                gravity=Gravity.CENTER_VERTICAL
                action(if(config.useDefaultQuestion)"转写" else "补充问题",true) { approvePendingAudio(node) }.apply {
                    accessible(if(config.useDefaultQuestion)"确认转写本段录音，按音频默认输出处理" else "保留本段录音，补充问题后发送")
                }
                action("丢弃") { discardPendingAudio(node) }.apply { accessible("丢弃本段录音，不发起转写") }
            }.also { sourceContent.addView(it) }
        }
        node.source=uiContext.label("",11f,true).apply { minHeight=dp(28); gravity=Gravity.CENTER_VERTICAL;isClickable=true;accessible(when(node.kind){"photo"->"展开本次截图";"audio"->"展开转写原文";else->"展开所选原文"});setOnClickListener {
            if(node.kind=="photo")node.sourceImage?.let{it.visibility=if(it.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
            else if(node.kind=="text") {node.originalExpanded=!node.originalExpanded;node.transcript?.apply{maxLines=if(node.originalExpanded)Int.MAX_VALUE else 4;ellipsize=if(node.originalExpanded)null else android.text.TextUtils.TruncateAt.END}}
            else node.transcript?.let{it.visibility=if(it.visibility==View.VISIBLE)View.GONE else View.VISIBLE;updateTranscriptCopy(node)}
        }}.also{sourceContent.addView(it)}
        if(node.kind=="audio")sourceContent.addView(uiContext.iconButton(R.drawable.ic_line_subtitles,"在应用中打开音频字幕") {
            if(subtitlePreferences.anyEnabled())openConversationInApp(node,subtitles=true)
        }.apply {
            tag="audio_subtitles"
            visibility=if(subtitlePreferences.anyEnabled())View.VISIBLE else View.GONE
        },LinearLayout.LayoutParams(dp(40),dp(40)).apply { gravity=Gravity.END })
        node.sourceImage=ImageView(uiContext).apply { scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.GONE;accessible("本次截图预览");node.frame?.let{setImageBitmap(it.bitmap)} }.also{sourceContent.addView(it,LinearLayout.LayoutParams(-1,dp(110)))}
        node.transcript=uiContext.label("",12f,true).apply { visibility=View.GONE;enableTextActions();setLineSpacing(0f,1.3f);setPadding(0,dp(8),0,dp(8)) }.also{sourceContent.addView(it)}
        if(node.kind=="audio")node.transcriptCopy=icon(R.drawable.ic_copy,"复制转写原文") {
            val text=node.chat.transcript
            if(text.isNotBlank())runCatching {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide 转写原文",text))
            }.onSuccess { toast("已复制转写原文") }.onFailure { toast("复制失败，可长按文字选择复制") }
        }.apply {
            visibility=View.GONE;iconSize=dp(14)
            minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0
            setPadding(dp(9),0,dp(9),0)
        }.also { sourceContent.addView(it,LinearLayout.LayoutParams(dp(32),dp(32)).apply { gravity=Gravity.END }) }
        if(node.kind=="text")node.transcript?.apply{visibility=View.VISIBLE;maxLines=if(node.originalExpanded)Int.MAX_VALUE else 4;ellipsize=if(node.originalExpanded)null else android.text.TextUtils.TruncateAt.END}
        node.imageChoice?.apply {
            val choices=listOf("沿用本次截图","发送时更新画面","仅文字上下文")
            text=choices[node.imageMode];accessible("图片上下文：${choices[node.imageMode]}，点按选择")
            setOnClickListener { anchor ->
                PopupMenu(uiContext,anchor).apply {
                    choices.forEachIndexed { index,label -> menu.add(0,index,index,label).apply { isCheckable=true;isChecked=index==node.imageMode } }
                    setOnMenuItemClickListener { item ->
                        node.imageMode=item.itemId;text=choices[item.itemId]
                        accessible("图片上下文：${choices[item.itemId]}，点按选择");true
                    }
                }.show()
            }
        }
        node.input=presentation.input.apply {
            setText(node.draft);accessible("${when(node.kind){"photo"->"截图";"audio"->"音频";else->"文字"}}追问")
            onFocusChangeListener=View.OnFocusChangeListener { _,hasFocus -> if(hasFocus)node.root?.requestInputFocus() }
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){node.draft=s?.toString().orEmpty();updateComposerToggle(node);updateSendEnabled(node)}
                override fun afterTextChanged(s:Editable?)=Unit
            })
        }
        root.addView(body,FrameLayout.LayoutParams(-1,-1))
        setComposerExpanded(node,node.composerExpanded&&!node.awaitingAudioApproval)
        installDrag(node,header,root,layout,false);installDrag(node,resize,root,layout,true)
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener{_,insets->
            val ime=insets.getInsets(WindowInsets.Type.ime()).bottom
            if(ime>0&&node.root===root){val bottom=screenSize().second-ime-dp(12);val y=layout.y.coerceAtMost((bottom-layout.height).coerceAtLeast(dp(8)));if(y!=layout.y){layout.y=y;runCatching{root.update(layout)}}}
            insets
        }
        layout.softInputMode=(if(focusQuestion)WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        try{root.attach(layout);node.root=root;node.layout=layout;playback.setVisible(node,node.chat.conversationId,true);if(!node.composerExpanded)root.requestFocus();updatePin(node);refresh(node);if(focusQuestion)focusQuestionInput(node)}catch(_:RuntimeException){runCatching{root.detach()};toast("无法打开结果窗口，请检查悬浮权限")}
        removeBubble();showBubble()
    }
    private fun setComposerExpanded(node:FloatingConversation,expanded:Boolean,fromUser:Boolean=false) {
        if(node.discarding)return
        node.composerExpanded=expanded
        if(!expanded) {
            node.input?.clearFocus()
            if(fromUser)node.root?.let { root ->
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
                root.requestFocus()
                root.releaseInputFocus()
            }
        }
        node.composer?.visibility=if(expanded)View.VISIBLE else View.GONE
        updateComposerToggle(node)
        if(expanded&&fromUser)focusQuestionInput(node)
    }
    private fun focusQuestionInput(node:FloatingConversation) {
        val input=node.input ?: return
        node.root?.requestInputFocus()
        input.requestFocus();input.setSelection(input.text?.length ?: 0)
        fun showKeyboard() {
            input.post {
                if(node.input===input&&node.root?.isAttachedToWindow==true&&node.composerExpanded&&input.hasFocus()&&input.hasWindowFocus())
                    getSystemService(InputMethodManager::class.java).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)
            }
        }
        if(input.hasWindowFocus())showKeyboard() else {
            input.viewTreeObserver.addOnWindowFocusChangeListener(object:ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus:Boolean) {
                    if(hasFocus) {
                        if(input.viewTreeObserver.isAlive)input.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        showKeyboard()
                    }
                }
            })
        }
    }
    private fun updateComposerToggle(node:FloatingConversation) {
        node.composerToggle?.setAssistantComposerState(node.composerExpanded,node.draft.isNotBlank())
    }
    private fun updateSendEnabled(node:FloatingConversation) {
        node.send?.isEnabled=node.draft.isNotBlank()&&!node.discarding&&!node.awaitingAudioApproval&&!node.chat.busy&&!gathering&&!recordingActive
    }
    private fun updatePin(node:FloatingConversation){node.pin?.apply{
        icon=uiContext.prototypeIcon(if(node.pinned)R.drawable.ic_pin_filled else R.drawable.ic_line_pin)
        isCheckable=true;isChecked=node.pinned
        iconTint=android.content.res.ColorStateList.valueOf(if(node.pinned)ACCENT else MUTED)
        accessible(if(node.pinned)"取消固定，之后点外部收起" else "固定窗口，点外部保持显示")
    };node.root?.setPinned(node.pinned)}
    private fun updateFavorite(node:FloatingConversation){node.favorite?.apply{
        icon=uiContext.prototypeIcon(if(node.isFavorite)R.drawable.ic_star_filled else R.drawable.ic_line_star)
        isCheckable=true;isChecked=node.isFavorite
        iconTint=android.content.res.ColorStateList.valueOf(if(node.isFavorite)ACCENT else MUTED)
        accessible(if(node.isFavorite)"取消收藏" else "收藏会话")
    }}
    private fun toggleFavorite(node:FloatingConversation){
        if(!alive||node.detached||node.discarding)return
        if(node.awaitingAudioApproval){toast("请先保留或丢弃本段录音");return}
        runCatching {
            val store=HistoryStore(this)
            var stored=store.summary(node.chat.conversationId)
            if(stored==null){store.save(node.chat.snapshot());stored=store.summary(node.chat.conversationId)}
            val favorite=stored?.isFavorite!=true
            store.setFavorite(node.chat.conversationId,favorite)
            node.isFavorite=favorite
        }.onSuccess {
            updateFavorite(node)
            toast(if(node.isFavorite)"已收藏" else "已取消收藏")
        }.onFailure { toast(it.message?.take(120) ?: "无法保存收藏，请重试") }
    }
    private fun openConversationInApp(node:FloatingConversation,subtitles:Boolean=false){
        if(!alive||gathering||node.detached||node.openingInApp||node.discarding)return
        if(node.awaitingAudioApproval){toast("请先选择转写或丢弃本段录音");return}
        node.openingInApp=true
        // Keep the visible overlay until startActivity so the user-initiated launch is allowed.
        try {
            startActivity(AppEntry.intent(this,AppPage.CHAT,node.chat.conversationId,openSubtitles=subtitles).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed({if(!node.detached)node.openingInApp=false},1500)
        } catch(_:RuntimeException){node.openingInApp=false;toast("无法打开应用聊天，请稍后重试")}
    }
    private fun updateSearch(node:FloatingConversation) {
        node.search?.apply {
            val selected=node.webSearchOverride ?: node.searchDefault
            applySearchToggleStyle(selected)
            val supported=node.apiSnapshot?.let(ProviderCapabilities::search) ?: false
            isEnabled=!node.discarding&&!node.chat.busy&&!gathering&&!recordingActive&&(supported||selected)
            tooltipText=if(supported)"允许模型搜索网页" else "当前模型或协议不支持搜索"
            accessible(if(selected)"联网搜索已开启，点击关闭" else "联网搜索已关闭，点击开启")
        }
    }
    private fun clamp(layout:WindowManager.LayoutParams){val size=screenSize();layout.width=layout.width.coerceIn(minOf(dp(220),size.first-dp(16)),size.first-dp(16));layout.height=layout.height.coerceIn(minOf(dp(220),size.second-dp(80)),size.second-dp(80));layout.x=layout.x.coerceIn(dp(8),(size.first-layout.width-dp(8)).coerceAtLeast(dp(8)));layout.y=layout.y.coerceIn(dp(8),(size.second-layout.height-dp(60)).coerceAtLeast(dp(8)))}
    private fun installDrag(node:FloatingConversation,handle:View,root:AssistantConversationWindow,layout:WindowManager.LayoutParams,resizing:Boolean){
        var downX=0f;var downY=0f;var ox=0;var oy=0;var ow=0;var oh=0
        handle.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;ox=layout.x;oy=layout.y;ow=layout.width;oh=layout.height;true}
            MotionEvent.ACTION_MOVE->{val dx=(event.rawX-downX).toInt();val dy=(event.rawY-downY).toInt();if(resizing){layout.width=ow+dx;layout.height=oh+dy}else{layout.x=ox+dx;layout.y=oy+dy};clamp(layout);if(node.root===root)root.update(layout);true}
            MotionEvent.ACTION_UP->{val size=screenSize();val density=resources.displayMetrics.density;preferences.saveWindow(node.kind,SavedWindow(layout.x.toFloat()/(size.first-layout.width).coerceAtLeast(1),(layout.y-dp(8)).toFloat()/(size.second-layout.height-dp(60)).coerceAtLeast(1),(layout.width/density).toInt(),(layout.height/density).toInt()));true}
            else->true
        }}
    }
    private fun updateTranscriptCopy(node:FloatingConversation) {
        node.transcriptCopy?.visibility=if(node.transcript?.visibility==View.VISIBLE&&node.chat.transcript.isNotBlank())View.VISIBLE else View.GONE
    }
    private fun refresh(node:FloatingConversation){
        if(!alive||node.detached)return
        node.readingPresentation?.sourceContent?.findViewWithTag<View>("audio_subtitles")?.visibility=
            if(subtitlePreferences.anyEnabled()&&!node.awaitingAudioApproval)View.VISIBLE else View.GONE
        val imageReadingEnabled=preferences.load().imagePointReading
        if(node.sourceImage!=null && node.imageReadingBound!=imageReadingEnabled)node.sourceImage?.apply {
            node.imageReadingBound=imageReadingEnabled
            if(imageReadingEnabled) {
                accessible("截图预览，点按打开图片点读")
                setOnClickListener {
                    if(preferences.load().imagePointReading)node.frame?.bitmap?.let { bitmap ->
                        node.readingImageDialog?.dismiss()
                        node.readingImageDialog=showImagePointReading(uiContext,bitmap,node.chat.conversationId,overlay=true)
                    }
                }
            } else {
                setOnClickListener(null);isClickable=false;accessible("本次截图预览")
            }
        }
        val readingEnabled=preferences.load().chatPointReading
        if(readingEnabled && node.readingBar==null && node.readingPresentation!=null) {
            node.readingBar=ReadingBar(uiContext,node.chat.conversationId,ReadingOrigin.CHAT).also { node.readingPresentation?.addReadingBar(it) }
        } else if(!readingEnabled && node.readingBar!=null) {
            (node.readingBar?.parent as? ViewGroup)?.removeView(node.readingBar);node.readingBar=null
        }
        ProbeState.requesting=nodes.values.any{it.chat.busy};bubbleVisual?.working=ProbeState.requesting
        node.source?.text=when(node.kind){"photo"->imageDescription(node)+"  ▾";"text"->"所选原文  ▾";else->if(node.chat.transcript.isNotBlank())"转写原文 · ${String.format(Locale.getDefault(),"%.1f",node.clip?.seconds ?: 0f)} 秒  ▾" else "内部音频 · 不附图"}
        val sourceText=if(node.kind=="text")node.chat.sourceText else node.chat.transcript
        node.transcript?.let { if(it.text.toString()!=sourceText)it.text=sourceText }
        node.transcript?.enableChatReading(node.chat.conversationId, block=if(node.kind=="text")"source" else "transcript", overlay=true)
        updateTranscriptCopy(node)
        val messages=buildList {
            node.chat.messages.withIndex().toList().takeLast(12).forEach { (index,message) ->
                if(!(node.automaticFirstQuestion&&index==0&&message.role=="user")) add(FloatingReadingMessage(
                    message.role=="user",message.text,message.searchSuggestions,ReadingAnchor.message(index,message.role)))
            }
            if(node.chat.question.isNotBlank()&&!(node.automaticFirstQuestion&&node.chat.messages.isEmpty())) add(FloatingReadingMessage(
                true,node.chat.question,"",ReadingAnchor.message(node.chat.messages.size,"user")))
            if(node.chat.partial.isNotBlank()) add(FloatingReadingMessage(false,node.chat.partial,"","partial",complete=false))
        }
        val scroll=node.scroll;val nearBottom=scroll==null||(scroll.scrollY+scroll.height>=(node.content?.height ?: 0)-dp(48))
        renderFloatingMessages(node,messages)
        val notice=node.chat.notice.takeUnless { node.awaitingAudioApproval }
        node.requestNotice?.render(notice)
        val status=if(node.discarding)"正在丢弃…" else floatingConversationStatus(node.kind,node.chat.phase,node.chat.status,node.awaitingAudioApproval)
        val providerLabel=node.apiSnapshot?.let { api ->
            if(node.chat.busy || node.chat.question.isNotBlank())node.chat.requestProviderLabel else ProviderCapabilities.label(api)
        }.orEmpty()
        // Exception details belong behind the info action, including after the user dismisses the notice.
        val showStatus=node.discarding || node.awaitingAudioApproval || when(node.chat.phase) {
            ChatRequestPhase.FAILED,ChatRequestPhase.CANCELLED,ChatRequestPhase.PAUSED -> false
            else -> true
        }
        node.status?.text=listOf(providerLabel,if(showStatus)status else "").filter { it.isNotBlank() }.joinToString("\n")
        node.status?.visibility=if(node.status?.text.isNullOrBlank())View.GONE else View.VISIBLE
        node.audioApproval?.visibility=if(node.awaitingAudioApproval)View.VISIBLE else View.GONE
        node.composerToggle?.isEnabled=!node.discarding&&!node.awaitingAudioApproval
        node.input?.isEnabled=!node.discarding
        node.discard?.isEnabled=!node.discarding
        if(node.discarding)node.favorite?.isEnabled=false
        updateSendEnabled(node)
        node.cancel?.visibility=if(node.chat.busy)View.VISIBLE else View.GONE
        node.retry?.visibility=if(node.chat.canRetry&&!gathering&&!node.discarding)View.VISIBLE else View.GONE
        node.retry?.accessible(node.chat.retryLabel)
        node.copy?.isEnabled=!node.discarding&&node.chat.partial.ifBlank { node.chat.messages.lastOrNull { it.role=="assistant" }?.text.orEmpty() }.isNotBlank()
        node.imageChoice?.isEnabled=!node.discarding&&!node.chat.busy&&!gathering
        node.imageChoice?.alpha=if(node.imageChoice?.isEnabled==false).55f else 1f
        updateSearch(node)
        if(nearBottom)scroll?.post{if(node.scroll===scroll&&node.input?.hasFocus()!=true&&node.root?.hasTextActionMode!=true)scroll.scrollTo(0,node.content?.height ?: 0)}
        if(node.awaitingAudioApproval)ProbeState.lastEvent="已达录音上限，等待确认；尚未上传"
        else if(!node.chat.busy&&node.chat.status!=lastFinished){lastFinished=node.chat.status;ProbeState.lastEvent=node.chat.status}
    }
    /** Keep message views stable while a streaming reply grows and only update changed text. */
    private data class FloatingReadingMessage(val user:Boolean,val text:String,val suggestions:String,val block:String,val complete:Boolean=true)
    private fun renderFloatingMessages(node:FloatingConversation,messages:List<FloatingReadingMessage>) {
        val container=node.response ?: return
        var remaining=100000
        val visible=messages.asReversed().mapNotNull { message ->
            if(remaining<=0) null else message.copy(text=message.text.takeLast(remaining)).also { remaining-=it.text.length }
        }.asReversed()
        val visibleSuggestionKeys=mutableSetOf<String>()
        visible.forEachIndexed { index,item ->
            val user=item.user;val value=item.text;val suggestions=item.suggestions
            var row=container.getChildAt(index)
            if(row==null || row.tag!=user) {
                if(row!=null)container.removeViewAt(index)
                row=uiContext.assistantMessageRow(user)
                container.addView(row,index)
            }
            val body=if(row is com.google.android.material.card.MaterialCardView)row.getChildAt(0) as ViewGroup else row as ViewGroup
            val message=body.getChildAt(0) as TextView
            if(message.tag!=value) {
                message.text=MarkdownText.render(value)
                message.tag=value
            }
            message.enableChatReading(node.chat.conversationId,item.complete,block=item.block,overlay=true)
            val preserved=2 // Text and the binding's hidden-or-visible records button.
            if(!user && suggestions.isNotBlank() && suggestions.length<=32768) {
                val widgetKey="$index:$suggestions"
                visibleSuggestionKeys.add(widgetKey)
                val widget=node.suggestionViews.getOrPut(widgetKey) { uiContext.searchSuggestionsView(suggestions) }
                if(widget.parent!==body) {
                    (widget.parent as? ViewGroup)?.removeView(widget)
                    while(body.childCount>preserved)body.removeViewAt(preserved)
                    body.addView(widget)
                }
            } else while(body.childCount>preserved)body.removeViewAt(preserved)
        }
        node.suggestionViews.keys.filterNot { it in visibleSuggestionKeys }.toList().forEach { key ->
            node.suggestionViews.remove(key)?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        }
        while(container.childCount>visible.size)container.removeViewAt(container.childCount-1)
    }
    private fun imageDescription(node:FloatingConversation):String{val f=node.frame?:return "无可用截图";return "${if(node.bounds!=null)"选区" else "截图"} ${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(f.capturedAt))}"}
    private fun archivePrepared(node:FloatingConversation){
        node.clip?.file?.let { StorageRuntime.get(this).register(it, "temporary", node.chat.conversationId) }
        runCatching{HistoryStore(this).save(node.chat.snapshot().copy(title=when(node.kind){"photo"->"截图待提问";"audio"->"音频待提问";else->node.chat.sourceText.take(60)},evidence=when(node.kind){"photo"->imageDescription(node);"audio"->"内部录音，等待提问";else->"选中文字"},status="等待提问"))}.onFailure{toast("本机历史保存失败，请检查存储空间")}
        persistImage(node)
        persistAudio(node)
    }
    private fun persistAudio(node:FloatingConversation){
        val clip=node.clip?:return
        if(!preferences.load().saveHistoryAudio)return
        val id=node.chat.conversationId
        // Open before queuing: closing/replacing the overlay may unlink its temporary WAV.
        val input=runCatching{clip.file.inputStream()}.getOrElse{toast("音频未保存，请检查本机存储");return}
        val usage = Any()
        ConversationUsage.update(usage, id)
        try{
            val length=input.channel.size()
            mediaWorker.execute{
                try { input.use{
                    runCatching{
                        val history=HistoryStore(this);val media=ConversationMediaStore(this)
                        if(history.summary(id)!=null){
                            media.saveAudio(id,it,length)
                            // A deletion may have completed while the recording was being copied.
                            if(history.summary(id)==null)media.deleteAudio(id)
                        }
                    }.onFailure{error->main.post{if(alive)toast("音频未保存：${error.message?:"存储失败"}")}}
                } } finally { ConversationUsage.update(usage, null) }
            }
        }catch(_:Exception){
            ConversationUsage.update(usage, null)
            runCatching{input.close()};toast("音频未保存，请检查本机存储")
        }
    }
    private fun persistImage(node:FloatingConversation){
        val bitmap=node.frame?.bitmap?:return
        if(node.originalFrame==null)node.originalFrame=node.frame
        val original=node.originalFrame!!.bitmap
        if(!preferences.load().saveHistoryImages)return
        val id=node.chat.conversationId
        val usage = Any()
        ConversationUsage.update(usage, id)
        mediaWorker.execute{
            runCatching{
                val history=HistoryStore(this);val media=ConversationMediaStore(this)
                if(preferences.load().saveHistoryImages&&history.summary(id)!=null){
                    media.saveImages(id,original,bitmap)
                    if(!preferences.load().saveHistoryImages||history.summary(id)==null)media.deleteImage(id)
                }
            }.onFailure{error->main.post{if(alive)toast("回答可继续，但图片未保存：${error.message?:"存储失败"}")}}
            ConversationUsage.update(usage, null)
        }
    }
    private fun showPrepared(node:FloatingConversation,autoAsk:Boolean) {
        archivePrepared(node);showWindow(node,focusQuestion=!autoAsk)
        if(autoAsk)submitDefault(node)
    }
    private fun submitDefault(node:FloatingConversation){val c=readApiConfig(node)?:return;node.automaticFirstQuestion=true;sendQuestion(node,when(node.kind){"photo"->c.defaultQuestion;"audio"->c.audioQuestion;else->c.textQuestion},c,useDefaultAudioOutput=true)}
    private fun readApiConfig(node:FloatingConversation):AppConfig?=try{SettingsStore(this).load()}catch(_:Exception){node.chat.reportSubmissionFailure(IllegalStateException("无法读取 API 配置，请在设置中重新保存密钥"));null}
    private fun sendQuestion(node:FloatingConversation,text:String,frozen:AppConfig?=null,useDefaultAudioOutput:Boolean=false){
        if (AssistantPreview.overlays.active||node.discarding) return
        if(node.awaitingAudioApproval){toast("请先选择转写或丢弃本段录音");return}
        if(node.chat.busy||gathering||recordingActive){toast("请先结束当前采集或请求");return}
        if(text.isBlank()){node.chat.reportSubmissionFailure(IllegalArgumentException("请输入问题"));return}
        val base=frozen?:readApiConfig(node)?:return
        if(node.apiSnapshot?.connectionId != base.connectionId)node.webSearchOverride=null
        node.apiSnapshot=base
        node.searchDefault=base.webSearch
        node.status?.tooltipText=ProviderCapabilities.label(base)
        val c=base.copy(webSearch=node.webSearchOverride ?: base.webSearch)
        if(node.kind=="audio"&&node.clip!=null&&node.clip!!.peak<.0001){node.chat.reportSubmissionFailure(IllegalStateException("录音没有有效音量，请重新采集"));return}
        val submit=submit@{
            if(node.detached||node.discarding||nodes[node.kind]!==node)return@submit
            val image=if(node.kind=="photo"&&node.imageMode!=2)node.frame?.bitmap else null
            val evidence=when(node.kind){"photo"->if(image!=null)imageDescription(node) else "仅文字上下文";"audio"->"内部录音 ${String.format(Locale.getDefault(),"%.1f",node.clip?.seconds ?: 0f)} 秒 · 不附图";else->"选中文字与本段上下文"}
            try{node.chat.submit(c,text,image,evidence,if(node.kind=="audio"&&node.chat.transcript.isBlank())node.clip?.file else null,useDefaultAudioOutput=useDefaultAudioOutput);node.draft="";node.input?.setText("");persistImage(node);refresh(node)}catch(e:Exception){node.chat.reportSubmissionFailure(e)}
        }
        if(node.kind=="photo"&&node.imageMode==1)capture(node,false,true){submit()} else submit()
    }
    private fun captureNew(crop:Boolean){
        if (AssistantPreview.overlays.active) return
        if(recordingActive){toast("请先完成或取消录音");return}
        if(gathering){toast("正在取得画面，请稍候");return}
        if(frames==null){requestCaptureConsent(if(crop)AssistantActions.CROP else AssistantActions.SCREEN);return}
        val node=fresh("photo");capture(node,crop,false){showPrepared(node,config.autoAsk("photo"))}
    }
    private fun capture(node:FloatingConversation,crop:Boolean,reuse:Boolean,after:()->Unit){
        if(!alive || AssistantPreview.overlays.active || gathering || node.detached || nodes[node.kind]!==node)return
        val source=frames?:run{requestCaptureConsent(AssistantActions.SCREEN){capture(node,crop,reuse,after)};return}
        val oldBounds=if(reuse)node.bounds else null;val oldSize=node.sourceSize;val oldRotation=node.rotation
        val rotation=screenRotation();val id=++captureEpoch
        gathering=true;captureOwner=node;removeMenu();removeBubble();removeSelector()
        // Detach the actual overlay windows, including their surface/shadow and IME owner.
        // INVISIBLE only hides view content and can leave a composed window on vendor systems.
        val shown=nodes.values.mapNotNull { visible ->
            val root=visible.root?:return@mapNotNull null
            val layout=visible.layout?:return@mapNotNull null
            Triple(visible,root,layout)
        }
        restoreCaptureWindows={
            shown.forEach { (visible,root,layout) ->
                if(alive&&!AssistantPreview.overlays.active&&!visible.detached&&nodes[visible.kind]===visible&&visible.root===root&&!root.isAttachedToWindow){
                    clamp(layout)
                    layout.softInputMode=(layout.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    runCatching{root.attach(layout);visible.layout=layout;refresh(visible)}
                        .onFailure{hideWindow(visible,false);toast("截图结束，但小窗恢复失败，可从菜单重新打开")}
                }
            }
        }
        try {
            shown.forEach { (_,root,_) ->
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
                root.clearFocus()
                root.detach()
            }
        } catch(_:RuntimeException) {
            restoreAfterCapture();toast("无法暂时收起小窗，截图未发送，请重试");return
        }
        Choreographer.getInstance().postFrameCallback{Choreographer.getInstance().postFrameCallback{
            // Window removal and the system dim/blur layer must settle before the fresh-frame boundary.
            main.postDelayed({
                if(!alive||captureEpoch!=id)return@postDelayed
                source.snapshot(System.nanoTime()){result->
                    if(!alive||captureEpoch!=id||node.detached||nodes[node.kind]!==node){result.getOrNull()?.bitmap?.recycle();return@snapshot}
                    restoreAfterCapture()
                    result.fold(onSuccess={frame->
                        if(screenRotation()!=rotation||(oldBounds!=null&&(oldSize!=(frame.bitmap.width to frame.bitmap.height)||oldRotation!=rotation))){toast("屏幕尺寸或方向变化，请重新框选");showWindow(node);return@fold}
                        if(!reuse){node.bounds=null;node.sourceSize=null;node.rotation=null}
                        node.frame=if(oldBounds==null)frame else CapturedFrame(Bitmap.createBitmap(frame.bitmap,oldBounds.x,oldBounds.y,oldBounds.width,oldBounds.height),frame.capturedAt)
                        if(crop)showCrop(node,frame,rotation) else{node.sourceImage?.setImageBitmap(node.frame?.bitmap);after()}
                    },onFailure={toast(it.message?:"取图失败，请重试");if(reuse)showWindow(node)})
                }
            },CAPTURE_SETTLE_MS)
        }}
    }
    private fun restoreAfterCapture(){
        frames?.cancelSnapshot()
        val restore=restoreCaptureWindows;restoreCaptureWindows=null
        gathering=false;captureOwner=null
        restore?.invoke()
        showBubble()
    }
    private fun showCrop(node:FloatingConversation,frame:CapturedFrame,rotation:Int){
        if (AssistantPreview.overlays.active) return
        removeBubble();hideUnpinned();removeSelector()
        val crop=CropView(uiContext,frame.bitmap)
        lateinit var body:LinearLayout
        val cancel={
            if(selector===body) { removeSelector();node.frame=null;showBubble();applyPendingAppearance() }
        }
        body=uiContext.column().apply{background=card(BACKGROUND);addView(uiContext.label("拖动选择画面",18f));addView(crop,LinearLayout.LayoutParams(-1,0,1f))
            action("使用选区",true){val bounds=crop.selectedBounds();val bitmap=crop.selected();if(bounds==null||bitmap==null){toast("请先拖动选择足够大的区域");return@action};node.bounds=bounds;node.sourceSize=frame.bitmap.width to frame.bitmap.height;node.rotation=rotation;node.frame=CapturedFrame(bitmap,frame.capturedAt);node.imageMode=0;removeSelector();showPrepared(node,config.autoAsk("photo"))}
            action("取消",run=cancel)
        }
        installOverlayBack(body,cancel)
        val size=screenSize()
        val layout=params(size.first,size.second-dp(60),true).apply {
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        try{windows.addView(body,layout);selector=body;body.requestFocus()}catch(_:RuntimeException){toast("无法打开框选");showBubble()}
    }
    private fun startRecording(){
        if(pendingRecordingStart!=null){toast("正在等待开始录音");return}
        if(!recordingReady(requestPermission=true))return
        val recordingConfig=config
        val delay=recordingConfig.recordingStartDelayTenths * 100L
        if(delay==0L){beginRecording(recordingConfig);return}
        lateinit var task: Runnable
        task=Runnable {
            if(pendingRecordingStart!==task)return@Runnable
            pendingRecordingStart=null;recordingStartAt=0L
            if(recordingReady(requestPermission=false))beginRecording(recordingConfig)
            else { removeMenu();cancelGestures();ProbeState.lastEvent="未开始录音" }
        }
        pendingRecordingStart=task
        recordingStartAt=SystemClock.uptimeMillis()+delay
        removeMenu();hideUnpinned();cancelGestures()
        main.postDelayed(task,delay)
        ProbeState.lastEvent="${RecordingStartDelay.seconds(recordingConfig.recordingStartDelayTenths)} 秒后开始录音"
        toast(ProbeState.lastEvent);showBubble()
    }
    private fun recordingReady(requestPermission:Boolean):Boolean{
        if(!alive||!AssistantControl.isEnabled(this)||!Settings.canDrawOverlays(this)||AssistantPreview.overlays.active||recorder!=null||finishingAudio||gathering)return false
        if(!requestPermission && (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked))return false
        nodes["audio"]?.takeIf{it.awaitingAudioApproval}?.let {
            showWindow(it);toast("上一段录音已达上限，请先选择转写或丢弃");return false
        }
        if(projection==null || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            if(requestPermission)requestCaptureConsent(AssistantActions.START_AUDIO) else toast("录音授权已失效，请重新开始")
            return false
        }
        if(nodes["audio"]?.chat?.busy==true){toast("请先等待或取消当前音频回答");showWindow(nodes.getValue("audio"));return false}
        return true
    }
    private fun beginRecording(recordingConfig:InteractionConfig){
        val source=projection?:return
        PointReadingController.stopIfCreated()
        removeMenu();hideUnpinned();stopPlayback();audioManual=false;audioForPreview=false
        val token=recordingGate.begin();val next=PlaybackRecorder(this,main);recorder=next
        try{next.start(source, recordingConfig.recordingMaxMinutes){result->
            if(!alive||recorder!==next||!recordingGate.complete(token)){result?.file?.delete();return@start}
            recorder=null;finishingAudio=false;ProbeState.recording=false
            val retainForPreview = audioForPreview; audioForPreview = false
            if(result==null){ProbeState.lastEvent="录音采集失败，请检查权限和音源后重录";toast(ProbeState.lastEvent);return@start}
            val disposition=recordingDisposition(result.reachedLimit,recordingConfig.recordingLimitAction,config.autoAskAudio,audioManual,config.useDefaultQuestion,captureFailed=result.error!=null,retainForPreview=retainForPreview)
            if(disposition==RecordingDisposition.FAILED){
                val deleted=result.file.delete()
                ProbeState.lastEvent=if(deleted)"录音采集中断，请重录" else "录音采集中断，请重录；临时音频清理失败"
                toast(ProbeState.lastEvent);showBubble();return@start
            }
            if(disposition==RecordingDisposition.DISCARD){
                val deleted=result.file.delete()
                ProbeState.lastEvent=if(deleted)"已达录音上限，本段录音已丢弃，未发起转写" else "已达录音上限，未发起转写；录音文件清理失败"
                toast(ProbeState.lastEvent);showBubble();return@start
            }
            val node=fresh("audio");node.clip=result;ActiveConversationBridge.retainAudio(result.file);node.imageMode=2
            node.awaitingAudioApproval=disposition==RecordingDisposition.PROMPT
            if(!node.awaitingAudioApproval)archivePrepared(node)
            showWindow(node,focusQuestion=disposition==RecordingDisposition.READY)
            if(node.awaitingAudioApproval){toast("已达录音上限，录音已停止，等待确认转写");return@start}
            if(result.peak<.0001)toast("未检测到有效声音，音源可能不允许采集")
            else if(disposition==RecordingDisposition.PROCESS)submitDefault(node)
        };ProbeState.recording=true;ProbeState.lastEvent="内部音频录制已开始";showBubble()}
        catch(e:Exception){recordingGate.cancel();recorder=null;next.stop(true);finishingAudio=false;ProbeState.recording=false;toast(e.message?:"无法开始内部录音")}
    }
    private fun approvePendingAudio(node:FloatingConversation){
        if(node.discarding||!node.awaitingAudioApproval||nodes[node.kind]!==node)return
        node.awaitingAudioApproval=false
        showPrepared(node,config.useDefaultQuestion)
    }
    private fun discardPendingAudio(node:FloatingConversation){
        if(node.awaitingAudioApproval)discardConversation(node)
    }
    private fun discardConversation(node:FloatingConversation){
        if(!alive||node.detached||node.openingInApp||node.discarding||nodes[node.kind]!==node)return
        node.discarding=true
        if(captureOwner===node){captureEpoch++;removeSelector();restoreAfterCapture()}
        // Cancel invalidates streaming/completion callbacks before any archive is removed.
        // The cancellation snapshot is queued first; HistoryStore.delete invalidates queued writes.
        node.chat.cancel()
        ChatAudioPlayback.stopConversation(node.chat.conversationId)
        playback.setVisible(node, null, false)
        refresh(node)
        val id=node.chat.conversationId
        val temporaryAudio=node.clip?.file
        mediaWorker.execute {
            val result=runCatching {
                HistoryStore(this).delete(id)
                // Pending/unarchived evidence may have media without a history entry.
                ConversationMediaStore(this).delete(id)
                temporaryAudio?.let { check(!it.exists()||it.delete()) { "临时录音清理失败" } }
            }
            main.post {
                result.onSuccess {
                    hideWindow(node)
                    node.detached=true;node.chat.close()
                    if(nodes[node.kind]===node)nodes.remove(node.kind)
                    if(deferredPreviewWindows[node.kind]?.first===node)deferredPreviewWindows.remove(node.kind)
                    previewWindows=previewWindows.filterNot { it.first===node }
                    node.draft="";node.frame=null;node.originalFrame=null;node.clip=null
                    temporaryAudio?.let(ActiveConversationBridge::releaseAudio)
                    if(lastKind==node.kind&&nodes[lastKind]==null)lastKind=nodes.keys.lastOrNull() ?: "photo"
                    ProbeState.requesting=nodes.values.any { it.chat.busy }
                    bubbleVisual?.working=ProbeState.requesting
                    if(alive){ProbeState.lastEvent="已丢弃当前对话";toast(ProbeState.lastEvent);showBubble()}
                }.onFailure {
                    node.discarding=false
                    if(alive&&nodes[node.kind]===node){
                        if(node.root!=null)showWindow(node,node.layout)
                        toast("丢弃失败，请点垃圾桶重试")
                    }
                }
            }
        }
    }
    private fun cancelPendingRecordingStart():Boolean{
        val pending=pendingRecordingStart?:return false
        pendingRecordingStart=null;recordingStartAt=0L;main.removeCallbacks(pending)
        cancelGestures()
        ProbeState.lastEvent="已取消，未开始录音"
        return true
    }
    private fun finishRecording(manual:Boolean){
        if(pendingRecordingStart!=null){cancelRecording(true);return}
        val current=recorder?:return;if(finishingAudio)return;audioManual=manual;finishingAudio=true;current.stop(false)
    }
    private fun cancelRecording(notify:Boolean){
        val wasPending=cancelPendingRecordingStart()
        val current=recorder
        recordingGate.cancel();recorder=null;finishingAudio=false;audioManual=false;audioForPreview=false;ProbeState.recording=false
        current?.stop(true);removeMenu();cancelGestures();bubbleVisual?.invalidate()
        ProbeState.lastEvent=if(wasPending)"已取消，未开始录音" else if(current!=null)"录音已取消并丢弃，没有发起请求" else "当前没有录音"
        if(notify)toast(ProbeState.lastEvent)
    }
    private fun stopPlayback() { playback.stop() }
    private fun playClip() {
        val node = nodes["audio"] ?: return
        if(node.discarding)return
        val file = node.clip?.file ?: return
        if (!preferences.load().backgroundPlayback && node.root?.isAttachedToWindow != true) {
            showWindow(node)
            if (node.root?.isAttachedToWindow != true) return
        }
        if (playback.isActive(node.chat.conversationId) && playback.readingKey.isEmpty()) playback.toggle()
        else playback.play(node.chat.conversationId, file)
    }
    private fun detachToApplication(id:String):Bitmap?{
        val entry=nodes.entries.firstOrNull{it.value.chat.conversationId==id}?:return null
        if(entry.value.discarding)return null
        val node=entry.value;hideWindow(node);node.detached=true;nodes.remove(entry.key)
        if(captureOwner===node){captureEpoch++;removeSelector();restoreAfterCapture()}
        node.clip?.file?.let{ActiveConversationBridge.retainAudio(it)}
        ActiveConversationBridge.offer(id,HandedOffConversation(node.chat,node.frame?.bitmap,{
            node.chat.close();node.clip?.file?.let{ActiveConversationBridge.releaseAudio(it);ChatAudioPlayback.deleteTemporaryAudio(it)}
        },node.clip,node.draft,node.webSearchOverride,node.originalFrame?.bitmap ?: node.frame?.bitmap,node.imageMode!=2,
            restore={ state ->
                // Never replace a newer capture, restart a stopped service, or copy stale history.
                if(!alive || nodes.containsKey(node.kind)) false else {
                    node.draft=state.draft;node.webSearchOverride=state.webSearchOverride
                    node.imageMode=if(!state.reuseImage)2 else if(node.imageMode==2)0 else node.imageMode
                    node.detached=false;node.openingInApp=false
                    nodes[node.kind]=node
                    refresh(node)
                    true
                }
            }))
        ProbeState.requesting=nodes.values.any{it.chat.busy}
        return node.frame?.bitmap
    }
    @Suppress("DEPRECATION") private fun screenRotation()=windows.defaultDisplay.rotation
    private fun clearCapture(){cancelRecording(false);captureEpoch++;frames?.close();frames=null;val old=projection;projection=null;old?.unregisterCallback(captureCallback);old?.stop();ProbeState.sharing=false;removeSelector();restoreAfterCapture()}
    override fun onConfigurationChanged(newConfig:Configuration){
        super.onConfigurationChanged(newConfig)
        val geometryChanged = newConfig.orientation != lastConfiguration.orientation ||
            newConfig.screenWidthDp != lastConfiguration.screenWidthDp || newConfig.screenHeightDp != lastConfiguration.screenHeightDp ||
            newConfig.densityDpi != lastConfiguration.densityDpi
        lastConfiguration = Configuration(newConfig)
        if (geometryChanged) {
            captureEpoch++;removeSelector();restoreAfterCapture()
            nodes.values.forEach { node ->
                node.root?.visibility=View.VISIBLE
                node.layout?.let { layout -> clamp(layout); node.root?.let { root -> runCatching { root.update(layout) } } }
            }
            if(Build.VERSION.SDK_INT<34){val size=screenSize();frames?.resize(size.first,size.second)}
        }
        requestAppearanceRefresh()
    }
    override fun onDestroy(){
        alive=false;capturePermissionGate.cancel();main.removeCallbacksAndMessages(null);ActiveConversationBridge.detachHandler=null
        unregisterReceiver(recordingScreenReceiver)
        AssistantPreview.overlays.removeListener(previewListener); AssistantPreview.finishRecording = null
        previewWindows=emptyList();previewSelector=null;deferredPreviewWindows.clear()
        AppAppearance.removeListener(this, appearanceListener)
        preferences.removeListener(preferencesListener);cancelRecording(false)
        apiPreferences.unregisterOnSharedPreferenceChangeListener(apiPreferencesListener)
        subtitlePreferences.removeListener(subtitleListener)
        nodes.values.forEach{hideWindow(it);it.chat.close();it.clip?.file?.let { file -> ActiveConversationBridge.releaseAudio(file);ChatAudioPlayback.deleteTemporaryAudio(file) }};nodes.clear()
        clearCapture();removeMenu();removeBubble();removeSelector();mediaWorker.shutdown()
        ProbeState.serviceRunning=false;ProbeState.recording=false;ProbeState.requesting=false;ProbeState.lastEvent="助手与采集已停止"
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    private inner class OutsideFrame(private val outside:()->Unit):FrameLayout(uiContext){
        override fun dispatchTouchEvent(event:MotionEvent):Boolean{if(event.actionMasked==MotionEvent.ACTION_OUTSIDE){outside();return false};return super.dispatchTouchEvent(event)}
    }
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    companion object { const val START="start";const val SHARE="share";const val STOP="stop";const val TEXT="process_text";private const val CHANNEL="assistant";private const val CAPTURE_SETTLE_MS=250L }
}
