package com.alwayside.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

/** The bubble dispatches actions. Each evidence type owns a separate compact conversation. */
class AssistantService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private lateinit var preferences: InteractionPreferences
    private var config = InteractionConfig()
    private val nodes = linkedMapOf<String, FloatingConversation>()
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private val recordingGate = RecordingGate()
    private var alive = true
    private var bubble: FrameLayout? = null
    private var bubbleVisual: BubbleVisual? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var selector: View? = null
    private var projection: MediaProjection? = null
    private var frames: FrameSource? = null
    private var gathering = false
    private var captureEpoch = 0
    private var captureOwner: FloatingConversation? = null
    private var recorder: PlaybackRecorder? = null
    private var finishingAudio = false
    private var audioManual = false
    private var requestingConsent = false
    private var captureContinuation: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private var lastKind = "photo"
    private val taps = TapSequence()
    private var burstRecording = false
    private var lastFinished: String? = null
    private val settleTaps = Runnable {
        val count = taps.settle()
        if (count > 0 && alive) dispatchAction(config.action(count, burstRecording))
    }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = preferences.load()
        if (updated != config) {
            config = updated
            cancelGestures()
            removeMenu()
            removeBubble(); showBubble()
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
            bubbleVisual?.recording = recorder != null
            bubbleVisual?.working = nodes.values.any { it.chat.busy }
            bubbleVisual?.invalidate()
            val recording = recorder != null
            bubble?.contentDescription = (if(recording)"AlwaySide 正在录音。" else "AlwaySide。") +
                listOf(1 to "单击",2 to "双击",3 to "三击",4 to "长按").joinToString("，") { (count,label) -> "$label：${AssistantActions.choices[config.action(count,recording)]}" }
            val current = recorder
            if (current != null) ProbeState.lastEvent = String.format(Locale.getDefault(), "正在听 %.1f 秒%s", current.seconds, if (finishingAudio) "，正在结束" else "")
            main.postDelayed(this, 250)
        }
    }

    private inner class FloatingConversation(val kind: String) {
        lateinit var chat: ChatSession
        var frame: CapturedFrame? = null
        var bounds: PixelCrop? = null
        var sourceSize: Pair<Int, Int>? = null
        var rotation: Int? = null
        var clip: AudioClip? = null
        var imageMode = 0
        var draft = ""
        var pinned = false
        var automaticFirstQuestion = false
        var originalExpanded = false
        var detached = false
        var root: OutsideFrame? = null
        var layout: WindowManager.LayoutParams? = null
        var response: TextView? = null
        var status: TextView? = null
        var source: TextView? = null
        var sourceImage: ImageView? = null
        var transcript: TextView? = null
        var input: EditText? = null
        var send: View? = null
        var cancel: View? = null
        var retry: View? = null
        var copy: View? = null
        var pin: ImageButton? = null
        var imageChoice: Spinner? = null
        var scroll: ScrollView? = null
        var content: LinearLayout? = null
        init {
            chat = ChatSession(this@AssistantService) { refresh(this) }
            chat.newConversation(kind = kind)
            pinned = config.defaultPinned
            imageMode = if (kind == "photo" && runCatching { SettingsStore(this@AssistantService).load().autoUpdateImage }.getOrDefault(false)) 1 else if (kind == "audio") 2 else 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        preferences = InteractionPreferences(this)
        config = preferences.load()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮助手与采集", NotificationManager.IMPORTANCE_LOW))
        preferences.addListener(preferencesListener)
        ActiveConversationBridge.detachHandler = ::detachToApplication
        ProbeState.serviceRunning = true
        cacheDir.listFiles()?.filter { it.name.startsWith("listening-") && it.extension == "wav" && !ActiveConversationBridge.isAudioRetained(it) }?.forEach { it.delete() }
        main.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            if(intent?.action == START || intent?.action == SHARE) requestingConsent=false
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
            val requestedAction=intent?.getStringExtra("requestedAction")
            val continuation=captureContinuation
            captureContinuation=null
            if(requestedAction in setOf(AssistantActions.SCREEN,AssistantActions.CROP,AssistantActions.AUDIO)) {
                // The permission bridge delivers only as it leaves, with its close animation disabled.
                // A screenshot additionally waits for two compositor frames before sampling a fresh frame.
                main.post { if(alive) { if(continuation!=null)continuation() else dispatchAction(requestedAction!!) } }
            }
            if (intent?.action == TEXT) {
                val selected = intent.getStringExtra("selectedText").orEmpty().trim().take(12000)
                if(selected.isNotBlank()) {
                    val node=fresh("text");node.chat.setSourceText(selected);archivePrepared(node);showWindow(node)
                    if(config.autoAskText)submitDefault(node)
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
    private fun removeBubble() { cancelGestures(); remove(bubble); bubble = null; bubbleVisual = null; bubbleLayout = null }
    private fun removeMenu() { remove(menu); menu = null }
    private fun removeSelector() { remove(selector); selector = null }
    private fun cancelGestures() { main.removeCallbacks(settleTaps); taps.cancel() }

    private fun showBubble() {
        if (!alive || bubble != null || selector != null || gathering || requestingConsent) return
        val size = screenSize()
        val position = preferences.ballPosition()
        val view = FrameLayout(this).apply { isClickable = true; isFocusable = true; accessible("AlwaySide，快捷手势可在设置中修改") }
        val glyph = BubbleVisual().also { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val layout = params(dp(48), dp(48), false).apply {
            x = if (position.first) 0 else (size.first - dp(48)).coerceAtLeast(0)
            y = (position.second * size.second).toInt().coerceIn(dp(8), (size.second - dp(100)).coerceAtLeast(dp(8)))
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false; var held = false
        val longPress = Runnable {
            if (!dragged && bubble === view) {
                held = true; cancelGestures(); dispatchAction(config.action(4, recorder != null))
            }
        }
        view.setOnClickListener { dispatchAction(config.action(1, recorder != null)) }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    config = preferences.load()
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragged = false; held = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!held && (abs(dx) > ViewConfiguration.get(this).scaledTouchSlop || abs(dy) > ViewConfiguration.get(this).scaledTouchSlop)) {
                        dragged = true; main.removeCallbacks(longPress); cancelGestures(); removeMenu()
                    }
                    if (dragged && bubble === view) {
                        layout.x = (startX + dx).toInt().coerceIn(0, (size.first - layout.width).coerceAtLeast(0))
                        layout.y = (startY + dy).toInt().coerceIn(dp(8), (size.second - dp(100)).coerceAtLeast(dp(8)))
                        windows.updateViewLayout(view, layout)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragged) {
                        layout.x = if (layout.x + layout.width / 2 < size.first / 2) 0 else (size.first - layout.width).coerceAtLeast(0)
                        preferences.saveBall(layout.x == 0, layout.y.toFloat() / size.second)
                        if (bubble === view) windows.updateViewLayout(view, layout)
                    } else if (!held) {
                        main.removeCallbacks(settleTaps)
                        val count = taps.tap(SystemClock.uptimeMillis())
                        if (count == 1) burstRecording = recorder != null
                        if (count == 3) settleTaps.run() else main.postDelayed(settleTaps, taps.gapMillis)
                    }
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); cancelGestures() }
            }
            true
        }
        try { windows.addView(view, layout); bubble = view; bubbleVisual = glyph; bubbleLayout = layout }
        catch (_: RuntimeException) { toast("悬浮权限不可用，请从应用重新开启"); stopSelf() }
    }
    private inner class BubbleVisual : View(this@AssistantService) {
        var recording = false
        var working = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sparkle = prototypeIcon(R.drawable.ic_line_sparkles).apply { setTint(Color.WHITE) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = dp(config.ballSize) / 2f
            val x = width / 2f; val y = height / 2f
            paint.color = config.ballColor; paint.alpha = if (recording) 245 else (config.ballOpacity * 2.55f).toInt()
            paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, radius, paint)
            paint.color = Color.WHITE; paint.alpha = if (recording) 255 else (config.ballOpacity * 2.55f).toInt()
            if (recording) {
                val phase = SystemClock.uptimeMillis() / 180.0
                for (i in -1..1) {
                    val h = radius * (.35 + .55 * abs(sin(phase + i))).toFloat()
                    canvas.drawRoundRect(x + i * radius * .35f - dp(1), y - h / 2, x + i * radius * .35f + dp(1), y + h / 2, dp(1).toFloat(), dp(1).toFloat(), paint)
                }
            } else {
                val r = radius * .57f
                sparkle.alpha=paint.alpha;sparkle.setBounds((x-r).toInt(),(y-r).toInt(),(x+r).toInt(),(y+r).toInt());sparkle.draw(canvas)
                if (working) { paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); canvas.drawArc(x-radius-2,y-radius-2,x+radius+2,y+radius+2,(SystemClock.uptimeMillis()%1800)/5f,90f,false,paint); paint.style=Paint.Style.FILL }
            }
        }
    }
    private fun dispatchAction(action: String) {
        if (!alive) return
        removeMenu()
        when (action) {
            AssistantActions.SCREEN -> captureNew(false)
            AssistantActions.CROP -> captureNew(true)
            AssistantActions.AUDIO -> if (recorder != null) finishRecording(false) else startRecording()
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
        if(!alive || requestingConsent)return
        requestingConsent=true;captureContinuation=continuation;removeMenu();hideUnpinned()
        try {
            // Keep the visible overlay until startActivity: Android permits this user-initiated
            // launch through SYSTEM_ALERT_WINDOW only while an overlay is actually visible.
            startActivity(Intent(this,CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("requestedAction",action).putExtra("needsCapture",projection==null))
            removeBubble()
        } catch(_:RuntimeException) {
            requestingConsent=false;captureContinuation=null;showBubble();toast("无法打开系统授权，请返回应用后重试")
        }
    }
    private fun showMenu() {
        if (gathering || selector != null) return
        hideUnpinned(); removeMenu(); showBubble()
        val body = OutsideFrame { removeMenu() }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); background = outlinedCard(BACKGROUND, 13) }
        var rowCount = 0
        fun item(resource: Int, title: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(9),0,dp(9),0)
                background=touchFeedback(); isClickable=true; isFocusable=true; accessible(title)
                addView(ImageView(this@AssistantService).apply { setImageDrawable(prototypeIcon(resource)); imageTintList=android.content.res.ColorStateList.valueOf(MUTED) },LinearLayout.LayoutParams(dp(17),dp(17)))
                addView(label(title,12f),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(10)})
                setOnClickListener { removeMenu(); action() }
            }
            items.addView(row,LinearLayout.LayoutParams(-1,dp(44)));rowCount++
        }
        item(R.drawable.ic_line_scan,"截图问答") { captureNew(false) }
        item(R.drawable.ic_line_audio,if (recorder != null) "结束录音并处理" else "听一段") { if (recorder != null) finishRecording(false) else startRecording() }
        if (recorder != null) item(R.drawable.ic_line_close,"取消并丢弃录音") { cancelRecording(true) }
        item(R.drawable.ic_line_crop,"框选问答") { captureNew(true) }
        item(R.drawable.ic_line_text,"最近悬浮对话") { dispatchAction(AssistantActions.LAST) }
        if (nodes["audio"]?.clip != null) item(R.drawable.ic_line_play,"回听上一段") { playClip() }
        items.addView(View(this).apply{setBackgroundColor(BORDER)},LinearLayout.LayoutParams(-1,dp(1)).apply{setMargins(dp(8),dp(4),dp(8),dp(4))})
        item(R.drawable.ic_line_history,"对话记录") { openApplication(AppPage.HISTORY) }
        item(R.drawable.ic_line_settings,"设置") { openApplication(AppPage.SETTINGS) }
        body.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(items)})
        val size=screenSize(); val ball=bubbleLayout
        val w=minOf(dp(164),size.first-dp(16)); val h=minOf(dp(rowCount*44+21),size.second-dp(100))
        val layout=params(w,h,false).apply {
            x=if ((ball?.x ?: 0)<size.first/2) dp(47) else size.first-w-dp(47)
            x=x.coerceIn(dp(8),(size.first-w-dp(8)).coerceAtLeast(dp(8)))
            y=((ball?.y ?: dp(120))-dp(50)).coerceIn(dp(8),(size.second-h-dp(60)).coerceAtLeast(dp(8)))
        }
        try { windows.addView(body,layout);menu=body } catch (_: RuntimeException) { toast("无法打开菜单") }
    }
    private fun fresh(kind: String): FloatingConversation {
        nodes.remove(kind)?.let { old ->
            hideWindow(old)
            if (old.chat.busy) { old.chat.cancel(); toast("已停止上一条同类请求，开始新会话") }
            old.chat.close(); old.clip?.file?.delete()
        }
        return FloatingConversation(kind).also { nodes[kind]=it;lastKind=kind }
    }
    private fun hideUnpinned(except: FloatingConversation?=null) { nodes.values.filter { it!==except && !it.pinned }.forEach(::hideWindow) }
    private fun hideWindow(node: FloatingConversation) {
        node.root?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0);remove(it) }
        node.root=null;node.layout=null;node.response=null;node.status=null;node.source=null;node.sourceImage=null;node.transcript=null
        node.input=null;node.send=null;node.cancel=null;node.retry=null;node.copy=null;node.pin=null;node.imageChoice=null;node.scroll=null;node.content=null
        stopPlayback()
    }
    private fun icon(resource: Int, description: String, run: () -> Unit) = iconButton(resource,description,run)
    private fun showWindow(node: FloatingConversation) {
        if (!alive || node.detached) return
        removeMenu();removeSelector();hideUnpinned(node);hideWindow(node);lastKind=node.kind
        config=preferences.load()
        val size=screenSize();val saved=preferences.window(node.kind)
        val width=minOf(dp(if(config.rememberWindowSize)saved?.widthDp ?: 320 else 320),size.first-dp(24)).coerceAtLeast(dp(180))
        val height=minOf(dp(if(config.rememberWindowSize)saved?.heightDp ?: 340 else 340),size.second-dp(100)).coerceAtLeast(dp(220))
        val layout=params(width,height,true).apply {
            x=if(config.rememberWindowPosition&&saved!=null)((size.first-width)*saved.xFraction).toInt() else (size.first-width)/2
            y=if(config.rememberWindowPosition&&saved!=null)((size.second-height-dp(60))*saved.yFraction).toInt()+dp(8) else (size.second-height-dp(32))/2
        }
        clamp(layout)
        val root=OutsideFrame { if(!node.pinned) hideWindow(node) }.apply { background=outlinedCard(BACKGROUND,15);elevation=dp(8).toFloat();clipToOutline=true }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val header=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),0,dp(3),0)
            addView(ImageView(this@AssistantService).apply{setImageDrawable(prototypeIcon(when(node.kind){"photo"->R.drawable.ic_line_scan;"audio"->R.drawable.ic_line_audio;else->R.drawable.ic_line_text}));imageTintList=android.content.res.ColorStateList.valueOf(MUTED)},LinearLayout.LayoutParams(dp(15),dp(15)).apply{marginEnd=dp(7)})
            addView(label(when(node.kind){"photo"->"截图问答";"audio"->"听一段";else->"文字问答"},12f),LinearLayout.LayoutParams(0,-2,1f))
            node.pin=icon(R.drawable.ic_pin,"固定窗口，点外部保持显示") {
                node.pinned=!node.pinned;updatePin(node)
            }.also { addView(it,LinearLayout.LayoutParams(dp(44),dp(44))) }
        }
        body.addView(header,LinearLayout.LayoutParams(-1,dp(44)))
        body.addView(View(this).apply{setBackgroundColor(BORDER)},LinearLayout.LayoutParams(-1,dp(1)))
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(8)) }
        node.content=content
        node.source=label("",11f,true).apply { isClickable=true;accessible(when(node.kind){"photo"->"展开本次截图";"audio"->"展开转写原文";else->"展开所选原文"});setOnClickListener {
            if(node.kind=="photo")node.sourceImage?.let{it.visibility=if(it.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
            else if(node.kind=="text") {node.originalExpanded=!node.originalExpanded;node.transcript?.apply{maxLines=if(node.originalExpanded)Int.MAX_VALUE else 4;ellipsize=if(node.originalExpanded)null else android.text.TextUtils.TruncateAt.END}}
            else node.transcript?.let{it.visibility=if(it.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        }}.also{content.addView(it)}
        node.sourceImage=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.GONE;accessible("本次截图预览");node.frame?.let{setImageBitmap(it.bitmap)} }.also{content.addView(it,LinearLayout.LayoutParams(-1,dp(110)))}
        node.transcript=label("",12f,true).apply { visibility=View.GONE;setTextIsSelectable(true);setLineSpacing(0f,1.5f);setPadding(0,dp(8),0,dp(8)) }.also{content.addView(it)}
        if(node.kind=="text")node.transcript?.apply{visibility=View.VISIBLE;maxLines=if(node.originalExpanded)Int.MAX_VALUE else 4;ellipsize=if(node.originalExpanded)null else android.text.TextUtils.TruncateAt.END}
        node.response=label("",13f).apply { setTextIsSelectable(true);setLineSpacing(0f,1.65f);setPadding(0,dp(9),0,0);movementMethod=android.text.method.LinkMovementMethod.getInstance() }.also{content.addView(it)}
        node.status=label("",11f,true).apply{setPadding(0,dp(6),0,0)}.also{content.addView(it)}
        val scroll=ScrollView(this).apply { addView(content);isFillViewport=true;isVerticalScrollBarEnabled=true }
        node.scroll=scroll;body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply { gravity=Gravity.END;setPadding(dp(8),0,dp(8),0) }
        node.cancel=icon(android.R.drawable.ic_menu_close_clear_cancel,"取消当前回答") { node.chat.cancel() }.also{controls.addView(it,LinearLayout.LayoutParams(dp(44),dp(44)))}
        node.retry=icon(android.R.drawable.ic_popup_sync,"重试原问题与证据") { if(!gathering)node.chat.retry() }.also{controls.addView(it,LinearLayout.LayoutParams(dp(44),dp(44)))}
        node.copy=icon(R.drawable.ic_copy,"复制最近回答") {
            val text=node.chat.partial.ifBlank { node.chat.messages.lastOrNull{it.role=="assistant"}?.text.orEmpty() }
            if(text.isNotBlank()){getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide",text));toast("已复制")}
        }.also{controls.addView(it,LinearLayout.LayoutParams(dp(44),dp(44)))}
        body.addView(controls,LinearLayout.LayoutParams(-1,-2))
        body.addView(View(this).apply{setBackgroundColor(BORDER)},LinearLayout.LayoutParams(-1,dp(1)))
        val composer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(9)) }
        if(node.kind=="photo")node.imageChoice=Spinner(this).apply {
            adapter=ArrayAdapter(this@AssistantService,android.R.layout.simple_spinner_dropdown_item,listOf("沿用本次截图","发送时更新画面","仅文字上下文"))
            setSelection(node.imageMode);contentDescription="图片上下文"
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){node.imageMode=position;(view as? TextView)?.apply{setTextColor(MUTED);textSize=11f}}
                override fun onNothingSelected(parent:AdapterView<*>?){}
            }
        }.also{composer.addView(it,LinearLayout.LayoutParams(-1,dp(28)))}
        val row=LinearLayout(this).apply { gravity=Gravity.BOTTOM }
        node.input=EditText(this).apply {
            hint="输入问题或继续追问…";setHintTextColor(MUTED);setTextColor(INK);textSize=13f;minLines=1;maxLines=3;includeFontPadding=false;minHeight=dp(40)
            setText(node.draft);background=card(SURFACE,dp(8).toFloat());setPadding(dp(10),dp(8),dp(10),dp(8));accessible("${when(node.kind){"photo"->"截图";"audio"->"音频";else->"文字"}}追问")
            filters=arrayOf(android.text.InputFilter.LengthFilter(12000))
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){node.draft=s?.toString().orEmpty()};override fun afterTextChanged(s:Editable?){} })
        }.also{row.addView(it,LinearLayout.LayoutParams(0,-2,1f))}
        node.send=icon(android.R.drawable.ic_menu_send,"发送问题") { sendQuestion(node,node.draft) }.apply{background=android.graphics.drawable.InsetDrawable(card(ACCENT,dp(8).toFloat()),dp(5),dp(4),dp(5),dp(4));imageTintList=android.content.res.ColorStateList.valueOf(ON_ACCENT)}.also{row.addView(it,LinearLayout.LayoutParams(dp(44),dp(44)).apply{marginStart=dp(5)})}
        composer.addView(row);body.addView(composer,LinearLayout.LayoutParams(-1,-2));root.addView(body,FrameLayout.LayoutParams(-1,-1))
        val resize=icon(R.drawable.ic_resize,"拖动调整窗口大小") {}.apply { alpha=.55f;setPadding(dp(20),dp(20),dp(1),dp(1));background=null;minimumWidth=0;minimumHeight=0 }
        root.addView(resize,FrameLayout.LayoutParams(dp(32),dp(32),Gravity.BOTTOM or Gravity.END))
        installDrag(node,header,root,layout,false);installDrag(node,resize,root,layout,true)
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener{_,insets->
            val ime=insets.getInsets(WindowInsets.Type.ime()).bottom
            if(ime>0&&node.root===root){val bottom=screenSize().second-ime-dp(12);val y=layout.y.coerceAtMost((bottom-layout.height).coerceAtLeast(dp(8)));if(y!=layout.y){layout.y=y;runCatching{windows.updateViewLayout(root,layout)}}}
            insets
        }
        try{windows.addView(root,layout);node.root=root;node.layout=layout;updatePin(node);refresh(node)}catch(_:RuntimeException){toast("无法打开结果窗口，请检查悬浮权限")}
        removeBubble();showBubble()
    }
    private fun updatePin(node:FloatingConversation){node.pin?.apply{imageTintList=android.content.res.ColorStateList.valueOf(if(node.pinned)ACCENT else MUTED);accessible(if(node.pinned)"取消固定，之后点外部收起" else "固定窗口，点外部保持显示")}}
    private fun clamp(layout:WindowManager.LayoutParams){val size=screenSize();layout.width=layout.width.coerceIn(minOf(dp(220),size.first-dp(16)),size.first-dp(16));layout.height=layout.height.coerceIn(minOf(dp(220),size.second-dp(80)),size.second-dp(80));layout.x=layout.x.coerceIn(dp(8),(size.first-layout.width-dp(8)).coerceAtLeast(dp(8)));layout.y=layout.y.coerceIn(dp(8),(size.second-layout.height-dp(60)).coerceAtLeast(dp(8)))}
    private fun installDrag(node:FloatingConversation,handle:View,root:View,layout:WindowManager.LayoutParams,resizing:Boolean){
        var downX=0f;var downY=0f;var ox=0;var oy=0;var ow=0;var oh=0
        handle.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;ox=layout.x;oy=layout.y;ow=layout.width;oh=layout.height;true}
            MotionEvent.ACTION_MOVE->{val dx=(event.rawX-downX).toInt();val dy=(event.rawY-downY).toInt();if(resizing){layout.width=ow+dx;layout.height=oh+dy}else{layout.x=ox+dx;layout.y=oy+dy};clamp(layout);if(node.root===root)windows.updateViewLayout(root,layout);true}
            MotionEvent.ACTION_UP->{val size=screenSize();val density=resources.displayMetrics.density;preferences.saveWindow(node.kind,SavedWindow(layout.x.toFloat()/(size.first-layout.width).coerceAtLeast(1),(layout.y-dp(8)).toFloat()/(size.second-layout.height-dp(60)).coerceAtLeast(1),(layout.width/density).toInt(),(layout.height/density).toInt()));true}
            else->true
        }}
    }
    private fun refresh(node:FloatingConversation){
        if(!alive||node.detached)return
        ProbeState.requesting=nodes.values.any{it.chat.busy};bubbleVisual?.working=ProbeState.requesting
        node.source?.text=when(node.kind){"photo"->imageDescription(node)+"  ▾";"text"->"所选原文  ▾";else->if(node.chat.transcript.isNotBlank())"转写原文 · ${String.format(Locale.getDefault(),"%.1f",node.clip?.seconds ?: 0f)} 秒  ▾" else "内部音频 · 不附图"}
        node.transcript?.text=if(node.kind=="text")node.chat.sourceText else node.chat.transcript
        val text=buildString{
            node.chat.messages.takeLast(12).forEachIndexed{index,m->if(!(node.automaticFirstQuestion&&index==0&&m.role=="user")){if(m.role=="user")append("### 你\n");append(m.text).append("\n\n")}}
            if(node.chat.question.isNotBlank()&&!(node.automaticFirstQuestion&&node.chat.messages.isEmpty()))append("### 你\n${node.chat.question}\n\n")
            append(node.chat.partial)
        }.takeLast(100000)
        val scroll=node.scroll;val nearBottom=scroll==null||(scroll.scrollY+scroll.height>=(node.content?.height ?: 0)-dp(48))
        node.response?.text=if(node.chat.busy)text else MarkdownText.render(text)
        node.status?.text=if(text.isBlank()&&!node.chat.busy)"${when(node.kind){"photo"->"截图";"audio"->"录音";else->"原文"}}已就绪，输入问题后发送" else node.chat.status
        node.send?.isEnabled=!node.chat.busy&&!gathering&&recorder==null
        node.cancel?.visibility=if(node.chat.busy)View.VISIBLE else View.GONE
        node.retry?.visibility=if(node.chat.canRetry&&!gathering)View.VISIBLE else View.GONE
        node.copy?.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE
        node.imageChoice?.isEnabled=!node.chat.busy&&!gathering
        if(nearBottom)scroll?.post{if(node.scroll===scroll)scroll.fullScroll(View.FOCUS_DOWN)}
        if(!node.chat.busy&&node.chat.status!=lastFinished){lastFinished=node.chat.status;ProbeState.lastEvent=node.chat.status}
    }
    private fun imageDescription(node:FloatingConversation):String{val f=node.frame?:return "无可用截图";return "${if(node.bounds!=null)"选区" else "截图"} ${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(f.capturedAt))}"}
    private fun archivePrepared(node:FloatingConversation){
        runCatching{HistoryStore(this).save(node.chat.snapshot().copy(title=when(node.kind){"photo"->"截图待提问";"audio"->"音频待提问";else->node.chat.sourceText.take(60)},evidence=when(node.kind){"photo"->imageDescription(node);"audio"->"内部录音，等待提问";else->"选中文字"},status="等待提问"))}.onFailure{toast("本机历史保存失败，请检查存储空间")}
        persistImage(node)
    }
    private fun persistImage(node:FloatingConversation){val bitmap=node.frame?.bitmap?:return;if(!preferences.load().saveHistoryImages)return;val id=node.chat.conversationId;mediaWorker.execute{runCatching{val history=HistoryStore(this);val media=ConversationMediaStore(this);if(preferences.load().saveHistoryImages&&history.find(id)!=null){media.saveImage(id,bitmap);if(!preferences.load().saveHistoryImages||history.find(id)==null)media.deleteImage(id)}}.onFailure{main.post{if(alive)toast("回答可继续，但历史图片保存失败")}}}}
    private fun submitDefault(node:FloatingConversation){val c=readApiConfig()?:return;node.automaticFirstQuestion=true;sendQuestion(node,when(node.kind){"photo"->c.defaultQuestion;"audio"->c.audioQuestion;else->c.textQuestion},c)}
    private fun readApiConfig():AppConfig?=try{SettingsStore(this).load()}catch(_:Exception){toast("无法读取 API 配置，请在设置中重新保存密钥");null}
    private fun sendQuestion(node:FloatingConversation,text:String,frozen:AppConfig?=null){
        if(node.chat.busy||gathering||recorder!=null){toast("请先结束当前采集或请求");return}
        if(text.isBlank()){toast("请输入问题");return}
        val c=frozen?:readApiConfig()?:return
        if(c.apiKey.isBlank()){toast("请在设置 → API 连接中填写密钥");return}
        if(node.kind=="audio"&&node.clip!=null&&node.clip!!.peak<.0001){toast("录音没有有效音量，请重新采集");return}
        val submit=submit@{
            if(node.detached||nodes[node.kind]!==node)return@submit
            val image=if(node.kind=="photo"&&node.imageMode!=2)node.frame?.bitmap else null
            val evidence=when(node.kind){"photo"->if(image!=null)imageDescription(node) else "仅文字上下文";"audio"->"内部录音 ${String.format(Locale.getDefault(),"%.1f",node.clip?.seconds ?: 0f)} 秒 · 不附图";else->"选中文字与本段上下文"}
            try{node.chat.submit(c,text,image,evidence,if(node.kind=="audio"&&node.chat.transcript.isBlank())node.clip?.file else null);node.draft="";node.input?.setText("");persistImage(node);refresh(node)}catch(e:Exception){toast(e.message?:"无法发送")}
        }
        if(node.kind=="photo"&&node.imageMode==1)capture(node,false,true){submit()} else submit()
    }
    private fun captureNew(crop:Boolean){
        if(recorder!=null){toast("请先完成或取消录音");return}
        if(gathering){toast("正在取得画面，请稍候");return}
        if(frames==null){requestCaptureConsent(if(crop)AssistantActions.CROP else AssistantActions.SCREEN);return}
        val node=fresh("photo");capture(node,crop,false){archivePrepared(node);showWindow(node);if(config.autoAskImage)submitDefault(node)}
    }
    private fun capture(node:FloatingConversation,crop:Boolean,reuse:Boolean,after:()->Unit){
        if(!alive || node.detached || nodes[node.kind]!==node)return
        val source=frames?:run{requestCaptureConsent(AssistantActions.SCREEN){capture(node,crop,reuse,after)};return}
        val oldBounds=if(reuse)node.bounds else null;val oldSize=node.sourceSize;val oldRotation=node.rotation
        val rotation=screenRotation();val id=++captureEpoch
        gathering=true;captureOwner=node;removeMenu();removeBubble();removeSelector()
        val shown=nodes.values.filter{it.root!=null}.toList();shown.forEach{it.root?.visibility=View.INVISIBLE}
        val hiddenAt=System.nanoTime()
        fun restore(){shown.forEach{it.root?.visibility=View.VISIBLE};gathering=false;captureOwner=null;showBubble()}
        Choreographer.getInstance().postFrameCallback{Choreographer.getInstance().postFrameCallback{
            if(!alive||captureEpoch!=id)return@postFrameCallback
            source.snapshot(hiddenAt){result->
                if(!alive||captureEpoch!=id||node.detached||nodes[node.kind]!==node){result.getOrNull()?.bitmap?.recycle();return@snapshot}
                restore()
                result.fold(onSuccess={frame->
                    if(screenRotation()!=rotation||(oldBounds!=null&&(oldSize!=(frame.bitmap.width to frame.bitmap.height)||oldRotation!=rotation))){toast("屏幕尺寸或方向变化，请重新框选");showWindow(node);return@fold}
                    if(!reuse){node.bounds=null;node.sourceSize=null;node.rotation=null}
                    node.frame=if(oldBounds==null)frame else CapturedFrame(Bitmap.createBitmap(frame.bitmap,oldBounds.x,oldBounds.y,oldBounds.width,oldBounds.height),frame.capturedAt)
                    if(crop)showCrop(node,frame,rotation) else{node.sourceImage?.setImageBitmap(node.frame?.bitmap);after()}
                },onFailure={toast(it.message?:"取图失败，请重试");if(reuse)showWindow(node)})
            }
        }}
    }
    private fun showCrop(node:FloatingConversation,frame:CapturedFrame,rotation:Int){
        removeBubble();hideUnpinned();removeSelector()
        val crop=CropView(this,frame.bitmap)
        val body=column().apply{background=card(BACKGROUND);addView(label("拖动选择画面",18f));addView(crop,LinearLayout.LayoutParams(-1,0,1f))
            action("使用选区",true){val bounds=crop.selectedBounds();val bitmap=crop.selected();if(bounds==null||bitmap==null){toast("请先拖动选择足够大的区域");return@action};node.bounds=bounds;node.sourceSize=frame.bitmap.width to frame.bitmap.height;node.rotation=rotation;node.frame=CapturedFrame(bitmap,frame.capturedAt);node.imageMode=0;removeSelector();archivePrepared(node);showWindow(node);if(config.autoAskImage)submitDefault(node)}
            action("取消"){removeSelector();node.frame=null;showBubble()}
        }
        val size=screenSize();try{windows.addView(body,params(size.first,size.second-dp(60),false));selector=body}catch(_:RuntimeException){toast("无法打开框选");showBubble()}
    }
    private fun startRecording(){
        if(recorder!=null||finishingAudio||gathering)return
        if(projection==null || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestCaptureConsent(AssistantActions.AUDIO);return}
        val source=projection?:return
        if(nodes["audio"]?.chat?.busy==true){toast("请先等待或取消当前音频回答");showWindow(nodes.getValue("audio"));return}
        removeMenu();hideUnpinned();stopPlayback();audioManual=false
        val token=recordingGate.begin();val next=PlaybackRecorder(this,main);recorder=next
        try{next.start(source){result->
            if(!alive||recorder!==next||!recordingGate.complete(token)){result?.file?.delete();return@start}
            recorder=null;finishingAudio=false;ProbeState.recording=false
            if(result==null){toast("未保存有效录音，请检查权限和音源");return@start}
            val node=fresh("audio");node.clip=result;node.imageMode=2;archivePrepared(node);showWindow(node)
            if(result.peak<.0001)toast("未检测到有效声音，音源可能不允许采集")
            else if(config.autoAskAudio&&!audioManual)submitDefault(node)
        };ProbeState.recording=true;ProbeState.lastEvent="内部音频录制已开始";showBubble()}
        catch(e:Exception){recordingGate.cancel();recorder=null;next.stop(true);finishingAudio=false;ProbeState.recording=false;toast(e.message?:"无法开始内部录音")}
    }
    private fun finishRecording(manual:Boolean){val current=recorder?:return;if(finishingAudio)return;audioManual=manual;finishingAudio=true;current.stop(false)}
    private fun cancelRecording(notify:Boolean){
        val current=recorder
        recordingGate.cancel();recorder=null;finishingAudio=false;audioManual=false;ProbeState.recording=false
        current?.stop(true);removeMenu();cancelGestures();bubbleVisual?.invalidate()
        ProbeState.lastEvent=if(current!=null)"录音已取消并丢弃，没有发起请求" else "当前没有录音"
        if(notify)toast(ProbeState.lastEvent)
    }
    private fun stopPlayback(){player?.let{runCatching{it.stop()};it.release()};player=null}
    private fun playClip(){val file=nodes["audio"]?.clip?.file?:return;stopPlayback();try{val p=MediaPlayer();player=p;p.setDataSource(file.absolutePath);p.setOnPreparedListener{if(player===it)it.start()};p.setOnCompletionListener{stopPlayback()};p.setOnErrorListener{_,_,_->stopPlayback();toast("回听失败");true};p.prepareAsync();toast("正在回听上一段") }catch(_:Exception){stopPlayback();toast("无法回听")}}
    private fun detachToApplication(id:String):Bitmap?{
        val entry=nodes.entries.firstOrNull{it.value.chat.conversationId==id}?:return null
        val node=entry.value;hideWindow(node);node.detached=true;nodes.remove(entry.key)
        if(captureOwner===node){captureEpoch++;captureOwner=null;gathering=false;nodes.values.forEach{it.root?.visibility=View.VISIBLE};removeSelector();showBubble()}
        node.clip?.file?.let{ActiveConversationBridge.retainAudio(it)}
        ActiveConversationBridge.offer(id,HandedOffConversation(node.chat,node.frame?.bitmap,{
            node.chat.close();node.clip?.file?.let{ActiveConversationBridge.releaseAudio(it);it.delete()}
        },node.clip,node.draft))
        ProbeState.requesting=nodes.values.any{it.chat.busy}
        return node.frame?.bitmap
    }
    @Suppress("DEPRECATION") private fun screenRotation()=windows.defaultDisplay.rotation
    private fun clearCapture(){cancelRecording(false);captureEpoch++;captureOwner=null;gathering=false;frames?.close();frames=null;val old=projection;projection=null;old?.unregisterCallback(captureCallback);old?.stop();ProbeState.sharing=false;removeSelector();nodes.values.forEach{it.root?.visibility=View.VISIBLE}}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);captureEpoch++;captureOwner=null;gathering=false;removeMenu();removeSelector();removeBubble();nodes.values.forEach{hideWindow(it)};if(Build.VERSION.SDK_INT<34){val size=screenSize();frames?.resize(size.first,size.second)};showBubble()}
    override fun onDestroy(){
        alive=false;captureContinuation=null;main.removeCallbacksAndMessages(null);ActiveConversationBridge.detachHandler=null
        preferences.removeListener(preferencesListener);cancelRecording(false);stopPlayback()
        nodes.values.forEach{hideWindow(it);it.chat.close();it.clip?.file?.delete()};nodes.clear()
        clearCapture();removeMenu();removeBubble();removeSelector();mediaWorker.shutdown()
        ProbeState.serviceRunning=false;ProbeState.recording=false;ProbeState.requesting=false;ProbeState.lastEvent="助手与采集已停止"
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    private inner class OutsideFrame(private val outside:()->Unit):FrameLayout(this@AssistantService){
        override fun dispatchTouchEvent(event:MotionEvent):Boolean{if(event.actionMasked==MotionEvent.ACTION_OUTSIDE){outside();return false};return super.dispatchTouchEvent(event)}
    }
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    companion object { const val START="start";const val SHARE="share";const val STOP="stop";const val TEXT="process_text";private const val CHANNEL="assistant" }
}
