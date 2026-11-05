package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences

internal object AssistantActions {
    const val SCREEN = "screen"
    const val AUDIO = "audio"
    const val MENU = "menu"
    const val CROP = "crop"
    const val LAST = "last"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CANCEL_AUDIO = "cancel_audio"
    const val NONE = "none"
    val choices = linkedMapOf(SCREEN to "截图问答", AUDIO to "开始／结束录音并处理", MENU to "展开菜单",
        CROP to "框选问答", LAST to "最近悬浮对话", HISTORY to "应用内对话记录", SETTINGS to "设置",
        CANCEL_AUDIO to "取消并丢弃录音", NONE to "不执行动作")
}

internal data class InteractionConfig(
    val ballSize: Int = 36,
    val ballOpacity: Int = 55,
    val ballColor: Int = 0xFFCC5C74.toInt(),
    val singleAction: String = AssistantActions.MENU,
    val doubleAction: String = AssistantActions.AUDIO,
    val tripleAction: String = AssistantActions.NONE,
    val longAction: String = AssistantActions.SCREEN,
    val recordingSingleAction: String = AssistantActions.MENU,
    val recordingDoubleAction: String = AssistantActions.AUDIO,
    val recordingTripleAction: String = AssistantActions.CANCEL_AUDIO,
    val recordingLongAction: String = AssistantActions.SCREEN,
    val autoAskImage: Boolean = true,
    val autoAskAudio: Boolean = true,
    val rememberWindowPosition: Boolean = true,
    val rememberWindowSize: Boolean = true,
    val defaultPinned: Boolean = false,
    val saveHistoryImages: Boolean = true,
    val autoAskText: Boolean = true,
    val recordingMaxMinutes: Int = RecordingLimits.DEFAULT_MINUTES,
    val collapseFollowUpByDefault: Boolean = true,
    val saveHistoryAudio: Boolean = false,
) {
    fun action(taps: Int, recording: Boolean): String = when {
        recording && taps == 1 -> recordingSingleAction
        recording && taps == 2 -> recordingDoubleAction
        recording && taps == 3 -> recordingTripleAction
        recording -> recordingLongAction
        taps == 1 -> singleAction
        taps == 2 -> doubleAction
        taps == 3 -> tripleAction
        else -> longAction
    }
}

internal data class SavedWindow(val xFraction: Float, val yFraction: Float, val widthDp: Int, val heightDp: Int)

