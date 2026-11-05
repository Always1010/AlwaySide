package com.alwayside.android

internal enum class AppPage { HISTORY, CHAT, SETTINGS, TUTORIAL }
internal enum class AppBack { SETTINGS_CATEGORY, PAGE, EXIT }

/** Application destinations share one Android window; only these routes form its back stack. */
internal class AppNavigation(savedPages: List<String> = emptyList()) {
    private val pages = mutableListOf(AppPage.HISTORY)
    init {
        // Restore only meaningful stacks, never a settings page without its application root.
        val restored = savedPages.mapNotNull { name -> AppPage.entries.find { it.name == name } }
        val parent = if (restored.lastOrNull() == AppPage.TUTORIAL) restored.dropLast(1) else restored
        if (parent in listOf(listOf(AppPage.HISTORY), listOf(AppPage.HISTORY, AppPage.CHAT),
                listOf(AppPage.HISTORY, AppPage.SETTINGS),
                listOf(AppPage.HISTORY, AppPage.CHAT, AppPage.SETTINGS))) {
            pages.clear(); pages.addAll(restored)
        }
    }
    val current: AppPage get() = pages.last()
    val hasChat: Boolean get() = AppPage.CHAT in pages
    val canGoBack: Boolean get() = pages.size > 1
    fun snapshot(): List<String> = pages.map { it.name }
    fun openSettings() { if (current != AppPage.SETTINGS) pages.add(AppPage.SETTINGS) }
    fun openTutorial() { if (current != AppPage.TUTORIAL) pages.add(AppPage.TUTORIAL) }
    fun openChat() { pages.clear(); pages.addAll(listOf(AppPage.HISTORY, AppPage.CHAT)) }
    fun openHistory() { pages.clear(); pages.add(AppPage.HISTORY) }
    fun back(settingsAtRoot: Boolean = true): AppBack {
        if (current == AppPage.SETTINGS && !settingsAtRoot) return AppBack.SETTINGS_CATEGORY
        if (!canGoBack) return AppBack.EXIT
        pages.removeAt(pages.lastIndex)
        return AppBack.PAGE
    }
}
