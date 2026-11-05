package com.alwayside.android

import android.Manifest
import android.annotation.SuppressLint
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
class MainActivity : ThemedActivity() {
    private val onboarding by lazy { getSharedPreferences("assistant_onboarding", MODE_PRIVATE) }
    private val guidePreferences by lazy { GuidePreferences(this) }
    private var assistantStartAttempted = false
    private var overlayWasGranted = false
    private var allowOnboarding = true
    private lateinit var navigation: AppNavigation
    private lateinit var container: FrameLayout
    private var settingsScreen: SettingsScreen? = null
    private var chatScreen: ChatScreen? = null
    private var tutorialScreen: TutorialScreen? = null
    private var tutorialFlow = TutorialFlow()
    private var historyScroll: ScrollView? = null
    private var historyY = 0
    private var historyScreen: HistoryListScreen? = null
    private var historyView: View? = null
    private var historyPermission: View? = null
    private var restoredHistorySelection = emptyList<String>()
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var retainedChatOffered = false
    private val assistantStateListener: () -> Unit = {
        historyScreen?.updateAssistantAction()
        if(ProbeState.serviceRunning && navigation.current == AppPage.TUTORIAL) {
            toast("真实悬浮球已开启，已退出模拟体验；请关闭后再继续练习")
            exitTutorial()
        }
    }
    private data class RetainedApp(
        val pages: List<String>, val chat: ChatScreen.RetainedConversation?,
        val settingsPage: String?, val historyY: Int, val historySelection: List<String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedApp
        val entry = AppEntry.take(intent)
        guidePreferences.initialize(onboarding.contains("overlay_prompted") || onboarding.contains("assistant_enabled") ||
            SettingsStore(this).hasSavedConfiguration())
        navigation = AppNavigation(savedInstanceState?.getStringArrayList("pages") ?: retained?.pages.orEmpty())
        tutorialFlow = TutorialFlow(TutorialStep.restore(savedInstanceState?.getString("tutorial_step") ?: guidePreferences.load().step.name))
        assistantStartAttempted = savedInstanceState?.getBoolean("assistant_start_attempted", false) ?: false
        overlayWasGranted = savedInstanceState?.getBoolean("overlay_was_granted", false) ?: false
        allowOnboarding = savedInstanceState?.getBoolean("allow_onboarding") ?: (entry == null)
        historyY = savedInstanceState?.getInt("history_y") ?: retained?.historyY ?: 0
        restoredHistorySelection = savedInstanceState?.getStringArrayList("history_selection")
            ?: retained?.historySelection.orEmpty()
        container = FrameLayout(this).apply { setBackgroundColor(BACKGROUND); fitsSystemWindows = true }
        setContentView(container)
        if(navigation.hasChat) chatScreen = createChat(savedState=savedInstanceState?.getBundle("chat"),retained=retained?.chat)
        if("SETTINGS" in navigation.snapshot()) createSettings(savedInstanceState?.getString("settings_page") ?: retained?.settingsPage)
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
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if(Build.VERSION.SDK_INT < 33)return
        val canHandleBack = navigation.canGoBack || (navigation.current == AppPage.HISTORY && historyScreen?.selecting == true)
        if(canHandleBack && backCallback == null) {
            backCallback=android.window.OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
            }
        } else if(!canHandleBack) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) };backCallback=null
        }
    }

    // OEM/key paths may call this even on API33+. Both paths must use the same internal stack.
    @Deprecated("Legacy key bridge; gesture Back uses OnBackInvokedCallback")
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if(navigation.canGoBack || (navigation.current == AppPage.HISTORY && historyScreen?.selecting == true))navigateBack() else super.onBackPressed() }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0) }
    }

    private fun navigateBack() {
        if(navigation.current == AppPage.TUTORIAL && tutorialScreen?.back() == true)return
        if(navigation.current == AppPage.HISTORY && historyScreen?.back() == true)return
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
        historyScreen?.back()
        hideKeyboard();chatScreen?.dispose();chatScreen=null
        navigation.openHistory();showCurrent()
    }

    private fun openSettings() {
        if(navigation.current == AppPage.SETTINGS)return
        historyScreen?.back()
        hideKeyboard();navigation.openSettings();createSettings()
    }

    private fun createSettings(page: String? = null) {
        settingsScreen=SettingsScreen(this,::present,::navigateBack,page,::openTutorial)
    }

    private fun openTutorial() {
        if(ProbeState.serviceRunning) {
            toast("请先在首页关闭真实悬浮球，再开始模拟体验，避免误触真实功能")
            return
        }
        val progress=guidePreferences.load().start()
        guidePreferences.save(progress)
        hideKeyboard(); tutorialFlow=TutorialFlow(progress.step); tutorialScreen=null
        navigation.openTutorial(); showCurrent()
    }

    private fun showTutorial() {
        if(ProbeState.serviceRunning) { exitTutorial(); return }
        val screen=tutorialScreen ?: TutorialScreen(this,tutorialFlow,::navigateBack,::exitTutorial,
            { step -> guidePreferences.save(guidePreferences.load().advance(step)) }).also { tutorialScreen=it }
        present(screen.view)
    }

    private fun exitTutorial() {
        tutorialScreen=null
        openHistory()
    }

    private fun createChat(id: String? = null,savedState: Bundle? = null,
        retained: ChatScreen.RetainedConversation? = null,autoAskText: Boolean = false): ChatScreen =
        ChatScreen(this,::navigateBack,::openSettings,id,savedState,retained,autoAskText)

    private fun openChat(id: String?,autoAskText: Boolean = false): Unit = leaveSettings {
        historyScreen?.back()
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
            AppPage.TUTORIAL -> openTutorial()
        }
    }

    private fun showCurrent() {
        when(navigation.current) {
            AppPage.HISTORY -> showHistory()
            AppPage.CHAT -> chatScreen?.let { present(it.view) } ?: openHistory()
            AppPage.SETTINGS -> settingsScreen?.show() ?: createSettings()
            AppPage.TUTORIAL -> showTutorial()
        }
    }

    private fun showHistory() {
        var root = historyView
        if(root == null) {
            val history = HistoryListScreen(this, onOpen = { openChat(it) }, onSettings = ::openSettings,
                onToggleAssistant = ::toggleAssistant,
                onSelectionChanged = ::historySelectionChanged, initialSelection = restoredHistorySelection,
                onIntro = ::showGuideInvitation)
            restoredHistorySelection = emptyList()
            historyScreen = history
            historyScroll = history.scrollView
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND)
                addView(history.toolbar)
                historyPermission = column().apply {
                    addView(label("实际使用悬浮球需要允许悬浮显示；模拟体验不需要。", 12f, true))
                    action("允许悬浮显示", true) { requestOverlayPermission() }
                }.also { addView(it) }
                addView(history.scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            historyView = root
            history.restoreScroll(historyY)
        } else historyScreen?.refresh()
        historySelectionChanged()
        present(root)
    }

    private fun historySelectionChanged() {
        val selecting=historyScreen?.selecting == true
        historyPermission?.visibility=if(selecting || guidePreferences.load().showOnHome || Settings.canDrawOverlays(this))View.GONE else View.VISIBLE
        updateBackCallback()
    }

    private fun showGuideInvitation(rows: LinearLayout) {
        val progress=guidePreferences.load()
        if(!progress.showOnHome)return
        rows.addView(column().apply {
            background=card(SURFACE)
            addView(label(if(progress.status == GuideStatus.IN_PROGRESS) "继续模拟体验" else "先练习一次截图提问",17f))
            addView(label("不用配置 API。使用内置示例，学习悬浮球和结果小窗的操作；不联网、不消耗 Token。",14f,true).apply {
                setPadding(0,dp(8),0,0)
            })
            action(if(progress.status == GuideStatus.IN_PROGRESS) "继续体验" else "开始体验",true,::openTutorial)
            action("跳过") {
                guidePreferences.save(progress.skip()); historyScreen?.refresh(); historySelectionChanged()
            }
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
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

    private fun toggleAssistant() {
        if (ProbeState.serviceRunning) {
            AssistantControl.stop(this)
        } else {
            AssistantControl.setEnabled(this, true)
            allowOnboarding = true
            assistantStartAttempted = Settings.canDrawOverlays(this)
            if (assistantStartAttempted) ensureAssistant() else requestOverlayPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        ProbeState.addServiceListener(assistantStateListener)
        assistantStateListener()
    }

    override fun onStop() {
        ProbeState.removeServiceListener(assistantStateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if(navigation.current == AppPage.TUTORIAL)return
        if(navigation.current == AppPage.HISTORY)showHistory()
        if(!allowOnboarding)return
        val allowed=Settings.canDrawOverlays(this)
        val newlyGranted=allowed && !overlayWasGranted
        overlayWasGranted=allowed
        if(allowed) {
            onboarding.edit().putBoolean("overlay_prompted",true).apply()
            // Resume only an explicitly enabled assistant. A new install must never prompt or start during the guide.
            if(onboarding.getBoolean("assistant_enabled",false) && (!assistantStartAttempted || newlyGranted)) { assistantStartAttempted=true;ensureAssistant() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pages",ArrayList(navigation.snapshot()))
        outState.putString("tutorial_step",tutorialFlow.step.name)
        outState.putString("settings_page",settingsScreen?.pageName())
        outState.putInt("history_y",historyScroll?.scrollY ?: historyY)
        outState.putStringArrayList("history_selection", ArrayList(historyScreen?.selectedIds() ?: restoredHistorySelection))
        chatScreen?.let { chat -> outState.putBundle("chat",Bundle().also { chat.saveState(it) }) }
        outState.putBoolean("assistant_start_attempted",assistantStartAttempted)
        outState.putBoolean("overlay_was_granted",overlayWasGranted)
        outState.putBoolean("allow_onboarding",allowOnboarding)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any {
        val retained=chatScreen?.retain();retainedChatOffered=retained!=null
        return RetainedApp(navigation.snapshot(),retained,settingsScreen?.pageName(),historyScroll?.scrollY ?: historyY,
            historyScreen?.selectedIds() ?: restoredHistorySelection)
    }

    override fun onDestroy() {
        if(Build.VERSION.SDK_INT>=33)backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        settingsScreen?.dispose();settingsScreen=null
        chatScreen?.dispose(retaining=isChangingConfigurations && retainedChatOffered);chatScreen=null
        super.onDestroy()
    }

    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
}
