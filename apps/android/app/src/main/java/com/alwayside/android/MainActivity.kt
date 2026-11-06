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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
    private var preparationScreen: PreparationScreen? = null
    private var preparationSettings: SettingsScreen? = null
    private var preparationSettingsPage: String? = null
    private var restoredSettingsPage: String? = null
    private var pendingChatState: Bundle? = null
    private var pendingRetainedChat: ChatScreen.RetainedConversation? = null
    private var historyScroll: ScrollView? = null
    private var historyY = 0
    private var historyScreen: HistoryListScreen? = null
    private var historyView: View? = null
    private var historyPermission: View? = null
    private var restoredHistorySelection = emptyList<String>()
    private var restoredHistorySelecting = false
    private var restoredHistoryBrowse = HistoryBrowse()
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var retainedChatOffered = false
    private var previewOwner = Any()
    private var previewForeground = true
    private var previewRecordingAccepted = false
    private var previewEntryDialog: AlertDialog? = null
    private var slideLesson: LongPressSlideLesson? = null
    private var slideDialog: AlertDialog? = null
    private val assistantStateListener: () -> Unit = {
        historyScreen?.updateAssistantAction()
        if(navigation.current == AppPage.PREPARATION)preparationScreen?.refresh()
    }
    private data class RetainedApp(
        val pages: List<String>, val chat: ChatScreen.RetainedConversation?,
        val settingsPage: String?, val historyY: Int, val historySelection: List<String>,
        val historyBrowse: HistoryBrowse, val historySelecting: Boolean,
        val previewOwner: Any, val slideLesson: LongPressSlideLesson?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedApp
        previewOwner = retained?.previewOwner ?: Any()
        slideLesson = retained?.slideLesson ?: savedInstanceState?.takeIf { it.containsKey("slide_lesson") }?.let {
            LongPressSlideLesson(it.getInt("slide_lesson"), it.getBoolean("slide_passed"))
        }
        val entry = AppEntry.take(intent) ?: AudioPlaybackService.takeEntry(intent, this)
        guidePreferences.initialize(onboarding.contains("overlay_prompted") || onboarding.contains("assistant_enabled") ||
            SettingsStore(this).hasSavedConfiguration())
        navigation = AppNavigation(savedInstanceState?.getStringArrayList("pages") ?: retained?.pages.orEmpty())
        val guideProgress=guidePreferences.load()
        tutorialFlow = TutorialFlow(TutorialStep.restore(savedInstanceState?.getString("tutorial_step") ?: guideProgress.step.name),guideProgress.completedLessons)
        assistantStartAttempted = savedInstanceState?.getBoolean("assistant_start_attempted", false) ?: false
        overlayWasGranted = savedInstanceState?.getBoolean("overlay_was_granted", false) ?: false
        allowOnboarding = savedInstanceState?.getBoolean("allow_onboarding") ?: (entry == null)
        historyY = savedInstanceState?.getInt("history_y") ?: retained?.historyY ?: 0
        restoredHistorySelection = savedInstanceState?.getStringArrayList("history_selection")
            ?: retained?.historySelection.orEmpty()
        restoredHistorySelecting = savedInstanceState?.getBoolean("history_selecting", restoredHistorySelection.isNotEmpty())
            ?: retained?.historySelecting ?: restoredHistorySelection.isNotEmpty()
        restoredHistoryBrowse = if (savedInstanceState != null) HistoryBrowse(
            savedInstanceState.getBoolean("history_favorites"), savedInstanceState.getString("history_tag"),
            savedInstanceState.getBoolean("history_untagged"), savedInstanceState.getString("history_query").orEmpty(),
            HistoryDateRange.restore(savedInstanceState.getString("history_date_start"), savedInstanceState.getString("history_date_end")),
            savedInstanceState.getInt("history_page"),
        ) else retained?.historyBrowse ?: HistoryBrowse()
        container = FrameLayout(this).apply { setBackgroundColor(BACKGROUND); fitsSystemWindows = true }
        setContentView(container)
        restoredSettingsPage=savedInstanceState?.getString("settings_page") ?: retained?.settingsPage
        preparationSettingsPage=savedInstanceState?.getString("preparation_settings_page") ?: "API"
        pendingChatState=savedInstanceState?.getBundle("chat"); pendingRetainedChat=retained?.chat
        // Restoring a tutorial must not construct its background chat/provider or decrypt settings.
        if(navigation.hasChat && navigation.current != AppPage.TUTORIAL) restoreChat()
        if(navigation.current == AppPage.SETTINGS) createSettings(restoredSettingsPage)
        if(entry != null) route(entry) else showCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        val entry = AppEntry.take(intent) ?: AudioPlaybackService.takeEntry(intent, this)
        if(entry != null) route(entry)
        else if(intent.action == Intent.ACTION_MAIN) { allowOnboarding=true;assistantStartAttempted=false }
    }

    private fun present(view: View) {
        if (navigation.current !in setOf(AppPage.SETTINGS, AppPage.PREPARATION_SETTINGS)) dismissSlidePreview(clearLesson = true)
        syncPreview()
        if(container.childCount != 1 || container.getChildAt(0) !== view) {
            hideKeyboard()
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews(); container.addView(view,FrameLayout.LayoutParams(-1,-1))
        }
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if(Build.VERSION.SDK_INT < 33)return
        val canHandleBack = navigation.canGoBack || (navigation.current == AppPage.HISTORY && historyScreen?.canGoBack == true)
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
    override fun onBackPressed() { if(navigation.canGoBack || (navigation.current == AppPage.HISTORY && historyScreen?.canGoBack == true))navigateBack() else super.onBackPressed() }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0) }
    }

    private fun navigateBack() {
        if(navigation.current==AppPage.PREPARATION_SETTINGS) {
            if(preparationSettings?.consumeEditorBack()==true)return
            preparationSettings?.requestLeave {
                preparationSettings?.dispose();preparationSettings=null
                navigation.back();showCurrent()
            }
            return
        }
        if(navigation.current == AppPage.TUTORIAL && tutorialScreen?.back() == true)return
        if(navigation.current == AppPage.HISTORY && historyScreen?.back() == true)return
        hideKeyboard()
        when(navigation.back(settingsAtRoot=settingsScreen?.isHome ?: true)) {
            AppBack.SETTINGS_CATEGORY -> settingsScreen?.back()
            AppBack.PAGE -> {
                if("SETTINGS" !in navigation.snapshot()) { settingsScreen?.dispose();settingsScreen=null }
                if(!navigation.hasChat) { chatScreen?.dispose();chatScreen=null;discardPendingChat() }
                showCurrent()
            }
            AppBack.EXIT -> finish()
        }
    }

    private fun leaveSettings(then: () -> Unit) {
        val settings=if(navigation.current==AppPage.PREPARATION_SETTINGS)preparationSettings else settingsScreen
        if(settings == null)then() else settings.requestLeave {
            settings.dispose()
            if(settings===preparationSettings)preparationSettings=null else settingsScreen=null
            then()
        }
    }

    private fun openHistory(): Unit = leaveSettings {
        historyScreen?.prepareToLeave()
        hideKeyboard();chatScreen?.dispose();chatScreen=null
        discardPendingChat()
        navigation.openHistory();showCurrent()
    }

    private fun openSettings() {
        if(navigation.current == AppPage.SETTINGS)return
        historyScreen?.prepareToLeave()
        hideKeyboard();navigation.openSettings();createSettings()
    }

    private fun createSettings(page: String? = null) {
        restoredSettingsPage=page
        settingsScreen=SettingsScreen(this,::present,::navigateBack,page,::openTutorial,::openPreparation,::openHelp,::openSlidePreview)
    }

    private fun openHelp() { hideKeyboard();navigation.openHelp();showCurrent() }

    private fun openTutorial(): Unit = requestPreview {
        val progress=guidePreferences.load().start()
        guidePreferences.save(progress)
        hideKeyboard(); tutorialFlow=TutorialFlow(progress.step,progress.completedLessons); tutorialScreen=null
        navigation.openTutorial(); showCurrent()
    }

    private fun showTutorial() {
        syncPreview()
        val screen=tutorialScreen ?: TutorialScreen(this,tutorialFlow,::navigateBack,::exitTutorial,
            { step -> guidePreferences.save(guidePreferences.load().advance(step,tutorialFlow.completedLessons)) },::openPreparation).also { tutorialScreen=it }
        present(screen.view)
    }

    private fun exitTutorial() {
        tutorialScreen=null
        navigation.openHelp();showCurrent()
    }

    private fun openPreparation() {
        hideKeyboard(); tutorialScreen=null
        navigation.openPreparation();showCurrent()
    }

    private fun requestPreview(enter: () -> Unit) {
        if (!ProbeState.recording || previewRecordingAccepted) { enter(); return }
        if (previewEntryDialog?.isShowing == true) return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("正在录音")
            .setMessage("结束并保留当前录音后进入模拟体验，不会自动转写或发送。")
            .setNegativeButton("继续录音") { _, _ -> cancelPreviewEntry() }
            .setPositiveButton("结束并体验") { _, _ ->
                AssistantPreview.finishRecording?.invoke()
                previewRecordingAccepted = true
                enter()
            }
            .setOnCancelListener { cancelPreviewEntry() }.create()
        previewEntryDialog = dialog
        dialog.setOnDismissListener { if (previewEntryDialog === dialog) previewEntryDialog = null }
        dialog.show()
    }

    private fun cancelPreviewEntry() {
        dismissSlidePreview(clearLesson = true)
        if (navigation.current == AppPage.TUTORIAL) { tutorialScreen = null; navigation.openHelp(); showCurrent() }
        syncPreview()
    }

    private fun syncPreview() {
        val wanted = previewForeground && (navigation.current == AppPage.TUTORIAL || slideLesson != null)
        if (wanted && ProbeState.recording && !previewRecordingAccepted && !AssistantPreview.overlays.owns(previewOwner)) {
            requestPreview { syncPreview(); showSlidePreview() }
            return
        }
        AssistantPreview.overlays.setActive(previewOwner, wanted)
        if (!wanted) previewRecordingAccepted = false
    }

    private fun openSlidePreview(): Unit = requestPreview {
        slideLesson = LongPressSlideLesson()
        syncPreview(); showSlidePreview()
    }

    private fun showSlidePreview() {
        val lesson = slideLesson ?: return
        if (!previewForeground || !AssistantPreview.overlays.owns(previewOwner) || slideDialog != null) return
        val dialog = LongPressSlideTutorial.show(this, InteractionPreferences(this).load(), lesson = lesson)
        slideDialog = dialog
        dialog.setOnDismissListener {
            if (slideDialog === dialog) {
                slideDialog = null
                if (previewForeground && !isChangingConfigurations) { slideLesson = null; syncPreview() }
            }
        }
    }

    private fun dismissSlidePreview(clearLesson: Boolean) {
        if (clearLesson) slideLesson = null
        val dialog = slideDialog; slideDialog = null
        dialog?.dismiss()
    }

    private fun showPreparation() {
        val screen=preparationScreen ?: PreparationScreen(this,::navigateBack,{ page ->
            hideKeyboard();preparationSettingsPage=page;navigation.openPreparationSettings();showCurrent()
        },::toggleAssistant).also { preparationScreen=it }
        screen.refresh(); present(screen.view)
    }

    private fun restoreChat() {
        chatScreen=createChat(savedState=pendingChatState,retained=pendingRetainedChat)
        pendingChatState=null;pendingRetainedChat=null
    }

    private fun discardPendingChat() {
        pendingRetainedChat?.let {
            if(it.handoff != null) {
                if(!it.handoff.returnToFloating(AppConversationState(it.draft,it.webSearchOverride,it.reuseImage)))it.handoff.dispose()
            } else it.session.close()
        }
        pendingRetainedChat=null;pendingChatState=null
    }

    private fun createChat(id: String? = null,savedState: Bundle? = null,
        retained: ChatScreen.RetainedConversation? = null,autoAskText: Boolean = false): ChatScreen =
        ChatScreen(this,::navigateBack,id,savedState,retained,autoAskText)

    private fun openChat(id: String?,autoAskText: Boolean = false): Unit = leaveSettings {
        historyScreen?.prepareToLeave()
        hideKeyboard()
        if(chatScreen?.matches(id) != true) {
            discardPendingChat()
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
            AppPage.PREPARATION -> openPreparation()
            AppPage.HELP -> openHelp()
            AppPage.PREPARATION_SETTINGS -> openPreparation()
        }
    }

    private fun showCurrent() {
        when(navigation.current) {
            AppPage.HISTORY -> showHistory()
            AppPage.CHAT -> {
                if(chatScreen == null)restoreChat()
                chatScreen?.let { present(it.view) }
            }
            AppPage.SETTINGS -> settingsScreen?.show() ?: createSettings(restoredSettingsPage)
            AppPage.TUTORIAL -> showTutorial()
            AppPage.PREPARATION -> showPreparation()
            AppPage.HELP -> present(HelpScreen(this,::navigateBack,::openTutorial,::openPreparation,guidePreferences.load().completedLessons.size==TutorialLesson.entries.size).view)
            AppPage.PREPARATION_SETTINGS -> {
                val screen=preparationSettings ?: SettingsScreen(this,::present,::navigateBack,preparationSettingsPage,
                    onSlideTutorial=::openSlidePreview).also { preparationSettings=it }
                screen.show()
            }
        }
    }

    private fun showHistory() {
        var root = historyView
        if(root == null) {
            val history = HistoryListScreen(this, onOpen = { openChat(it) }, onSettings = ::openSettings,
                onToggleAssistant = ::toggleAssistant,
                onSelectionChanged = ::historySelectionChanged, initialSelection = restoredHistorySelection,
                initialSelecting = restoredHistorySelecting,
                onIntro = ::showGuideInvitation, browse = restoredHistoryBrowse)
            restoredHistorySelection = emptyList()
            restoredHistorySelecting = false
            historyScreen = history
            historyScroll = history.scrollView
            root = history.wrap(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND)
                addView(history.toolbar)
                addView(history.searchView)
                historyPermission = column().apply {
                    addView(label("实际使用悬浮球需要允许悬浮显示；新手引导不需要。", 12f, true))
                    action("准备实际使用", true, ::openPreparation)
                }.also { addView(it) }
                addView(history.scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(history.selectionActions, LinearLayout.LayoutParams(-1, -2))
            })
            historyView = root
            history.restoreScroll(historyY)
        } else historyScreen?.refresh()
        historySelectionChanged()
        present(root)
    }

    private fun historySelectionChanged() {
        val selecting=historyScreen?.selecting == true
        historyPermission?.visibility=if(selecting || historyScreen?.browse?.favorites == true || guidePreferences.load().showOnHome || Settings.canDrawOverlays(this))View.GONE else View.VISIBLE
        updateBackCallback()
    }

    private fun showGuideInvitation(rows: LinearLayout) {
        val progress=guidePreferences.load()
        if(!progress.showOnHome)return
        rows.addView(column().apply {
            background=card(SURFACE)
            addView(label(if(progress.status == GuideStatus.IN_PROGRESS) "继续新手引导" else "新手引导",17f))
            addView(label("不用配置 API。使用内置示例，了解悬浮球和结果小窗的操作；不联网、不消耗 Token。",14f,true).apply {
                setPadding(0,dp(8),0,0)
            })
            action(if(progress.status == GuideStatus.IN_PROGRESS) "继续引导" else "开始引导",true,::openTutorial).apply { applyOnboardingPrimaryStyle() }
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
        chatScreen?.setPlaybackForeground(true)
        previewForeground = true
        syncPreview(); showSlidePreview()
        ProbeState.addServiceListener(assistantStateListener)
        assistantStateListener()
    }

    override fun onStop() {
        if (!isChangingConfigurations) chatScreen?.setPlaybackForeground(false)
        previewForeground = false
        dismissSlidePreview(clearLesson = false)
        previewEntryDialog?.dismiss(); previewEntryDialog = null
        if (!isChangingConfigurations) syncPreview()
        if (!isChangingConfigurations) chatScreen?.let { chat ->
            val state = Bundle().also { chat.saveState(it) }
            if (chat.returnToFloating()) {
                pendingChatState = state
                chat.dispose(); chatScreen = null
            }
        }
        ProbeState.removeServiceListener(assistantStateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if(navigation.current == AppPage.TUTORIAL || slideLesson != null || previewEntryDialog != null)return
        if(navigation.hasChat && chatScreen == null) {
            restoreChat()
            if(navigation.current == AppPage.CHAT)showCurrent()
        }
        if(navigation.current == AppPage.HISTORY)showHistory()
        if(navigation.current == AppPage.PREPARATION)showPreparation()
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
        slideLesson?.let { outState.putInt("slide_lesson", it.index); outState.putBoolean("slide_passed", it.passed) }
        outState.putStringArrayList("pages",ArrayList(navigation.snapshot()))
        outState.putString("tutorial_step",tutorialFlow.step.name)
        outState.putString("settings_page",settingsScreen?.pageName() ?: restoredSettingsPage)
        outState.putString("preparation_settings_page",preparationSettings?.pageName() ?: preparationSettingsPage)
        outState.putInt("history_y",historyScroll?.scrollY ?: historyY)
        outState.putStringArrayList("history_selection", ArrayList(historyScreen?.selectedIds() ?: restoredHistorySelection))
        outState.putBoolean("history_selecting", historyScreen?.selecting ?: restoredHistorySelecting)
        val browse = historyScreen?.browse ?: restoredHistoryBrowse
        outState.putInt("history_page", browse.page)
        outState.putBoolean("history_favorites", browse.favorites)
        outState.putString("history_tag", browse.tag)
        outState.putBoolean("history_untagged", browse.untagged)
        outState.putString("history_query", browse.query)
        outState.putString("history_date_start", browse.dateRange?.start?.toString())
        outState.putString("history_date_end", browse.dateRange?.end?.toString())
        chatScreen?.let { chat -> outState.putBundle("chat",Bundle().also { chat.saveState(it) }) }
            ?: pendingChatState?.let { outState.putBundle("chat",it) }
        outState.putBoolean("assistant_start_attempted",assistantStartAttempted)
        outState.putBoolean("overlay_was_granted",overlayWasGranted)
        outState.putBoolean("allow_onboarding",allowOnboarding)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any {
        val retained=chatScreen?.retain() ?: pendingRetainedChat;retainedChatOffered=retained!=null
        return RetainedApp(navigation.snapshot(),retained,settingsScreen?.pageName() ?: restoredSettingsPage,historyScroll?.scrollY ?: historyY,
            historyScreen?.selectedIds() ?: restoredHistorySelection, historyScreen?.browse ?: restoredHistoryBrowse,
            historyScreen?.selecting ?: restoredHistorySelecting,previewOwner,slideLesson)
    }

    override fun onDestroy() {
        dismissSlidePreview(clearLesson = false)
        previewEntryDialog?.dismiss(); previewEntryDialog = null
        if (!isChangingConfigurations) AssistantPreview.overlays.setActive(previewOwner, false)
        if(Build.VERSION.SDK_INT>=33)backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        historyScreen?.dispose()
        settingsScreen?.dispose();settingsScreen=null
        preparationSettings?.dispose();preparationSettings=null
        chatScreen?.dispose(retaining=isChangingConfigurations && retainedChatOffered);chatScreen=null
        if(!isChangingConfigurations || !retainedChatOffered)discardPendingChat()
        super.onDestroy()
    }

    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
}
