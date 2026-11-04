package com.alwayside.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** One application window for history, settings and conversations. */
class MainActivity : Activity() {
    private val onboarding by lazy { getSharedPreferences("assistant_onboarding", MODE_PRIVATE) }
    private var assistantStartAttempted = false
    private var overlayWasGranted = false
    private var allowOnboarding = true
    private lateinit var navigation: AppNavigation
    private lateinit var container: FrameLayout
    private var settingsScreen: SettingsScreen? = null
    private var chatScreen: ChatScreen? = null
    private var historyScroll: ScrollView? = null
    private var historyY = 0
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var retainedChatOffered = false
    private data class RetainedApp(
        val pages: List<String>, val chat: ChatScreen.RetainedConversation?,
        val settingsPage: String?, val historyY: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedApp
        val entry = AppEntry.take(intent)
        navigation = AppNavigation(savedInstanceState?.getStringArrayList("pages") ?: retained?.pages.orEmpty())
        assistantStartAttempted = savedInstanceState?.getBoolean("assistant_start_attempted", false) ?: false
        overlayWasGranted = savedInstanceState?.getBoolean("overlay_was_granted", false) ?: false
        allowOnboarding = savedInstanceState?.getBoolean("allow_onboarding") ?: (entry == null)
        historyY = savedInstanceState?.getInt("history_y") ?: retained?.historyY ?: 0
        container = FrameLayout(this).apply { setBackgroundColor(BACKGROUND); fitsSystemWindows = true }
        setContentView(container)
        if(navigation.hasChat) chatScreen = createChat(savedState=savedInstanceState?.getBundle("chat"),retained=retained?.chat)
        if(navigation.current == AppPage.SETTINGS) createSettings(savedInstanceState?.getString("settings_page") ?: retained?.settingsPage)
        if(entry != null) route(entry) else showCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        val entry = AppEntry.take(intent)
        if(entry != null) route(entry)
        else if(intent.action == Intent.ACTION_MAIN) { allowOnboarding=true;assistantStartAttempted=false }
    }

