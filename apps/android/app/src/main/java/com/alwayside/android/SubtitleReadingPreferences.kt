package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences

/** Reading controls are shared by the dialog and floating captions, separate from generation. */
internal class SubtitleReadingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_reading", Context.MODE_PRIVATE)
    fun segmentation() = SubtitleSegmentation.entries.firstOrNull {
        it.name == prefs.getString("segmentation", null)
    } ?: SubtitleSegmentation.SENTENCE
    fun saveSegmentation(value: SubtitleSegmentation) {
        check(prefs.edit().putString("segmentation", value.name).commit()) { "无法保存字幕分句方式" }
    }
    fun fontSize() = prefs.getInt("fontSize", 17).coerceIn(12, 32)
    fun saveFontSize(value: Int) {
        require(value in 12..32)
        check(prefs.edit().putInt("fontSize", value).commit()) { "无法保存字幕字号" }
    }
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
