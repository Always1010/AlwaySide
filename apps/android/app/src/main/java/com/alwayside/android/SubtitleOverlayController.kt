package com.alwayside.android

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/** Read-only caption presentation, owned by playback rather than the floating capture assistant. */
internal class SubtitleOverlayController private constructor(private val context: Context) {
    private val playback get() = ChatAudioPlayback.get(context)
    private val interaction = InteractionPreferences(context)
    private val preferences by lazy { SubtitleOverlayPreferences(context) }
    private val reading = SubtitleReadingPreferences(context)
    private var sourceBundle: SubtitleBundle? = null
    private var readingBundle: SubtitleBundle? = null
    private var segmentation: SubtitleSegmentation? = null
    private val subtitles = SubtitleController.get(context)
    private val state = SubtitleOverlayState()
    private var enabled = interaction.load().backgroundPlaybackSubtitles
    private var permission = Settings.canDrawOverlays(context)
    private var screenOn = context.getSystemService(PowerManager::class.java).isInteractive
    private var unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private var loadedSession = -1L
    private var window: SubtitleOverlayWindow? = null
    private val panels = mutableMapOf<Any, Pair<Activity, String>>()
    private val observer: () -> Unit = { sync() }
    private val readingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> sync() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "backgroundPlaybackSubtitles") {
            enabled = interaction.load().backgroundPlaybackSubtitles
            permission = Settings.canDrawOverlays(context)
            if (enabled) state.restore()
            sync()
        }
    }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        hide(); sync()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) hide()
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF &&
                context.getSystemService(PowerManager::class.java).isInteractive
            unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            permission = Settings.canDrawOverlays(context)
            sync()
        }
    }

    init {
        reading.addListener(readingListener)
        interaction.addListener(settingsListener)
        AppAppearance.addListener(context, appearanceListener)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                panels.filterValues { it.first === activity }.forEach { (token, panel) -> state.panel(token, panel.second, true) }
                screenOn = context.getSystemService(PowerManager::class.java).isInteractive
                unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                permission = Settings.canDrawOverlays(context)
                sync()
            }
            override fun onActivityPaused(activity: Activity) {
                panels.filterValues { it.first === activity }.keys.forEach { state.panel(it, null, false) }
                sync()
            }
            override fun onActivityDestroyed(activity: Activity) {
                panels.filterValues { it.first === activity }.keys.toList().forEach {
                    panels.remove(it); state.panel(it, null, false)
                }
                sync()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        subtitles.addObserver(observer)
    }

    private fun panel(activity: Activity, token: Any, owner: String, visible: Boolean) {
        if (visible) panels[token] = activity to owner else panels.remove(token)
        state.panel(token, owner, visible)
        sync()
    }

    private fun sync() {
        val player = playback
        state.playback(player.playbackSession)
        val owner = player.conversationId
        val original = owner != null && player.readingKey.isEmpty() &&
            !player.currentState.preparing && player.currentState.remaining != null
        if (enabled && original && loadedSession != player.playbackSession) {
            loadedSession = player.playbackSession
            subtitles.refresh(requireNotNull(owner))
        }
        val bundle = owner?.let { subtitles.record(it, SubtitleMethod.WHISPER).playable }
        if (!state.shouldShow(owner, enabled && permission, !bundle?.cues.isNullOrEmpty(),
                original, screenOn && unlocked)) { hide(); return }
        try {
            val shown = window ?: SubtitleOverlayWindow(context, preferences,
                onClose = { state.dismiss(); hide() },
                onOpen = { openPanel() }, onPlayback = { player.toggle() },
                onChanged = { sync() }, onUnavailable = { unavailable() }).also { window = it; it.show() }
            val currentSource = requireNotNull(bundle)
            val selected = reading.segmentation()
            if (sourceBundle !== currentSource || segmentation != selected) {
                sourceBundle = currentSource; segmentation = selected
                readingBundle = SubtitleReading.project(currentSource, selected)
            }
            val current = requireNotNull(readingBundle)
            shown.render(SubtitleOverlayState.text(current, SubtitleReading.activeIndex(current.cues, player.position.toLong(), player.currentState.playing),
                preferences.mode()), preferences.mode(), player.currentState.playing, player.position, player.duration)
        } catch (_: RuntimeException) { unavailable() }
    }

    private fun openPanel() {
        val owner = playback.conversationId ?: return
        runCatching { context.startActivity(AppEntry.intent(context, AppPage.CHAT, owner, openSubtitles = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "无法打开完整字幕，请返回应用查看", Toast.LENGTH_LONG).show() }
    }

    private fun unavailable() {
        state.dismiss(); hide()
        Toast.makeText(context, "无法显示字幕，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
    }
    private fun hide() { window?.close(); window = null }

    companion object {
        private var instance: SubtitleOverlayController? = null
        val tracking: Boolean get() = instance?.window != null
        private fun get(context: Context): SubtitleOverlayController = instance ?:
            SubtitleOverlayController(context.applicationContext).also { instance = it }
        fun playbackChanged(context: Context) { get(context).sync() }
        fun restore(context: Context) { get(context).let { it.state.restore(); it.sync() } }
        fun panelVisible(activity: Activity, token: Any, owner: String, visible: Boolean) {
            get(activity).panel(activity, token, owner, visible)
        }
    }
}
