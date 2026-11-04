package com.alwayside.android

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode

/** Shared appearance preference; configuration contexts also theme platform dialogs and menus. */
internal object AppAppearance {
    private const val PREFERENCES = "app_appearance"
    private const val MODE = "mode"
    private const val SCHEME = "scheme"
    private var selectedMode = AppearanceMode.SYSTEM
    private var selectedScheme = ThemeScheme.BLACK
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun mode(context: Context): String {
        selectedMode = AppearanceMode.parse(preferences(context).getString(MODE, "system"))
        selectedScheme = ThemeScheme.parse(preferences(context).getString(SCHEME, "black"))
        return selectedMode.value
    }
    fun scheme(context: Context): String = ThemeScheme.parse(preferences(context).getString(SCHEME,"black")).also { selectedScheme=it }.value
    fun saveScheme(context: Context, value: String) {
        val next=ThemeScheme.parse(value)
        check(preferences(context).edit().putString(SCHEME,next.value).commit()) { "无法保存配色，请重试" }
        selectedScheme=next
    }
    val accent: Int get() = selectedScheme.accent(isNight)
    val onAccent: Int get() = selectedScheme.onAccent(isNight)
    private fun paletteStyle(): Int = if(selectedScheme==ThemeScheme.BLUE)R.style.AppPalette_Blue else R.style.AppPalette_Black
    fun applyPalette(context: Context) { mode(context);context.theme.applyStyle(paletteStyle(),true) }
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
    fun themedContext(context: Context): Context = ContextThemeWrapper(configurationContext(context), R.style.AppTheme).also(::applyPalette)
    fun addListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences(context).registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
}

/** Use an overridden configuration before Activity creates any platform widgets. */
open class ThemedActivity : Activity(), AppCompatCallback {
    private lateinit var materialDelegate: AppCompatDelegate
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppAppearance.configurationContext(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        materialDelegate=AppCompatDelegate.create(this,this)
        materialDelegate.localNightMode=when(AppAppearance.mode(this)) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        materialDelegate.installViewFactory()
        materialDelegate.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
        AppAppearance.applyPalette(this)
    }
    override fun setContentView(view: View) = materialDelegate.setContentView(view)
    override fun setContentView(view: View, params: ViewGroup.LayoutParams) = materialDelegate.setContentView(view,params)
    override fun setContentView(layoutResID: Int) = materialDelegate.setContentView(layoutResID)
    override fun addContentView(view: View, params: ViewGroup.LayoutParams) = materialDelegate.addContentView(view,params)
    override fun onPostCreate(state: Bundle?) { super.onPostCreate(state);materialDelegate.onPostCreate(state) }
    override fun onStart() { super.onStart();materialDelegate.onStart() }
    override fun onPostResume() { super.onPostResume();materialDelegate.onPostResume() }
    override fun onStop() { super.onStop();materialDelegate.onStop() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig);materialDelegate.onConfigurationChanged(newConfig)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState);materialDelegate.onSaveInstanceState(outState)
    }
    override fun onDestroy() { super.onDestroy();materialDelegate.onDestroy() }
    override fun onSupportActionModeStarted(mode: ActionMode) = Unit
    override fun onSupportActionModeFinished(mode: ActionMode) = Unit
    override fun onWindowStartingSupportActionMode(callback: ActionMode.Callback): ActionMode? = null
}
