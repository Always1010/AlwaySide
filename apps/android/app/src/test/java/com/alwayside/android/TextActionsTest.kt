package com.alwayside.android

import android.view.Menu
import android.view.MenuItem
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TextActionsTest {
    @Test fun onlyBasicEditingAndExplicitExtrasRemainVisible() {
        val basics = listOf(android.R.id.copy, android.R.id.cut, android.R.id.paste,
            android.R.id.pasteAsPlainText, android.R.id.selectAll, android.R.id.shareText,
            android.R.id.undo, android.R.id.redo, android.R.id.startSelectingText,
            android.R.id.stopSelectingText, android.R.id.selectTextMode).map { item(it) }
        val reading = item(100, "点读")
        val share = item(101, "分享")
        val translation = item(102, "系统翻译")
        val blocked = listOf(item(Menu.NONE, "翻译"), item(Menu.NONE, "翻译"),
            item(Menu.NONE, "询问 AlwaySide"), item(android.R.id.textAssist, "搜索"), item(103, "复制"))
        val menu = menu((basics + reading + share + translation + blocked).toMutableList())

        assertTrue(restrictTextActionMenu(menu, listOf(reading, share, translation)))
        assertTrue((basics + reading + share + translation).all { it.isVisible })
        assertTrue(blocked.none { it.isVisible })
        assertFalse(restrictTextActionMenu(menu, listOf(reading, share, translation)))
    }

    @Test fun duplicateMenuIdsDoNotHideTheChosenSystemTranslation() {
        val browser = item(Menu.NONE, "翻译")
        val system = item(Menu.NONE, "系统翻译")
        val dictionary = item(Menu.NONE, "翻译")
        val menu = menu(mutableListOf(browser, system, dictionary))

        restrictTextActionMenu(menu, listOf(system))

        assertFalse(browser.isVisible)
        assertTrue(system.isVisible)
        assertFalse(dictionary.isVisible)
        assertEquals(3, menu.size())
    }

    @Test fun refreshedMenusAreFilteredAgainWithoutEnablingUnavailableEditing() {
        val copy = item(android.R.id.copy)
        val paste = item(android.R.id.paste, visible = false)
        val reading = item(100, "点读")
        val items = mutableListOf(copy, paste, reading)
        val menu = menu(items)
        restrictTextActionMenu(menu, listOf(reading))
        val injected = item(Menu.NONE, "翻译")
        items.add(injected)

        assertTrue(restrictTextActionMenu(menu, emptyList()))
        assertTrue(copy.isVisible)
        assertFalse(paste.isVisible)
        assertFalse(reading.isVisible)
        assertFalse(injected.isVisible)
    }

    @Test fun translationProviderRequiresBothExactPackageAndSystemInstallation() {
        assertTrue(isSystemTextTranslator("com.vivo.translator", true))
        assertFalse(isSystemTextTranslator("com.vivo.translator", false))
        assertFalse(isSystemTextTranslator("com.vivo.browser", true))
        assertFalse(isSystemTextTranslator("com.vivo.translator.fake", true))
        assertFalse(isSystemTextTranslator("com.example.translator", false))
    }

    @Test fun onlyOneNativeTranslationSurvivesAlongsideSameNamedExtensions() {
        val browser = item(Menu.NONE, "翻译")
        val search = item(android.R.id.textAssist, "搜索")
        val system = item(android.R.id.textAssist, "翻译")
        val duplicate = item(android.R.id.textAssist, "Translate")
        val menu = menu(mutableListOf(browser, search, system, duplicate))
        val chosen = findNativeSystemTranslation(menu) { it.itemId == android.R.id.textAssist }

        assertSame(system, chosen)
        restrictTextActionMenu(menu, listOfNotNull(chosen))

        assertTrue(system.isVisible)
        assertFalse(browser.isVisible)
        assertFalse(search.isVisible)
        assertFalse(duplicate.isVisible)
        assertSame(system, findNativeSystemTranslation(menu) { it.itemId == android.R.id.textAssist })
    }

    private fun item(id: Int, title: String = "", visible: Boolean = true): MenuItem {
        var shown = visible
        return Proxy.newProxyInstance(MenuItem::class.java.classLoader, arrayOf(MenuItem::class.java)) { proxy, method, args ->
            when (method.name) {
                "getItemId" -> id
                "getTitle" -> title
                "getIntent" -> null
                "isVisible" -> shown
                "setVisible" -> { shown = args!![0] as Boolean; proxy }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MenuItem($id, $title)"
                else -> error("Unexpected MenuItem call: ${method.name}")
            }
        } as MenuItem
    }

    private fun menu(items: MutableList<MenuItem>): Menu =
        Proxy.newProxyInstance(Menu::class.java.classLoader, arrayOf(Menu::class.java)) { _, method, args ->
            when (method.name) {
                "size" -> items.size
                "getItem" -> items[args!![0] as Int]
                else -> error("Unexpected Menu call: ${method.name}")
            }
        } as Menu
}
