package com.alwayside.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantService : Service() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var bubble: ImageButton?=null
    private var panel: View?=null
    private var selector: View?=null
    private var projection: MediaProjection?=null
    private var frames: FrameSource?=null
    private var preview: CapturedFrame?=null
    private var requestId=0
    private var alive=true
    private var failure: String?=null
    private var ballX=0
    private var ballY=240
    private var draft=""
    private var message="选择眼前的内容，快速解释或自行提问。"
    private lateinit var chat: ChatSession
    private var responseView: TextView?=null
    private var requestStatus: TextView?=null
    private var transcriptView: TextView?=null
    private var sendButton: View?=null
    private var cancelButton: View?=null
    private var retryButton: View?=null
    private var imageMode=0 // 0 original, 1 update at send time, 2 none
    private var useAudio=false
    private var expanded=false
    private var gathering=false
    private var cropBounds: PixelCrop?=null
    private var cropSourceSize: Pair<Int,Int>?=null
    private var cropRotation: Int?=null
    private var lastCaptureRotation=0
    private var finishAction=0 // save, explain, ask, discard
    private var clipTime=0L
    private var recorder: PlaybackRecorder?=null
    private var clip: AudioClip?=null
    private var player: MediaPlayer?=null
    private var audioLabel: TextView?=null
    private var audioButton: Button?=null
    private var finishingAudio=false
    private val ticker=object : Runnable {
        override fun run() {
            if (!alive) return
            recorder?.let { current ->
                if (!finishingAudio) audioLabel?.text=String.format(Locale.getDefault(),"录制 %.1f 秒 · 音量活动 %.0f%%",current.seconds,current.level*100)
                bubble?.background=card(0xFF753E38.toInt(),dp(30).toFloat())
                bubble?.contentDescription="正在录制内部音频，点击小球结束录制"
            }
            main.postDelayed(this,500)
        }
    }
    private val captureCallback=object : MediaProjection.Callback() {
        override fun onStop() {
            if (!alive) return
            clearCapture()
            message="屏幕共享已停止，请从主界面重新授权。"
            ProbeState.lastEvent=message
            removePanel(); removeSelector(); showBubble(); foreground(false)
        }
        override fun onCapturedContentResize(width: Int,height: Int) { frames?.resize(width,height) }
    }
    override fun onCreate() {
        super.onCreate()
        chat=ChatSession(this) { refreshChat() }
        imageMode=if (runCatching { SettingsStore(this).load().autoUpdateImage }.getOrDefault(false)) 1 else 0
        windows=getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"悬浮助手与采集",NotificationManager.IMPORTANCE_LOW))
        ProbeState.serviceRunning=true
        cacheDir.listFiles()?.filter { it.name.startsWith("listening-") && it.extension=="wav" }?.forEach { it.delete() }
        main.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if (intent?.action==STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            foreground(intent?.action==SHARE || projection!=null)
            if (intent?.action==SHARE && projection==null) {
                @Suppress("DEPRECATION")
                val consent: Intent?=if (Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("consent",Intent::class.java)
                    else intent.getParcelableExtra("consent")
                if (consent!=null) {
                    val acquired=getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(intent.getIntExtra("resultCode",0),consent)
                        ?: error("系统没有返回有效的屏幕共享授权")
                    projection=acquired
                    acquired.registerCallback(captureCallback,main)
                    val size=screenSize()
                    frames=FrameSource(acquired,size.first,size.second,resources.displayMetrics.densityDpi,main)
                    ProbeState.sharing=true
                    message="已共享屏幕。返回目标页面后取图。"
                }
            }
            ProbeState.lastEvent=message; showBubble()
            if (intent?.action=="resume_history") {
                val saved=HistoryStore(this).list().firstOrNull { it.id==intent.getStringExtra("historyId") }
                if (saved!=null) { newConversation(saved); showPanel() }
            }
        } catch (e: Exception) {
            failure="开启失败：${e.javaClass.simpleName} ${e.message.orEmpty().take(180)}"
            ProbeState.lastEvent=failure!!
            toast(ProbeState.lastEvent); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun foreground(capturing: Boolean) {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,AssistantService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle(if (capturing) "AlwaySide · 屏幕共享中" else "AlwaySide · 悬浮球已开启")
            .setContentText("点击返回设置，或停止所有采集").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
        val type=if (Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        else if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        startForeground(100,notification,type)
    }
    private fun params(width: Int,height: Int,focusable: Boolean)=WindowManager.LayoutParams(
        width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity=Gravity.TOP or Gravity.START
        softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
    }
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int,Int> = if (Build.VERSION.SDK_INT>=30)
        windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    else android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    private fun showBubble() {
        if (!alive || bubble!=null || panel!=null || selector!=null) return
        val view=ImageButton(this).apply {
            setImageResource(R.drawable.ic_assistant)
            background=card(SURFACE,dp(30).toFloat()); elevation=dp(6).toFloat()
            setPadding(dp(3),dp(3),dp(3),dp(3)); accessible("AlwaySide，点击打开，长按框选，拖动移动")
            setOnClickListener { if (recorder!=null) finishRecording(1); showPanel() }
        }
        val size=screenSize()
        val layout=params(dp(56),dp(56),false).apply {
            x=ballX.coerceIn(0,(size.first-dp(56)).coerceAtLeast(0))
            y=ballY.coerceIn(0,(size.second-dp(100)).coerceAtLeast(0))
        }
        var downX=0f; var downY=0f; var originX=0; var originY=0; var dragging=false
        val slop=ViewConfiguration.get(this).scaledTouchSlop
        val gestures=GestureDetector(this,object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent)=true
            override fun onSingleTapUp(e: MotionEvent): Boolean { if (!dragging) view.performClick(); return true }
            override fun onLongPress(e: MotionEvent) { if (!dragging) capture(true) }
        })
        view.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX=event.rawX; downY=event.rawY; originX=layout.x; originY=layout.y; dragging=false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (kotlin.math.abs(event.rawX-downX)>slop || kotlin.math.abs(event.rawY-downY)>slop)) {
                        dragging=true
                        MotionEvent.obtain(event).let { it.action=MotionEvent.ACTION_CANCEL; gestures.onTouchEvent(it); it.recycle() }
                    }
                    if (dragging && bubble===view) {
                        layout.x=(originX+event.rawX-downX).toInt().coerceIn(0,(size.first-dp(56)).coerceAtLeast(0))
                        layout.y=(originY+event.rawY-downY).toInt().coerceIn(0,(size.second-dp(100)).coerceAtLeast(0))
                        windows.updateViewLayout(view,layout)
                    }
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> if (dragging && bubble===view) {
                    layout.x=if (layout.x<size.first/2) 0 else (size.first-dp(56)).coerceAtLeast(0)
                    ballX=layout.x; ballY=layout.y; windows.updateViewLayout(view,layout)
                }
            }
            if (!dragging) gestures.onTouchEvent(event)
            true
        }
        try { windows.addView(view,layout); bubble=view }
        catch (e: RuntimeException) { failure="悬浮权限不可用：${e.javaClass.simpleName}"; ProbeState.lastEvent=failure!!; stopSelf() }
    }
    private fun removeBubble() { bubble?.let { windows.removeView(it) }; bubble=null }
    private fun removePanel() {
        responseView=null; requestStatus=null; transcriptView=null; sendButton=null; cancelButton=null; retryButton=null
        audioLabel=null; audioButton=null
        stopPlayback()
        panel?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)
            windows.removeView(it)
        }; panel=null
    }
    private fun removeSelector() { selector?.let { windows.removeView(it) }; selector=null }
    private fun closeButton(action: () -> Unit)=ImageButton(this).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        imageTintList=android.content.res.ColorStateList.valueOf(INK)
        background=card(SURFACE); accessible("收起"); setOnClickListener { action() }
    }
    private fun icon(resource: Int, title: String, action: () -> Unit)=ImageButton(this).apply {
        setImageResource(resource); imageTintList=android.content.res.ColorStateList.valueOf(INK)
        background=card(SURFACE); accessible(title); setOnClickListener { action() }
    }
    private fun showPanel() {
        if (!alive) return
        removeBubble(); removePanel(); removeSelector()
        val body=column().apply {
            background=card(BACKGROUND)
            addView(LinearLayout(this@AssistantService).apply {
                addView(label("AlwaySide",22f),LinearLayout.LayoutParams(0,-2,1f))
                addView(icon(android.R.drawable.ic_menu_add,"新会话") { newConversation(); showPanel() },LinearLayout.LayoutParams(dp(44),dp(44)))
                addView(icon(android.R.drawable.ic_menu_crop,"展开／缩小面板") { expanded=!expanded; showPanel() },LinearLayout.LayoutParams(dp(44),dp(44)))
                addView(closeButton { removePanel(); showBubble() },LinearLayout.LayoutParams(dp(44),dp(44)))
            })
            addView(label(message,14f,true))
            requestStatus=label("",13f,true).also { addView(it) }
            responseView=label("",16f).also { it.setTextIsSelectable(true); it.movementMethod=android.text.method.LinkMovementMethod.getInstance(); addView(it) }
            transcriptView=label("",14f,true).also { it.setTextIsSelectable(true); addView(it) }
            addView(LinearLayout(this@AssistantService).apply {
                addView(icon(R.drawable.ic_copy,"复制最近回答") {
                    val answer=chat.partial.ifEmpty { chat.messages.lastOrNull { it.role=="assistant" }?.text.orEmpty() }
                    if (answer.isNotEmpty()) {
                        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("AlwaySide",answer)); toast("已复制")
                    }
                },LinearLayout.LayoutParams(dp(44),dp(44)))
                cancelButton=icon(android.R.drawable.ic_menu_close_clear_cancel,"取消当前回答") { chat.cancel() }.also { addView(it,LinearLayout.LayoutParams(dp(44),dp(44))) }
                retryButton=icon(android.R.drawable.ic_popup_sync,"重试上次请求（使用原问题和证据）") { if (!gathering) chat.retry() }.also { addView(it,LinearLayout.LayoutParams(dp(44),dp(44))) }
                addView(icon(android.R.drawable.ic_menu_preferences,"模型与指令设置") {
                    startActivity(Intent(this@AssistantService,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); removePanel(); showBubble()
                },LinearLayout.LayoutParams(dp(44),dp(44)))
            })
            preview?.let { frame ->
                addView(ImageView(this@AssistantService).apply {
                    setImageBitmap(frame.bitmap); scaleType=ImageView.ScaleType.FIT_CENTER; accessible("本次候选图片")
                },LinearLayout.LayoutParams(-1,dp(120)))
                addView(label(imageDescription(),12f,true))
            }
            action("解释全屏",true) { capture(false) { imageMode=0; submitDefault(false) } }
            action("整屏自行提问") { capture(false) { imageMode=0; showPanel() } }
            action("框选画面") { capture(true) }
            addView(label("提问时附图",14f,true))
            addView(Spinner(this@AssistantService).apply {
                adapter=ArrayAdapter(this@AssistantService,android.R.layout.simple_spinner_dropdown_item,listOf("沿用原图","更新画面（沿用选区）","不附图"))
                setSelection(imageMode)
                onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { imageMode=position }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            })
            addView(EditText(this@AssistantService).apply {
                hint="输入问题或继续追问…"; setHintTextColor(MUTED); setTextColor(INK); setText(draft)
                minLines=2; maxLines=5; textSize=16f; filters=arrayOf(android.text.InputFilter.LengthFilter(12000))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { draft=s?.toString().orEmpty() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            },LinearLayout.LayoutParams(-1,-2))
            sendButton=icon(android.R.drawable.ic_menu_send,"发送问题") { sendQuestion(draft) }.also { addView(it,LinearLayout.LayoutParams(-1,dp(48))) }
            addView(label("发送所选证据与最近文字上下文；不附图不会移除旧讨论文字，新会话可清空上下文。",12f,true))
            addView(label("听一段",20f))
            audioLabel=label(audioStatus(),14f,true).also { addView(it) }
            if (recorder==null) {
                audioButton=action("开始听内部声音") { startRecording() }.apply { isEnabled=!chat.busy && !gathering }
            } else {
                audioButton=action("结束并解释",true) { finishRecording(1) }.apply { isEnabled=!finishingAudio }
                action("结束后自行提问") { finishRecording(2) }.isEnabled=!finishingAudio
                action("取消这段录音") { finishRecording(3) }.isEnabled=!finishingAudio
            }
            if (clip!=null && recorder==null) {
                addView(CheckBox(this@AssistantService).apply {
                    text="提问附带这段录音（已转写时复用原文）"; setTextColor(INK); isChecked=useAudio
                    setOnCheckedChangeListener { _,checked -> useAudio=checked }
                })
                action("解释这段录音") { submitDefault(true) }.isEnabled=!chat.busy
                action("回听上一段") { playClip() }
                action("丢弃录音") { if (!chat.busy) { stopPlayback(); clip?.file?.delete(); clip=null; useAudio=false; showPanel() } }.isEnabled=!chat.busy
            }
            action("返回设置与历史") {
                startActivity(Intent(this@AssistantService,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); removePanel(); showBubble()
            }
            action("停止助手与请求") { stopSelf() }
        }
        val scroll=ScrollView(this).apply { background=card(BACKGROUND); addView(body); isFillViewport=true }
        val size=screenSize()
        val layout=params(if (expanded) size.first-dp(16) else minOf(dp(380),size.first-dp(24)),
            if (expanded) size.second-dp(80) else minOf(dp(710),size.second-dp(100)),true).apply { x=(size.first-width)/2; y=dp(32) }
        try { windows.addView(scroll,layout); panel=scroll; refreshChat() }
        catch (e: RuntimeException) { toast("无法打开悬浮面板"); showBubble() }
    }
    private fun refreshChat() {
        if (!alive) return
        requestStatus?.text="${chat.status}\n本轮：${chat.evidence}"
        val text=buildString {
            chat.messages.takeLast(8).forEach { append(if (it.role=="user") "### 你\n" else "### AlwaySide\n"); append(it.text); append("\n\n") }
            if (chat.question.isNotEmpty()) { append("### 你\n${chat.question}\n\n"); append(chat.partial) }
        }.takeLast(100_000)
        responseView?.text=if (chat.busy) text else MarkdownText.render(text)
        transcriptView?.text=if (chat.transcript.isEmpty()) "" else "转写原文：\n${chat.transcript}"
        sendButton?.isEnabled=!chat.busy && !gathering && recorder==null
        cancelButton?.isEnabled=chat.busy
        retryButton?.isEnabled=chat.canRetry && !gathering && recorder==null
        ProbeState.lastEvent=chat.status
        ProbeState.requesting=chat.busy
        if (recorder==null) bubble?.background=card(if (chat.busy) 0xFF35566A.toInt() else SURFACE,dp(30).toFloat())
    }
    private fun imageDescription(): String {
        val frame=preview ?: return "无图片"
        val time=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt))
        return "${if (cropBounds!=null) "选区" else "整屏"} $time · ${frame.bitmap.width} × ${frame.bitmap.height}"
    }
    private fun newConversation(saved: StoredConversation?=null) {
        requestId++; gathering=false; preview=null; cropBounds=null; cropSourceSize=null; cropRotation=null; draft=""; useAudio=false
        recorder?.stop(true); recorder=null; finishingAudio=false; finishAction=0; ProbeState.recording=false
        stopPlayback(); clip?.file?.delete(); clip=null
        chat.newConversation(saved)
        imageMode=if (runCatching { SettingsStore(this).load().autoUpdateImage }.getOrDefault(false)) 1 else 0
        message=if (saved==null) "选择画面或录音，开始一个新会话。" else "已恢复文字历史；重新取图或录音即可继续。"
    }
    private fun submitDefault(audio: Boolean) {
        showPanel()
        val config=runCatching { SettingsStore(this).load() }.getOrElse { toast("设置无法读取，请重新保存 API Key"); return }
        useAudio=audio
        sendQuestion(if (audio) config.audioQuestion else config.defaultQuestion,config)
    }
    private fun sendQuestion(text: String, frozen: AppConfig?=null) {
        if (chat.busy || gathering || recorder!=null) { toast("请先结束当前采集或请求"); return }
        if (text.isBlank()) { toast("请输入问题"); return }
        val config=frozen ?: runCatching { SettingsStore(this).load() }.getOrElse { toast("设置无法读取，请重新保存 API Key"); return }
        if (config.apiKey.isBlank()) { toast("请先在设置填写 API Key"); return }
        val audio=if (useAudio) clip else null
        if (audio!=null && audio.peak<0.0001) { toast("这段录音没有有效音量，请重新录制后再发送"); return }
        val mode=imageMode
        val submit={
            val image=if (mode==2) null else preview
            val label=(if (image==null) "不附图" else imageDescription()) +
                (if (audio!=null) " · 录音 ${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(clipTime))} / ${String.format(Locale.getDefault(),"%.1f",audio.seconds)} 秒" else " · 不附录音")
            try { chat.submit(config,text,image?.bitmap,label,audio?.file); draft=""; showPanel() }
            catch (e: Exception) { toast(e.message ?: "无法发送") }
        }
        if (mode==1) capture(false, reuse=true, after=submit) else submit()
    }
    private fun capture(crop: Boolean,reuse: Boolean=false,after: (() -> Unit)?=null) {
        if (chat.busy || gathering) { toast("请先等待或取消当前请求"); return }
        val source=frames
        if (source==null) { message="请先从主界面开启屏幕共享"; showPanel(); return }
        val previousCrop=if (reuse) cropBounds else null
        val previousSize=cropSourceSize
        val rotation=screenRotation()
        val id=++requestId
        gathering=true
        if (!reuse) { cropBounds=null; cropSourceSize=null; cropRotation=null }
        preview=null; removeBubble(); removePanel(); removeSelector()
        val hiddenAt=System.nanoTime()
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback secondFrame@{
                if (!alive || requestId!=id) return@secondFrame
                source.snapshot(hiddenAt) { result ->
                    if (!alive || requestId!=id) return@snapshot
                    gathering=false
                    result.fold(onSuccess={ frame ->
                        if (screenRotation()!=rotation || (previousCrop!=null &&
                            (previousSize!=(frame.bitmap.width to frame.bitmap.height) || cropRotation!=rotation))) {
                            message="屏幕尺寸或方向已变化，请重新框选后再发送。"; showPanel(); return@fold
                        }
                        lastCaptureRotation=rotation
                        preview=if (previousCrop==null) frame else CapturedFrame(Bitmap.createBitmap(frame.bitmap,previousCrop.x,previousCrop.y,previousCrop.width,previousCrop.height),frame.capturedAt)
                        message="已取得新画面，请确认预览是否为目标内容。"
                        ProbeState.lastEvent="取图成功 ${frame.bitmap.width}x${frame.bitmap.height}"
                        if (crop) showCrop(frame) else if (after!=null) after() else showPanel()
                    },onFailure={ error ->
                        message=error.message ?: "取图失败，请重试"; ProbeState.lastEvent=message; showPanel()
                    })
                }
            }
        }
    }
    private fun showCrop(frame: CapturedFrame) {
        val crop=CropView(this,frame.bitmap)
        fun accept(quick: Boolean) {
            val bounds=crop.selectedBounds()
            val selected=crop.selected()
            if (selected==null || bounds==null) { toast("请先拖出一个足够大的区域"); return }
            cropBounds=bounds; cropSourceSize=frame.bitmap.width to frame.bitmap.height
            cropRotation=lastCaptureRotation
            preview=CapturedFrame(selected,frame.capturedAt); imageMode=0; useAudio=false
            message="已裁剪，只发送所选区域"; removeSelector()
            if (quick) submitDefault(false) else showPanel()
        }
        val body=column().apply {
            background=card(BACKGROUND); addView(label("拖动框选 · 画面已冻结",20f))
            addView(crop,LinearLayout.LayoutParams(-1,0,1f))
            action("快速解释",true) { accept(true) }
            action("自行提问") { accept(false) }
            action("取消") { preview=null; removeSelector(); showBubble() }
        }
        val size=screenSize()
        try { windows.addView(body,params(size.first,size.second-dp(48),false)); selector=body }
        catch (e: RuntimeException) { message="无法显示框选界面"; showPanel() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestId++; gathering=false; removeSelector(); removePanel(); removeBubble(); showBubble()
        if (Build.VERSION.SDK_INT<34) { val size=screenSize(); frames?.resize(size.first,size.second) }
    }
    @Suppress("DEPRECATION")
    private fun screenRotation()=windows.defaultDisplay.rotation
    private fun clearCapture() {
        finishRecording()
        requestId++; gathering=false; frames?.close(); frames=null
        val old=projection; projection=null
        old?.unregisterCallback(captureCallback); old?.stop()
        ProbeState.sharing=false
    }
    override fun onDestroy() {
        alive=false; main.removeCallbacksAndMessages(null)
        chat.close()
        ProbeState.requesting=false
        recorder?.stop(true); stopPlayback(); clip?.file?.delete(); clip=null
        clearCapture(); removePanel(); removeSelector(); removeBubble()
        ProbeState.serviceRunning=false; ProbeState.recording=false; ProbeState.lastEvent=failure ?: "助手和采集已停止"
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun audioStatus(): String {
        if (finishingAudio) return "正在保存这段录音…"
        if (recorder!=null) return "内部音频录制中，最长 2 分钟"
        val last=clip ?: return "录下当前播放的一段声音，再在这里回听。"
        val activity=if (last.peak<0.0001) "没有检测到有效声音；可能是静音或音源不允许采集" else "已检测到音量活动，请回听确认内容"
        return String.format(Locale.getDefault(),"%.1f 秒 · %s%s",last.seconds,activity,last.error?.let { "\n$it" }.orEmpty())
    }
    private fun startRecording() {
        if (chat.busy || gathering) { toast("请先结束当前请求"); return }
        val source=projection
        if (source==null) { toast("请先从主界面共享屏幕"); return }
        if (recorder!=null || finishingAudio) return
        finishAction=0; useAudio=false
        stopPlayback(); clip?.file?.delete(); clip=null
        val next=PlaybackRecorder(this,main)
        recorder=next
        try {
            next.start(source) { result ->
                if (!alive || recorder!==next) { result?.file?.delete(); return@start }
                recorder=null; finishingAudio=false; clip=result; ProbeState.recording=false; clipTime=System.currentTimeMillis()
                val action=finishAction; finishAction=0
                message=if (result==null) "没有保存到有效录音，请检查权限与目标应用" else "录音已结束，可回听确认。"
                if (action==3) message="已取消这段录音"
                ProbeState.lastEvent=audioStatus()
                bubble?.background=card(SURFACE,dp(30).toFloat())
                bubble?.accessible("AlwaySide，点击打开，长按框选，拖动移动")
                if (result!=null && (action==1 || action==2)) {
                    useAudio=true; imageMode=2
                    if (action==1) submitDefault(true) else showPanel()
                } else if (panel!=null) showPanel() else {
                    showBubble(); toast(message)
                }
            }
            ProbeState.recording=true; ProbeState.lastEvent="内部播放音频录制已开始"
            message="正在听内部声音，点击结束可保存这一段。"
            showPanel()
        } catch (e: Exception) {
            next.stop(true); recorder=null; ProbeState.recording=false
            message=e.message ?: "无法开始内部录音"; ProbeState.lastEvent=message; showPanel()
        }
    }
    private fun finishRecording(action: Int=0) {
        if (recorder==null || finishingAudio) return
        finishAction=action
        finishingAudio=true; audioButton?.isEnabled=false; audioLabel?.text="正在结束录音…"
        recorder?.stop(action==3)
    }
    private fun stopPlayback() { player?.let { runCatching { it.stop() }; it.release() }; player=null }
    private fun playClip() {
        val source=clip ?: return
        stopPlayback()
        try {
            val playback=MediaPlayer(); player=playback
            playback.setDataSource(source.file.absolutePath)
            playback.setOnPreparedListener { if (player===it) { it.start(); audioLabel?.text="正在回听；收起面板将停止播放" } }
            playback.setOnCompletionListener { stopPlayback(); audioLabel?.text=audioStatus() }
            playback.setOnErrorListener { _,_,_ -> stopPlayback(); toast("无法播放这段录音"); true }
            playback.prepareAsync()
        } catch (e: Exception) { stopPlayback(); toast("回听失败，请重新录制") }
    }
    companion object {
        const val START="start"; const val SHARE="share"; const val STOP="stop"
        private const val CHANNEL="assistant"
    }
}
