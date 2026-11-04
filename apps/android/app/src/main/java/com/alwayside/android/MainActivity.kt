package com.alwayside.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() { status.text = ProbeState.diagnostics(); handler.postDelayed(this,1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = column().apply {
            addView(label("AlwaySide", 30f))
            addView(label("随手理解眼前的内容", 20f))
            addView(label("设备能力测试版 · 暂不连接 AI", muted = true))
            addView(label("先开悬浮球，再共享屏幕。回到你要阅读的应用，点击小球打开面板，长按直接框选。",muted=true))
            action("开启悬浮球",true) {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                    toast("允许悬浮显示后，返回这里再次开启")
                } else {
                    if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),12)
                    startAssistant(Intent(this@MainActivity,AssistantService::class.java).setAction(AssistantService.START))
                }
            }
            action("开启屏幕共享") { startSharing() }
            action("允许内部音频录制") {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),11)
                else toast("音频权限已允许，共享屏幕后可从小球开始录音")
            }
            addView(label("声音仅来自允许采集的媒体应用，不使用麦克风。每段最多 2 分钟，结束后可回听。",muted=true))
            action("停止助手和所有采集") { stopService(Intent(this@MainActivity,AssistantService::class.java)) }
            addView(label("设备与运行状态",22f))
            status=label(ProbeState.diagnostics(),14f,true).apply { setTextIsSelectable(true) }
            addView(status)
            action("复制诊断信息") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide",ProbeState.diagnostics()))
                toast("已复制，不含图片、录音或密钥")
            }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); isFillViewport = true; fitsSystemWindows = true
            addView(content)
        })
    }
    private fun startSharing() {
        if (!Settings.canDrawOverlays(this)) { toast("请先允许悬浮球权限"); return }
        if (ProbeState.sharing) { toast("已共享，回到目标应用操作小球即可"); return }
        val manager=getSystemService(MediaProjectionManager::class.java)
        val consent=if (Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(consent,10)
    }
    @Deprecated("Platform result bridge for the dependency-free probe")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode==10 && resultCode==RESULT_OK && data!=null) {
            startAssistant(Intent(this,AssistantService::class.java).setAction(AssistantService.SHARE)
                .putExtra("resultCode",resultCode).putExtra("consent",data))
            toast("返回目标应用后，点击或长按小球")
        } else if (requestCode==10) toast("未开启屏幕共享")
    }
    private fun startAssistant(intent: Intent) {
        try { startForegroundService(intent) }
        catch (e: RuntimeException) { toast("无法开启助手：${e.javaClass.simpleName}") }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
}
