package com.alwayside.android

import android.content.Context

/** Window appearance only; separate from generation preferences and the full panel's language. */
internal class SubtitleOverlayPreferences(context: Context) {
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
    fun fontSize() = prefs.getInt("fontSize", 17).coerceIn(14, 22)
    fun saveFontSize(size: Int) {
        require(size in 14..22)
        check(prefs.edit().putInt("fontSize", size).commit()) { "无法保存字幕字号" }
    }
    fun opacity() = prefs.getInt("opacity", 90).coerceIn(60, 100)
    fun saveOpacity(value: Int) {
        require(value in 60..100)
        check(prefs.edit().putInt("opacity", value).commit()) { "无法保存字幕背景" }
    }
    fun window(): SavedWindow? = if (!prefs.contains("width")) null else SavedWindow(
        prefs.getFloat("x", .5f).coerceIn(0f, 1f), prefs.getFloat("y", .72f).coerceIn(0f, 1f),
        prefs.getInt("width", 320).coerceIn(272, 800), prefs.getInt("height", 216).coerceIn(152, 800))
    fun saveWindow(value: SavedWindow) {
        prefs.edit().putFloat("x", value.xFraction).putFloat("y", value.yFraction)
            .putInt("width", value.widthDp).putInt("height", value.heightDp).apply()
    }
}