internal class InteractionPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant_interaction", Context.MODE_PRIVATE)
    fun load(): InteractionConfig {
        val version = prefs.getInt("gestureDefaultsVersion", 0)
        val hasStoredGestures = listOf("singleAction", "doubleAction", "tripleAction", "longAction").any(prefs::contains)
        val d = if (version < InteractionDefaults.VERSION && hasStoredGestures)
            InteractionConfig(singleAction=AssistantActions.SCREEN,longAction=AssistantActions.MENU) else InteractionConfig()
        fun action(key: String, fallback: String) = prefs.getString(key, fallback)?.takeIf { it in AssistantActions.choices } ?: fallback
        val loaded = InteractionConfig(
            prefs.getInt("ballSize", d.ballSize).coerceIn(18, 48),
            prefs.getInt("ballOpacity", d.ballOpacity).coerceIn(15, 100),
            prefs.getInt("ballColor", d.ballColor) or 0xFF000000.toInt(),
            action("singleAction", d.singleAction), action("doubleAction", d.doubleAction),
            action("tripleAction", d.tripleAction), action("longAction", d.longAction),
            action("recordingSingleAction", d.recordingSingleAction), action("recordingDoubleAction", d.recordingDoubleAction),
            action("recordingTripleAction", d.recordingTripleAction), action("recordingLongAction", d.recordingLongAction),
            prefs.getBoolean("autoAskImage", d.autoAskImage), prefs.getBoolean("autoAskAudio", d.autoAskAudio),
            prefs.getBoolean("rememberWindowPosition", d.rememberWindowPosition),
            prefs.getBoolean("rememberWindowSize", d.rememberWindowSize), prefs.getBoolean("defaultPinned", d.defaultPinned),
            prefs.getBoolean("saveHistoryImages", d.saveHistoryImages),
            prefs.getBoolean("autoAskText", d.autoAskText),
            prefs.getInt("recordingMaxMinutes", d.recordingMaxMinutes).coerceIn(0, RecordingLimits.MAX_MINUTES),
            prefs.getBoolean("collapseFollowUpByDefault", d.collapseFollowUpByDefault),
            prefs.getBoolean("saveHistoryAudio", d.saveHistoryAudio),
        )
        if (version >= InteractionDefaults.VERSION) return loaded
        val upgraded = InteractionDefaults.upgrade(loaded)
        val edit = prefs.edit().putInt("gestureDefaultsVersion", InteractionDefaults.VERSION)
        if (upgraded != loaded) edit.putString("singleAction", upgraded.singleAction).putString("longAction", upgraded.longAction)
        // Persist only the default-policy upgrade; all other appearance, media and user mappings stay intact.
        edit.commit()
        return upgraded
    }
    fun save(value: InteractionConfig) {
        require(value.ballSize in 18..48 && value.ballOpacity in 15..100)
        require(value.recordingMaxMinutes in 0..RecordingLimits.MAX_MINUTES)
        require((listOf(value.singleAction, value.doubleAction, value.tripleAction, value.longAction,
            value.recordingSingleAction, value.recordingDoubleAction, value.recordingTripleAction,
            value.recordingLongAction)).all { it in AssistantActions.choices })
        check(prefs.edit().putInt("gestureDefaultsVersion", InteractionDefaults.VERSION)
            .putInt("ballSize", value.ballSize).putInt("ballOpacity", value.ballOpacity)
            .putInt("ballColor", value.ballColor).putString("singleAction", value.singleAction)
            .putString("doubleAction", value.doubleAction).putString("tripleAction", value.tripleAction)
            .putString("longAction", value.longAction).putString("recordingSingleAction", value.recordingSingleAction)
            .putString("recordingDoubleAction", value.recordingDoubleAction).putString("recordingTripleAction", value.recordingTripleAction)
            .putString("recordingLongAction", value.recordingLongAction).putBoolean("autoAskImage", value.autoAskImage)
            .putBoolean("autoAskAudio", value.autoAskAudio).putBoolean("rememberWindowPosition", value.rememberWindowPosition)
            .putBoolean("rememberWindowSize", value.rememberWindowSize).putBoolean("defaultPinned", value.defaultPinned)
            .putBoolean("saveHistoryImages", value.saveHistoryImages).putBoolean("autoAskText", value.autoAskText)
            .putInt("recordingMaxMinutes", value.recordingMaxMinutes)
            .putBoolean("collapseFollowUpByDefault", value.collapseFollowUpByDefault)
            .putBoolean("saveHistoryAudio", value.saveHistoryAudio).commit()) { "无法保存交互设置" }
    }
    fun window(kind: String): SavedWindow? = if (!prefs.contains("${kind}_width")) null else SavedWindow(
        prefs.getFloat("${kind}_x", .5f).coerceIn(0f, 1f), prefs.getFloat("${kind}_y", .5f).coerceIn(0f, 1f),
        prefs.getInt("${kind}_width", 320).coerceIn(220, 800), prefs.getInt("${kind}_height", 340).coerceIn(220, 1200))
    fun saveWindow(kind: String, value: SavedWindow) {
        prefs.edit().putFloat("${kind}_x", value.xFraction.coerceIn(0f, 1f))
            .putFloat("${kind}_y", value.yFraction.coerceIn(0f, 1f))
            .putInt("${kind}_width", value.widthDp).putInt("${kind}_height", value.heightDp).apply()
    }
    fun ballPosition(): Pair<Boolean, Float> = prefs.getBoolean("ballLeft", false) to prefs.getFloat("ballY", .34f).coerceIn(0f, 1f)
    fun saveBall(left: Boolean, y: Float) { prefs.edit().putBoolean("ballLeft", left).putFloat("ballY", y.coerceIn(0f, 1f)).apply() }
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
