package com.alwayside.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {
    private val onboarding by lazy { getSharedPreferences("assistant_onboarding", MODE_PRIVATE) }
    private var assistantStartAttempted = false
    private var overlayWasGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistantStartAttempted = savedInstanceState?.getBoolean("assistant_start_attempted", false) ?: false
        overlayWasGranted = savedInstanceState?.getBoolean("overlay_was_granted", false) ?: false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        assistantStartAttempted = false
    }

    private fun showHome() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
            addView(appHeader())
            addHistoryTabs(this@MainActivity)
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true
                addView(column().apply {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        addView(label("允许悬浮显示后，即可从小球截图或听一段。", 12f, true))
                        action("允许悬浮显示", true) { requestOverlayPermission() }
                        addView(android.view.View(this@MainActivity), LinearLayout.LayoutParams(-1, dp(12)))
                    }
                    addConversationList(this@MainActivity, archiveShortcut = true) { showHome() }
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun requestOverlayPermission() {
        // Persist before leaving the app so returning or rotating cannot reopen Settings.
        onboarding.edit().putBoolean("overlay_prompted", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: RuntimeException) {
            toast("无法打开悬浮权限设置，请在系统设置中允许 AlwaySide 悬浮显示")
        }
    }

    private fun ensureAssistant() {
        if (!ProbeState.serviceRunning) {
            try { startForegroundService(Intent(this, AssistantService::class.java).setAction(AssistantService.START)) }
            catch (error: RuntimeException) { toast("无法开启悬浮球：${error.javaClass.simpleName}") }
        }
        // Notification permission improves visibility; its refusal does not block a foreground service.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !onboarding.getBoolean("notification_prompted", false)) {
            onboarding.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onResume() {
        super.onResume()
        showHome()
        val allowed = Settings.canDrawOverlays(this)
        val newlyGranted = allowed && !overlayWasGranted
        overlayWasGranted = allowed
        if (allowed) {
            onboarding.edit().putBoolean("overlay_prompted", true).apply()
            if (!assistantStartAttempted || newlyGranted) {
                // Mark even when already running so a later consent callback cannot undo a stop.
                assistantStartAttempted = true
                ensureAssistant()
            }
        }
        else if (!onboarding.getBoolean("overlay_prompted", false)) requestOverlayPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("assistant_start_attempted", assistantStartAttempted)
        outState.putBoolean("overlay_was_granted", overlayWasGranted)
        super.onSaveInstanceState(outState)
    }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
