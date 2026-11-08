package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences

internal object AssistantActions {
    const val SCREEN = "screen"
    const val AUDIO = "audio"
    const val START_AUDIO = "start_audio"
    const val END_AUDIO = "end_audio"
    const val MENU = "menu"
    const val CROP = "crop"
    const val LAST = "last"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CANCEL_AUDIO = "cancel_audio"
    const val NONE = "none"
}

internal data class InteractionConfig(
    val ballSize: Int = 36,
    val ballOpacity: Int = 55,
    val ballColor: Int = 0xFFCC5C74.toInt(),
    val autoAskImage: Boolean = true,
    val autoAskAudio: Boolean = true,
    val rememberWindowPosition: Boolean = true,
    val rememberWindowSize: Boolean = true,
    val defaultPinned: Boolean = false,
    val saveHistoryImages: Boolean = true,
    val autoAskText: Boolean = true,
    val recordingMaxMinutes: Int = RecordingLimits.DEFAULT_MINUTES,
    val collapseFollowUpByDefault: Boolean = true,
    val saveHistoryAudio: Boolean = true,
    val backgroundPlayback: Boolean = false,
    val backgroundPlaybackSubtitles: Boolean = false,
    val chatPointReading: Boolean = false,
    val imagePointReading: Boolean = false,
    val recordingLimitAction: RecordingLimitAction = RecordingLimitAction.DISCARD,
    val useDefaultQuestion: Boolean = true,
    val menuWebSearch: Boolean? = null,
    val menuItems: List<AssistantMenuAction> = AssistantMenuAction.defaults,
    val menuAppearance: MenuAppearance = MenuAppearance(),
    val longPressSlide: Boolean = false,
    val longPressMillis: Int = 600,
    val slideDistanceDp: Int = 24,
    val actionCircleSizeDp: Int = 32,
    val bindings: List<GestureBinding>? = null,
) {
    fun resolvedBindings() = bindings ?: GestureBindings.defaults()
    fun gestureAction(gesture: BubbleGesture, recording: Boolean): String {
        if (gesture.directional && !longPressSlide) return AssistantActions.NONE
        return resolvedBindings().firstOrNull { it.gesture == gesture && it.recording == recording }?.action ?: AssistantActions.NONE
    }
    fun autoAsk(kind: String): Boolean = useDefaultQuestion && when (kind) {
        "photo" -> autoAskImage
        "audio" -> autoAskAudio
        else -> autoAskText
    }

    fun action(taps: Int, recording: Boolean): String = gestureAction(BubbleGesture.taps(taps), recording)
}

internal data class SavedWindow(val xFraction: Float, val yFraction: Float, val widthDp: Int, val heightDp: Int)

