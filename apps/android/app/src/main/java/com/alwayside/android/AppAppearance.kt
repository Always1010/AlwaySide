package com.alwayside.android

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper

/** Shared appearance preference; configuration contexts also theme platform dialogs and menus. */
internal object AppAppearance {
    private const val PREFERENCES = "app_appearance"
    private const val MODE = "mode"
    private var selectedMode = AppearanceMode.SYSTEM
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun mode(context: Context): String {
        selectedMode = AppearanceMode.parse(preferences(context).getString(MODE, "system"))
        return selectedMode.value
    }
    fun save(context: Context, mode: String) {
        val next = AppearanceMode.parse(mode)
        check(preferences(context).edit().putString(MODE, next.value).commit()) { "无法保存主题，请重试" }
        selectedMode = next
    }
    val isNight: Boolean get() = selectedMode.isNight(
        Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    fun configurationContext(context: Context): Context {
        mode(context)
        val forcedNight = selectedMode.forcedNight ?: return context
        val configuration = Configuration(context.resources.configuration)
        val night = if (forcedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(configuration)
    }
    fun themedContext(context: Context): Context = ContextThemeWrapper(configurationContext(context), R.style.AppTheme)
    fun addListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences(context).registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
}

/** Use an overridden configuration before Activity creates any platform widgets. */
open class ThemedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppAppearance.configurationContext(newBase))
    }
}
