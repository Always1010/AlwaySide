package com.alwayside.android

import android.content.Context

/** Window appearance only; separate from generation preferences and the full panel's language. */
internal class SubtitleOverlayPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_overlay", Context.MODE_PRIVATE)
    private val initialMode = SubtitlePreferences(context).display().takeUnless { it == SubtitleDisplay.OFF }
        ?: SubtitleDisplay.BILINGUAL

    fun mode() = SubtitleDisplay.entries.firstOrNull {
        it != SubtitleDisplay.OFF && it.name == prefs.getString("language", null)
    } ?: initialMode
    fun saveMode(mode: SubtitleDisplay) {
        require(mode != SubtitleDisplay.OFF)
        check(prefs.edit().putString("language", mode.name).commit()) { "无法保存字幕语言" }
    }
    fun fontSize() = prefs.getInt("fontSize", 17).coerceIn(12, 32)
    fun saveFontSize(size: Int) {
        require(size in 12..32)
        check(prefs.edit().putInt("fontSize", size).commit()) { "无法保存字幕字号" }
    }
    fun opacity() = prefs.getInt("opacity", 90).coerceIn(0, 100)
    fun saveOpacity(value: Int) {
        require(value in 0..100)
        check(prefs.edit().putInt("opacity", value).commit()) { "无法保存字幕背景" }
    }
    fun theme() = SubtitleOverlayTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) }
        ?: SubtitleOverlayTheme.SYSTEM
    fun customColor() = prefs.getInt("customColor", SubtitleAppearance.DEFAULT_CUSTOM) or 0xFF000000.toInt()
    fun saveTheme(value: SubtitleOverlayTheme) {
        check(prefs.edit().putString("theme", value.name).commit()) { "无法保存字幕颜色主题" }
    }
    fun saveCustomColor(value: Int) {
        check(prefs.edit().putInt("customColor", value or 0xFF000000.toInt()).commit()) { "无法保存字幕背景颜色" }
    }
    private val orientation get() = if (context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE) ".landscape" else ".portrait"
    fun window(): SavedWindow? {
        val suffix = if (prefs.contains("width$orientation")) orientation else ""
        if (!prefs.contains("width$suffix")) return null
        return SavedWindow(prefs.getFloat("x$suffix", .5f).coerceIn(0f, 1f),
            prefs.getFloat("y$suffix", .72f).coerceIn(0f, 1f),
            prefs.getInt("width$suffix", 340).coerceIn(272, 800), prefs.getInt("height$suffix", 244).coerceIn(158, 800))
    }
    fun saveWindow(value: SavedWindow) {
        prefs.edit().putFloat("x$orientation", value.xFraction).putFloat("y$orientation", value.yFraction)
            .putInt("width$orientation", value.widthDp).putInt("height$orientation", value.heightDp).apply()
    }
    fun resetWindow() {
        prefs.edit().apply {
            for (suffix in listOf("", orientation)) for (key in listOf("x", "y", "width", "height")) remove(key + suffix)
        }.apply()
    }
}
