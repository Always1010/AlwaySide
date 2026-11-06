package com.alwayside.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/** Brief system-permission bridge for a gesture started from the floating assistant. */
class CapturePermissionActivity : ThemedActivity() {
    private var stage = "idle"
    private var pendingDelivery: Intent? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private val requestedAction: String? get() = intent.getStringExtra("requestedAction")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33) backCallback=android.window.OnBackInvokedCallback { cancel() }.also {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
        }
        stage = savedInstanceState?.getString("permission_stage") ?: "idle"
        setContentView(column().apply {
            setBackgroundColor(BACKGROUND); gravity = Gravity.CENTER_VERTICAL; fitsSystemWindows = true
            addView(label("AlwaySide", 20f))
            addView(label(if (requestedAction in setOf("audio", AssistantActions.START_AUDIO)) "允许系统授权后，开始听一段。" else "允许系统授权后，截取当前画面。", 12f, true).apply {
                setPadding(0, dp(10), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        })
        if (stage == "done") {
            // Consent tokens are deliberately not retained through recreation after completion.
            pendingDelivery = restoreIntent()
            finishPermissionFlow()
            return
        }
        if (stage == "idle") window.decorView.post { if (!isFinishing && stage == "idle") requestNextPermission() }
    }

    private fun requestNextPermission() {
        if (!ProbeState.serviceRunning) { stage = "done"; finishPermissionFlow(); return }
        if (requestedAction !in setOf("screen", "crop", "audio", AssistantActions.START_AUDIO)) {
            cancel("无法识别采集操作，请重试")
            return
        }
        try {
            if (requestedAction in setOf("audio", AssistantActions.START_AUDIO) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stage = "audio"
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21)
                return
            }
            if (intent.getBooleanExtra("needsCapture", true)) {
                stage = "capture"
                val manager = getSystemService(MediaProjectionManager::class.java)
                val consent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else manager.createScreenCaptureIntent()
                @Suppress("DEPRECATION")
                startActivityForResult(consent, 22)
            } else complete(Intent(this, AssistantService::class.java).setAction(AssistantService.START)
                .putExtra("requestedAction", requestedAction))
        } catch (_: RuntimeException) { cancel("无法请求采集权限，请重试") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 21 || stage != "audio") return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            stage = "idle"
            requestNextPermission()
        } else cancel("未允许音频采集，本次没有开始录制")
    }

    @Deprecated("Platform result bridge for the dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 22 || stage != "capture") return
        if (resultCode == RESULT_OK && data != null) {
            complete(Intent(this, AssistantService::class.java).setAction(AssistantService.SHARE)
                .putExtra("resultCode", resultCode).putExtra("consent", data).putExtra("requestedAction", requestedAction))
        } else cancel("已取消屏幕授权，本次没有开始采集")
    }

    private fun complete(serviceIntent: Intent) {
        if (stage == "done") return
        stage = "done"
        pendingDelivery = serviceIntent.putExtra(CapturePermissionGate.EXTRA_REQUEST_ID,
            intent.getStringExtra(CapturePermissionGate.EXTRA_REQUEST_ID))
        finishPermissionFlow()
    }

    private fun restoreIntent() = Intent(this, AssistantService::class.java).setAction(AssistantService.START)
        .putExtra(CapturePermissionGate.EXTRA_REQUEST_ID, intent.getStringExtra(CapturePermissionGate.EXTRA_REQUEST_ID))

    @Suppress("DEPRECATION")
    private fun finishPermissionFlow() {
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0)
    }

    private fun cancel(message: String? = null) {
        if (stage == "done") return
        stage = "done"
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        pendingDelivery = restoreIntent()
        finishPermissionFlow()
    }

    @Deprecated("Legacy API 29–32 bridge; newer systems use OnBackInvokedCallback")
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if(Build.VERSION.SDK_INT<33)cancel() else super.onBackPressed() }

    override fun onDestroy() {
        if(Build.VERSION.SDK_INT>=33)backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        val delivery = pendingDelivery.takeIf { isFinishing && !isChangingConfigurations }
        pendingDelivery = null
        super.onDestroy()
        // Dispatch only after this permission task has left, and never resurrect a stopped service.
        if (delivery != null && ProbeState.serviceRunning) {
            try { applicationContext.startForegroundService(delivery) }
            catch (_: RuntimeException) {
                Toast.makeText(applicationContext, "无法开始采集，请重试", Toast.LENGTH_LONG).show()
                if (ProbeState.serviceRunning && (delivery.action == AssistantService.SHARE || delivery.hasExtra("requestedAction")))
                    runCatching { applicationContext.startForegroundService(restoreIntent()) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("permission_stage", stage)
        super.onSaveInstanceState(outState)
    }
}
