package com.alwayside.android

internal enum class AppPage { HISTORY, CHAT, SETTINGS, HELP, TUTORIAL, PREPARATION, PREPARATION_SETTINGS }
internal enum class AppBack { SETTINGS_CATEGORY, PAGE, EXIT }

/** Application destinations share one Android window; only these routes form its back stack. */
internal class AppNavigation(savedPages: List<String> = emptyList()) {
    private val pages = mutableListOf(AppPage.HISTORY)
    init {
        // Restore only meaningful stacks, never a settings page without its application root.
        var restored = savedPages.mapNotNull { name -> AppPage.entries.find { it.name == name } }
        // Upgrade the previous guide stack without discarding its preparation destination.
        if(restored.take(2)==listOf(AppPage.HISTORY,AppPage.PREPARATION)) {
            restored=listOf(AppPage.HISTORY,AppPage.HELP,AppPage.PREPARATION)+restored.drop(2).map {
                if(it==AppPage.SETTINGS)AppPage.PREPARATION_SETTINGS else it
            }
        } else if(restored.lastOrNull()==AppPage.TUTORIAL && AppPage.HELP !in restored) {
            restored=restored.dropLast(1)+listOf(AppPage.HELP,AppPage.TUTORIAL)
        }
        val roots=listOf(listOf(AppPage.HISTORY),listOf(AppPage.HISTORY,AppPage.CHAT),
            listOf(AppPage.HISTORY,AppPage.SETTINGS),listOf(AppPage.HISTORY,AppPage.CHAT,AppPage.SETTINGS))
        val allowed=roots.flatMap { root -> listOf(root,root+AppPage.HELP,
            root+listOf(AppPage.HELP,AppPage.TUTORIAL),root+listOf(AppPage.HELP,AppPage.PREPARATION),
            root+listOf(AppPage.HELP,AppPage.PREPARATION,AppPage.PREPARATION_SETTINGS)) }
        if (restored in allowed) {
            pages.clear(); pages.addAll(restored)
        }
    }
    val current: AppPage get() = pages.last()
    val hasChat: Boolean get() = AppPage.CHAT in pages
    val canGoBack: Boolean get() = pages.size > 1
    fun snapshot(): List<String> = pages.map { it.name }
    fun openSettings() {
        if(current==AppPage.SETTINGS)return
        if(current!=AppPage.HISTORY && current!=AppPage.CHAT)openHistory()
        pages.add(AppPage.SETTINGS)
    }
    fun openHelp() {
        val existing=pages.indexOf(AppPage.HELP)
        if(existing>=0)while(pages.lastIndex>existing)pages.removeAt(pages.lastIndex)
        else pages.add(AppPage.HELP)
    }
    fun openTutorial() { if(current!=AppPage.TUTORIAL){openHelp();pages.add(AppPage.TUTORIAL)} }
    fun openPreparation() { openHelp();pages.add(AppPage.PREPARATION) }
    fun openPreparationSettings() { check(current==AppPage.PREPARATION);pages.add(AppPage.PREPARATION_SETTINGS) }
    fun openChat() { pages.clear(); pages.addAll(listOf(AppPage.HISTORY, AppPage.CHAT)) }
    fun openHistory() { pages.clear(); pages.add(AppPage.HISTORY) }
    fun back(settingsAtRoot: Boolean = true): AppBack {
        if (current == AppPage.SETTINGS && !settingsAtRoot) return AppBack.SETTINGS_CATEGORY
        if (!canGoBack) return AppBack.EXIT
        pages.removeAt(pages.lastIndex)
        return AppBack.PAGE
    }
}