    private fun present(view: View) {
        if(container.childCount != 1 || container.getChildAt(0) !== view) {
            hideKeyboard()
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews(); container.addView(view,FrameLayout.LayoutParams(-1,-1))
        }
        if(navigation.current == AppPage.SETTINGS)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if(Build.VERSION.SDK_INT < 33)return
        if(navigation.canGoBack && backCallback == null) {
            backCallback=android.window.OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
            }
        } else if(!navigation.canGoBack) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) };backCallback=null
        }
    }

    // OEM/key paths may call this even on API33+. Both paths must use the same internal stack.
    @Deprecated("Legacy key bridge; gesture Back uses OnBackInvokedCallback")
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if(navigation.canGoBack)navigateBack() else super.onBackPressed() }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0) }
    }

    private fun navigateBack() {
        hideKeyboard()
        when(navigation.back(settingsAtRoot=settingsScreen?.isHome ?: true)) {
            AppBack.SETTINGS_CATEGORY -> settingsScreen?.back()
            AppBack.PAGE -> {
                if(navigation.current != AppPage.SETTINGS) { settingsScreen?.dispose();settingsScreen=null }
                if(!navigation.hasChat) { chatScreen?.dispose();chatScreen=null }
                showCurrent()
            }
            AppBack.EXIT -> finish()
        }
    }

    private fun leaveSettings(then: () -> Unit) {
        val settings=settingsScreen
        if(settings == null)then() else settings.requestLeave {
            settings.dispose();settingsScreen=null;then()
        }
    }

    private fun openHistory(): Unit = leaveSettings {
        hideKeyboard();chatScreen?.dispose();chatScreen=null
        navigation.openHistory();showCurrent()
    }

    private fun openSettings() {
        if(navigation.current == AppPage.SETTINGS)return
        hideKeyboard();navigation.openSettings();createSettings()
    }

    private fun createSettings(page: String? = null) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settingsScreen=SettingsScreen(this,::present,::openHistory,page)
    }

    private fun createChat(id: String? = null,savedState: Bundle? = null,
        retained: ChatScreen.RetainedConversation? = null,autoAskText: Boolean = false): ChatScreen =
        ChatScreen(this,::navigateBack,::openSettings,id,savedState,retained,autoAskText)

    private fun openChat(id: String?,autoAskText: Boolean = false): Unit = leaveSettings {
        hideKeyboard()
        if(chatScreen?.matches(id) != true) {
            chatScreen?.dispose();chatScreen=createChat(id=id,autoAskText=autoAskText)
        }
        navigation.openChat();showCurrent()
    }

    private fun route(entry: AppEntry.Request) {
        when(entry.page) {
            AppPage.HISTORY -> openHistory()
            AppPage.SETTINGS -> openSettings()
            AppPage.CHAT -> openChat(entry.conversationId,entry.autoAskText)
        }
    }

    private fun showCurrent() {
        when(navigation.current) {
            AppPage.HISTORY -> showHistory()
            AppPage.CHAT -> chatScreen?.let { present(it.view) } ?: openHistory()
            AppPage.SETTINGS -> settingsScreen?.show() ?: createSettings()
        }
    }

    private fun showHistory() {
        historyY=historyScroll?.scrollY ?: historyY
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(BACKGROUND)
            addView(appHeader());addView(appTabs(AppPage.HISTORY,::openHistory,::openSettings))
        }
        val scroll=ScrollView(this).apply {
            isFillViewport=true
            addView(column().apply {
                if(!Settings.canDrawOverlays(this@MainActivity)) {
                    addView(label("允许悬浮显示后，即可从小球截图或听一段。",12f,true))
                    action("允许悬浮显示",true) { requestOverlayPermission() }
                    addView(View(this@MainActivity),LinearLayout.LayoutParams(-1,dp(12)))
                }
                addConversationList(this@MainActivity,onOpen={openChat(it)}) { showHistory() }
            })
        }
        historyScroll=scroll;root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        present(root)
        val targetY=historyY
        scroll.post { if(historyScroll===scroll)scroll.scrollTo(0,targetY) }
    }

    private fun requestOverlayPermission() {
        onboarding.edit().putBoolean("overlay_prompted",true).apply()
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        catch(_:RuntimeException) { toast("无法打开悬浮权限设置，请在系统设置中允许 AlwaySide 悬浮显示") }
    }

    private fun ensureAssistant() {
        if(!ProbeState.serviceRunning) {
            try { startForegroundService(Intent(this,AssistantService::class.java).setAction(AssistantService.START)) }
            catch(error:RuntimeException) { toast("无法开启悬浮球：${error.javaClass.simpleName}") }
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !onboarding.getBoolean("notification_prompted",false)) {
            onboarding.edit().putBoolean("notification_prompted",true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),12)
        }
    }

    override fun onResume() {
        super.onResume()
        if(navigation.current == AppPage.HISTORY)showHistory()
        if(!allowOnboarding)return
        val allowed=Settings.canDrawOverlays(this)
        val newlyGranted=allowed && !overlayWasGranted
        overlayWasGranted=allowed
        if(allowed) {
            onboarding.edit().putBoolean("overlay_prompted",true).apply()
            if(!assistantStartAttempted || newlyGranted) { assistantStartAttempted=true;ensureAssistant() }
        } else if(!onboarding.getBoolean("overlay_prompted",false))requestOverlayPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pages",ArrayList(navigation.snapshot()))
        outState.putString("settings_page",settingsScreen?.pageName())
        outState.putInt("history_y",historyScroll?.scrollY ?: historyY)
        chatScreen?.let { chat -> outState.putBundle("chat",Bundle().also { chat.saveState(it) }) }
        outState.putBoolean("assistant_start_attempted",assistantStartAttempted)
        outState.putBoolean("overlay_was_granted",overlayWasGranted)
        outState.putBoolean("allow_onboarding",allowOnboarding)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any {
        val retained=chatScreen?.retain();retainedChatOffered=retained!=null
        return RetainedApp(navigation.snapshot(),retained,settingsScreen?.pageName(),historyScroll?.scrollY ?: historyY)
    }

    override fun onDestroy() {
        if(Build.VERSION.SDK_INT>=33)backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        settingsScreen?.dispose();settingsScreen=null
        chatScreen?.dispose(retaining=isChangingConfigurations && retainedChatOffered);chatScreen=null
        super.onDestroy()
    }

    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
}