internal class InteractionPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant_interaction", Context.MODE_PRIVATE)
    fun load(): InteractionConfig {
        val d = InteractionConfig()
        // This pre-release format has one current schema, without legacy gesture migration.
        val savedBindings = if (prefs.getInt("gestureBindingsVersion", 0) == 2) buildList {
            for (recording in listOf(false, true)) for (gesture in BubbleGesture.entries) {
                prefs.getString("binding_${recording}_${gesture.name}", null)?.let {
                    add(GestureBinding(gesture, recording, it))
                }
            }
        } else GestureBindings.defaults()
        return InteractionConfig(
            ballSize = prefs.getInt("ballSize", d.ballSize).coerceIn(18, 48),
            ballOpacity = prefs.getInt("ballOpacity", d.ballOpacity).coerceIn(15, 100),
            ballColor = prefs.getInt("ballColor", d.ballColor) or 0xFF000000.toInt(),
            autoAskImage = prefs.getBoolean("autoAskImage", d.autoAskImage),
            autoAskAudio = prefs.getBoolean("autoAskAudio", d.autoAskAudio),
            rememberWindowPosition = prefs.getBoolean("rememberWindowPosition", d.rememberWindowPosition),
            rememberWindowSize = prefs.getBoolean("rememberWindowSize", d.rememberWindowSize),
            defaultPinned = prefs.getBoolean("defaultPinned", d.defaultPinned),
            saveHistoryImages = prefs.getBoolean("saveHistoryImages", d.saveHistoryImages),
            autoAskText = prefs.getBoolean("autoAskText", d.autoAskText),
            recordingMaxMinutes = prefs.getInt("recordingMaxMinutes", d.recordingMaxMinutes).coerceIn(0, RecordingLimits.MAX_MINUTES),
            collapseFollowUpByDefault = prefs.getBoolean("collapseFollowUpByDefault", d.collapseFollowUpByDefault),
            saveHistoryAudio = prefs.getBoolean("saveHistoryAudio", d.saveHistoryAudio),
            backgroundPlayback = prefs.getBoolean("backgroundPlayback", d.backgroundPlayback),
            backgroundPlaybackSubtitles = prefs.getBoolean("backgroundPlaybackSubtitles", d.backgroundPlaybackSubtitles),
            chatPointReading = prefs.getBoolean("chatPointReading", false),
            imagePointReading = prefs.getBoolean("imagePointReading", false),
            recordingLimitAction = RecordingLimitAction.from(prefs.getString("recordingLimitAction", null)),
            useDefaultQuestion = prefs.getBoolean("useDefaultQuestion", d.useDefaultQuestion),
            menuWebSearch = if (prefs.contains("menuWebSearch")) prefs.getBoolean("menuWebSearch", false) else null,
            menuItems = AssistantMenuAction.decode(prefs.getString("menuItems", null)),
            menuAppearance = MenuAppearance(
                backgroundColor = if (prefs.contains("menuBackgroundColor")) prefs.getInt("menuBackgroundColor", 0) else null,
                transparency = prefs.getInt("menuTransparency", 0).coerceIn(0, 100),
                sizePercent = prefs.getInt("menuSizePercent", 100).coerceIn(30, 150),
                blurStrength = prefs.getInt("menuBlurStrength", 25).coerceIn(0, 100),
                dimPercent = if (prefs.contains("menuDimPercent")) prefs.getInt("menuDimPercent", 18).coerceIn(0, 100) else null,
                cornerRadiusDp = prefs.getInt("menuCornerRadiusDp", 24).coerceIn(0, 100),
            ),
            longPressSlide = prefs.getBoolean("longPressSlide", false),
            longPressMillis = prefs.getInt("longPressMillis", 600).coerceIn(400, 1500),
            slideDistanceDp = prefs.getInt("slideDistanceDp", d.slideDistanceDp).coerceIn(16, 40).let { it / 4 * 4 },
            actionCircleSizeDp = prefs.getInt("actionCircleSizeDp", d.actionCircleSizeDp).coerceIn(28, 40).let { it / 4 * 4 },
            bindings = savedBindings.takeIf(GestureBindings::valid) ?: GestureBindings.defaults(),
        )
    }
    fun save(value: InteractionConfig) {
        require(value.ballSize in 18..48 && value.ballOpacity in 15..100)
        require(value.recordingMaxMinutes in 0..RecordingLimits.MAX_MINUTES)
        require(value.longPressMillis in 400..1500)
        require(value.slideDistanceDp in 16..40 && value.slideDistanceDp % 4 == 0)
        require(value.actionCircleSizeDp in 28..40 && value.actionCircleSizeDp % 4 == 0)
        require(GestureBindings.valid(value.resolvedBindings()))
        require(value.menuItems.distinct() == value.menuItems)
        val edit=prefs.edit().putInt("gestureBindingsVersion", 2).putBoolean("longPressSlide", value.longPressSlide)
            .putInt("longPressMillis", value.longPressMillis).remove("slideWindowMillis")
            .putInt("slideDistanceDp", value.slideDistanceDp).putInt("actionCircleSizeDp", value.actionCircleSizeDp)
            .putInt("ballSize", value.ballSize).putInt("ballOpacity", value.ballOpacity)
            .putInt("ballColor", value.ballColor).putBoolean("autoAskImage", value.autoAskImage)
            .putBoolean("autoAskAudio", value.autoAskAudio).putBoolean("rememberWindowPosition", value.rememberWindowPosition)
            .putBoolean("rememberWindowSize", value.rememberWindowSize).putBoolean("defaultPinned", value.defaultPinned)
            .putBoolean("saveHistoryImages", value.saveHistoryImages).putBoolean("autoAskText", value.autoAskText)
            .putInt("recordingMaxMinutes", value.recordingMaxMinutes)
            .putString("recordingLimitAction", value.recordingLimitAction.value)
            .putBoolean("collapseFollowUpByDefault", value.collapseFollowUpByDefault)
            .putBoolean("saveHistoryAudio", value.saveHistoryAudio)
            .putBoolean("backgroundPlayback", value.backgroundPlayback)
            .putBoolean("backgroundPlaybackSubtitles", value.backgroundPlaybackSubtitles)
            .putBoolean("chatPointReading", value.chatPointReading)
            .putBoolean("imagePointReading", value.imagePointReading)
            .putBoolean("useDefaultQuestion", value.useDefaultQuestion)
            .putString("menuItems", AssistantMenuAction.encode(value.menuItems))
            .putInt("menuTransparency", value.menuAppearance.transparency)
            .putInt("menuSizePercent", value.menuAppearance.sizePercent)
            .putInt("menuBlurStrength", value.menuAppearance.blurStrength)
            .putInt("menuCornerRadiusDp", value.menuAppearance.cornerRadiusDp)
        value.menuAppearance.backgroundColor.let { color ->
            if (color == null) edit.remove("menuBackgroundColor") else edit.putInt("menuBackgroundColor", color)
        }
        value.menuAppearance.dimPercent.let { dim ->
            if (dim == null) edit.remove("menuDimPercent") else edit.putInt("menuDimPercent", dim)
        }
        for (recording in listOf(false, true)) for (gesture in BubbleGesture.entries) {
            val key = "binding_${recording}_${gesture.name}"
            val binding = value.resolvedBindings().firstOrNull { it.gesture == gesture && it.recording == recording }
            if (binding == null) edit.remove(key) else edit.putString(key, binding.action)
        }
        if (value.menuWebSearch == null) edit.remove("menuWebSearch") else edit.putBoolean("menuWebSearch", value.menuWebSearch)
        check(edit.commit()) { "无法保存交互设置" }
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
