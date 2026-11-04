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
    private var message="点击取图查看结果；本包不发送 AI 请求。"
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
            setOnClickListener { if (recorder!=null) finishRecording(); showPanel() }
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
    private fun showPanel() {
        if (!alive) return
        removeBubble(); removePanel(); removeSelector()
        val body=column().apply {
            background=card(BACKGROUND)
            addView(LinearLayout(this@AssistantService).apply {
                addView(label("眼前的内容",22f),LinearLayout.LayoutParams(0,-2,1f))
                addView(closeButton { removePanel(); showBubble() },LinearLayout.LayoutParams(dp(44),dp(44)))
            })
            addView(label(message,14f,true))
            preview?.let { frame ->
                addView(ImageView(this@AssistantService).apply {
                    setImageBitmap(frame.bitmap); adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER
                    accessible("已获取的画面预览")
                },LinearLayout.LayoutParams(-1,dp(220)))
                val time=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt))
                addView(label("$time · ${frame.bitmap.width} × ${frame.bitmap.height}",12f,true))
            }
            action("取整屏预览",true) { capture(false) }
            action("框选当前画面") { capture(true) }
            addView(label("听一段",20f))
            audioLabel=label(audioStatus(),14f,true).also { addView(it) }
            audioButton=action(if (recorder!=null) "结束录音" else "开始听内部声音") {
                if (recorder!=null) finishRecording() else startRecording()
            }.apply { isEnabled=!finishingAudio }
            if (clip!=null && recorder==null) {
                action("回听上一段") { playClip() }
                action("丢弃录音") { stopPlayback(); clip?.file?.delete(); clip=null; showPanel() }
            }
            addView(EditText(this@AssistantService).apply {
                hint="输入试试：你想问什么？"; setHintTextColor(MUTED); setTextColor(INK); setText(draft)
                minLines=2; maxLines=4; textSize=16f
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { draft=s?.toString().orEmpty() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            },LinearLayout.LayoutParams(-1,-2))
            addView(label("本包仅验证选图和输入，不发送问题。",12f,true))
            action("返回设置 / 查看诊断") {
                startActivity(Intent(this@AssistantService,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                removePanel(); showBubble()
            }
            action("停止助手") { stopSelf() }
        }
        val scroll=ScrollView(this).apply { background=card(BACKGROUND); addView(body); isFillViewport=true }
        val size=screenSize()
        val layout=params(minOf(dp(360),size.first-dp(24)),minOf(dp(650),size.second-dp(100)),true).apply {
            x=(size.first-width)/2; y=dp(40)
        }
        try { windows.addView(scroll,layout); panel=scroll }
        catch (e: RuntimeException) { toast("无法打开悬浮面板"); showBubble() }
    }
    private fun capture(crop: Boolean) {
        val source=frames
        if (source==null) { message="请先从主界面开启屏幕共享"; showPanel(); return }
        val id=++requestId
        preview=null; removeBubble(); removePanel(); removeSelector()
        val hiddenAt=System.nanoTime()
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback secondFrame@{
                if (!alive || requestId!=id) return@secondFrame
                source.snapshot(hiddenAt) { result ->
                    if (!alive || requestId!=id) return@snapshot
                    result.fold(onSuccess={ frame ->
                        preview=frame; message="已取得新画面，请确认预览是否为目标内容。"
                        ProbeState.lastEvent="取图成功 ${frame.bitmap.width}x${frame.bitmap.height}"
                        if (crop) showCrop(frame) else showPanel()
                    },onFailure={ error ->
                        message=error.message ?: "取图失败，请重试"; ProbeState.lastEvent=message; showPanel()
                    })
                }
            }
        }
    }
    private fun showCrop(frame: CapturedFrame) {
        val crop=CropView(this,frame.bitmap)
        val body=column().apply {
            background=card(BACKGROUND); addView(label("拖动框选 · 画面已冻结",20f))
            addView(crop,LinearLayout.LayoutParams(-1,0,1f))
            action("使用选区",true) {
                val selected=crop.selected()
                if (selected==null) toast("请先拖出一个足够大的区域")
                else { preview=CapturedFrame(selected,frame.capturedAt); message="已裁剪，只保留所选区域"; removeSelector(); showPanel() }
            }
            action("取消") { preview=null; removeSelector(); showBubble() }
        }
        val size=screenSize()
        try { windows.addView(body,params(size.first,size.second-dp(48),false)); selector=body }
        catch (e: RuntimeException) { message="无法显示框选界面"; showPanel() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestId++; removeSelector(); removePanel(); removeBubble(); showBubble()
        if (Build.VERSION.SDK_INT<34) { val size=screenSize(); frames?.resize(size.first,size.second) }
    }
    private fun clearCapture() {
        finishRecording()
        requestId++; preview=null; frames?.close(); frames=null
        val old=projection; projection=null
        old?.unregisterCallback(captureCallback); old?.stop()
        ProbeState.sharing=false
    }
    override fun onDestroy() {
        alive=false; main.removeCallbacksAndMessages(null)
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
        val source=projection
        if (source==null) { toast("请先从主界面共享屏幕"); return }
        if (recorder!=null || finishingAudio) return
        stopPlayback(); clip?.file?.delete(); clip=null
        val next=PlaybackRecorder(this,main)
        recorder=next
        try {
            next.start(source) { result ->
                if (!alive || recorder!==next) { result?.file?.delete(); return@start }
                recorder=null; finishingAudio=false; clip=result; ProbeState.recording=false
                message=if (result==null) "没有保存到有效录音，请检查权限与目标应用" else "录音已结束，可回听确认。"
                ProbeState.lastEvent=audioStatus()
                bubble?.background=card(SURFACE,dp(30).toFloat())
                bubble?.accessible("AlwaySide，点击打开，长按框选，拖动移动")
                if (panel!=null) showPanel() else {
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
    private fun finishRecording() {
        if (recorder==null || finishingAudio) return
        finishingAudio=true; audioButton?.isEnabled=false; audioLabel?.text="正在结束录音…"
        recorder?.stop()
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
