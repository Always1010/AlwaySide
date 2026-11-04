package com.alwayside.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var diagnostics: TextView? = null
    private var assistantDialog: AlertDialog? = null
    private val refresh = object : Runnable {
        override fun run() {
            diagnostics?.text = ProbeState.diagnostics()
            if (assistantDialog?.isShowing == true) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    private fun showHome() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
            addView(appHeader(assistant = { showAssistantControls() }))
            addHistoryTabs(this@MainActivity)
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true
                addView(column().apply {
                    addConversationList(this@MainActivity, archiveShortcut = true) { showHome() }
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun showAssistantControls() {
        if (assistantDialog?.isShowing == true) return
        val content = column().apply {
            addView(label("悬浮助手", 18f))
            addView(label("快捷截图与听一段。应用内查看和追问不需要开启悬浮助手。", 12f, true).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            action("开启悬浮球", true) {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    toast("允许悬浮显示后，返回这里再次开启")
                } else {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
                    startAssistant(Intent(this@MainActivity, AssistantService::class.java).setAction(AssistantService.START))
                }
            }
            action("开启屏幕共享") { startSharing() }
            action("允许内部音频录制") {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
                else toast("音频权限已允许，共享屏幕后可从小球开始录音")
            }
            addView(label("声音来自允许采集的媒体应用，不使用麦克风；每段最多 2 分钟。", 11f, true).apply {
                setPadding(0, dp(8), 0, 0)
            })
            action("停止助手和所有采集") { stopService(Intent(this@MainActivity, AssistantService::class.java)) }
            addView(label("设备与运行状态", 14f).apply { setPadding(0, dp(18), 0, dp(8)) })
            diagnostics = label(ProbeState.diagnostics(), 11f, true).apply { setTextIsSelectable(true) }
            addView(diagnostics)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = android.view.Gravity.END
                addView(appIcon(R.drawable.ic_copy, "复制诊断信息") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AlwaySide", ProbeState.diagnostics()))
                    toast("已复制，不含图片、录音或密钥")
                })
            })
        }
        assistantDialog = AlertDialog.Builder(this).setView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); addView(content)
        }).setNegativeButton("返回对话", null).create().also { dialog ->
            dialog.setOnDismissListener { handler.removeCallbacks(refresh); diagnostics = null; assistantDialog = null }
            dialog.show()
        }
        handler.post(refresh)
    }

    private fun startSharing() {
        if (!Settings.canDrawOverlays(this)) { toast("请先允许悬浮球权限"); return }
        if (ProbeState.sharing) { toast("已共享，回到目标应用操作小球即可"); return }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(consent, 10)
    }

    @Deprecated("Platform result bridge for the dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK && data != null) {
            startAssistant(Intent(this, AssistantService::class.java).setAction(AssistantService.SHARE)
                .putExtra("resultCode", resultCode).putExtra("consent", data))
            toast("返回目标应用后，按你设置的手势操作小球")
        } else if (requestCode == 10) toast("未开启屏幕共享")
    }

    private fun startAssistant(intent: Intent) {
        try { startForegroundService(intent) }
        catch (e: RuntimeException) { toast("无法开启助手：${e.javaClass.simpleName}") }
    }

    override fun onResume() {
        super.onResume(); showHome()
        if (assistantDialog?.isShowing == true) handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { assistantDialog?.dismiss(); handler.removeCallbacks(refresh); super.onDestroy() }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
