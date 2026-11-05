package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun preparationSettingsReturnToPreparationAndRestoreAfterRecreation() {
        val nav = AppNavigation().apply { openTutorial(); openPreparation(); openPreparationSettings() }
        val restored = AppNavigation(nav.snapshot())
        assertEquals(listOf("HISTORY", "HELP", "PREPARATION", "PREPARATION_SETTINGS"), restored.snapshot())
        assertEquals(AppBack.PAGE, restored.back())
        assertEquals(AppPage.PREPARATION, restored.current)
        assertEquals(AppBack.PAGE, restored.back())
        assertEquals(AppPage.HELP, restored.current)
    }

    @Test fun tutorialFromPreparationHelpKeepsThePreparationParent() {
        val nav = AppNavigation().apply { openPreparation(); back(); openTutorial() }
        val restored = AppNavigation(nav.snapshot())
        assertEquals(AppPage.TUTORIAL, restored.current)
        restored.back(); assertEquals(AppPage.HELP, restored.current)
        restored.back(); assertEquals(AppPage.HISTORY, restored.current)
    }
    @Test fun tutorialRestoresItsParentAndBackReturnsToSettings() {
        val nav = AppNavigation().apply { openSettings(); openTutorial(); openTutorial() }
        val restored = AppNavigation(nav.snapshot())
        assertEquals(listOf("HISTORY", "SETTINGS", "HELP", "TUTORIAL"), restored.snapshot())
        assertEquals(AppBack.PAGE, restored.back())
        assertEquals(AppPage.HELP, restored.current)
        assertEquals(AppPage.HISTORY, AppNavigation(listOf("TUTORIAL")).current)
    }
    @Test fun preparationKeepsHelpAndItsOriginalSettingsParent() {
        val nav=AppNavigation().apply { openChat();openSettings();openHelp();openPreparation();openPreparationSettings() }
        var restored=AppNavigation(nav.snapshot())
        assertEquals(AppBack.PAGE,restored.back(false))
        assertEquals(AppPage.PREPARATION,restored.current)
        restored=AppNavigation(restored.snapshot())
        restored.back();assertEquals(AppPage.HELP,restored.current)
        restored.back();assertEquals(AppPage.SETTINGS,restored.current)
        restored.back();assertEquals(AppPage.CHAT,restored.current)
    }
    @Test fun legacyPreparationStateGetsAnExplicitHelpParent() {
        val nav=AppNavigation(listOf("HISTORY","PREPARATION","SETTINGS"))
        assertEquals(AppPage.PREPARATION_SETTINGS,nav.current)
        nav.back(false);assertEquals(AppPage.PREPARATION,nav.current)
        nav.back();assertEquals(AppPage.HELP,nav.current)
    }
    @Test fun settingsDetailBackKeepsSettingsBeforeReturningToHistory() {
        val nav = AppNavigation()
        nav.openSettings()
        assertEquals(AppBack.SETTINGS_CATEGORY, nav.back(settingsAtRoot = false))
        assertEquals(AppPage.SETTINGS, nav.current)
        assertEquals(AppBack.PAGE, nav.back())
        assertEquals(AppPage.HISTORY, nav.current)
        assertEquals(AppBack.EXIT, nav.back())
    }
    @Test fun chatSettingsReturnToChatBeforeHistory() {
        val nav = AppNavigation()
        nav.openChat(); nav.openSettings()
        assertEquals(AppBack.SETTINGS_CATEGORY, nav.back(false))
        assertTrue(nav.hasChat)
        assertEquals(AppBack.PAGE, nav.back())
        assertEquals(AppPage.CHAT, nav.current)
        assertEquals(AppBack.PAGE, nav.back())
        assertFalse(nav.hasChat)
        assertEquals(AppPage.HISTORY, nav.current)
    }
    @Test fun repeatedTabsDoNotStackDuplicateDestinations() {
        val nav = AppNavigation()
        repeat(5) { nav.openSettings() }
        assertEquals(listOf("HISTORY", "SETTINGS"), nav.snapshot())
        nav.openHistory(); nav.openSettings()
        assertEquals(AppBack.PAGE, nav.back())
        assertEquals(AppBack.EXIT, nav.back())
    }
    @Test fun restoredSettingsPreservesItsChatParent() {
        val original = AppNavigation().apply { openChat(); openSettings() }
        val restored = AppNavigation(original.snapshot())
        assertEquals(AppBack.SETTINGS_CATEGORY, restored.back(false))
        assertEquals(AppBack.PAGE, restored.back())
        assertEquals(AppPage.CHAT, restored.current)
        assertEquals(AppPage.HISTORY, AppNavigation(listOf("SETTINGS")).current)
    }
    @Test fun explicitHistoryAndNewChatDiscardObsoleteSettingsRoutes() {
        val nav = AppNavigation().apply { openChat(); openSettings() }
        nav.openHistory()
        assertFalse(nav.hasChat)
        assertEquals(AppBack.EXIT, nav.back())
        nav.openSettings(); nav.openChat()
        assertEquals(listOf("HISTORY", "CHAT"), nav.snapshot())
        assertEquals(AppBack.PAGE, nav.back())
        assertEquals(AppPage.HISTORY, nav.current)
    }
}
