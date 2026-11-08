package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AssistantMenuModelTest {
    @Test fun appearanceDefaultsAndResetPreserveToolsAndQuestionOptions() {
        val defaults = MenuAppearance()
        assertEquals(0, defaults.transparency)
        assertEquals(255, defaults.panelAlpha)
        assertEquals(100, defaults.sizePercent)
        assertEquals(16f, defaults.blurRadiusDp, 0f)
        assertEquals(24, defaults.cornerRadiusDp)
        assertEquals(18, defaults.effectiveDimPercent(true))
        assertEquals(32, defaults.effectiveDimPercent(false))
        val tools = listOf(AssistantMenuAction.APPEARANCE, AssistantMenuAction.AUDIO)
        assertEquals(tools, AssistantMenuAction.decode(AssistantMenuAction.encode(tools)))
        assertFalse(AssistantMenuAction.defaults.contains(AssistantMenuAction.APPEARANCE))
        val config = InteractionConfig(menuItems = tools, menuWebSearch = true, useDefaultQuestion = false,
            menuAppearance = MenuAppearance(transparency = 100, sizePercent = 30, blurStrength = 0))
        val reset = config.copy(menuAppearance = defaults)
        assertEquals(tools, reset.menuItems)
        assertEquals(true, reset.menuWebSearch)
        assertFalse(reset.useDefaultQuestion)
        assertEquals(defaults, reset.menuAppearance)
    }

    @Test fun transparentPanelsAndDisabledBlurKeepIndependentBackgroundDimming() {
        assertEquals(0, MenuAppearance(transparency = 100).panelAlpha)
        assertEquals(.3f, MenuAppearance(sizePercent = 30).scale, 0f)
        assertEquals(1.5f, MenuAppearance(sizePercent = 150).scale, 0f)
        assertEquals(32, MenuAppearance(blurStrength = 0).effectiveDimPercent(true))
        for (blur in listOf(0, 100)) for (supported in listOf(false, true)) {
            assertEquals(0, MenuAppearance(blurStrength = blur, dimPercent = 0).effectiveDimPercent(supported))
            assertEquals(100, MenuAppearance(blurStrength = blur, dimPercent = 100).effectiveDimPercent(supported))
        }
        for (size in listOf(29, 151)) {
            assertThrows(IllegalArgumentException::class.java) { MenuAppearance(sizePercent = size) }
        }
    }

    @Test fun persistedOrderAllowsRemovingEveryToolAndSurvivesUnknownOrDuplicateIds() {
        val selected = listOf(AssistantMenuAction.AUDIO, AssistantMenuAction.CROP)
        assertEquals(selected, AssistantMenuAction.decode(AssistantMenuAction.encode(selected)))
        assertEquals(emptyList<AssistantMenuAction>(), AssistantMenuAction.decode(""))
        assertEquals(selected, AssistantMenuAction.decode("AUDIO,unknown,CROP,AUDIO"))
        assertEquals(AssistantMenuAction.defaults, AssistantMenuAction.decode(null))
        val empty = InteractionConfig(menuItems = emptyList(), menuWebSearch = true, useDefaultQuestion = false)
        assertTrue(empty.menuItems.isEmpty())
        assertTrue(empty.menuWebSearch == true)
        assertFalse(empty.useDefaultQuestion)
    }

    @Test fun movingInEitherDirectionPreservesAllOtherToolsAndHiddenChoices() {
        val original = listOf(AssistantMenuAction.CROP, AssistantMenuAction.SCREEN, AssistantMenuAction.AUDIO)
        val moved = AssistantMenuAction.move(original, AssistantMenuAction.CROP, AssistantMenuAction.AUDIO)
        assertEquals(listOf(AssistantMenuAction.SCREEN, AssistantMenuAction.AUDIO, AssistantMenuAction.CROP), moved)
        assertEquals(original, AssistantMenuAction.move(moved, AssistantMenuAction.CROP, AssistantMenuAction.SCREEN))
        assertEquals(original, AssistantMenuAction.move(original, AssistantMenuAction.STOP, AssistantMenuAction.AUDIO))
        val config = InteractionConfig(menuItems = moved, menuWebSearch = false, useDefaultQuestion = false)
        val reset = config.copy(menuItems = AssistantMenuAction.defaults)
        assertFalse(reset.menuWebSearch!!)
        assertFalse(reset.useDefaultQuestion)
        assertFalse(moved.contains(AssistantMenuAction.SETTINGS))
        assertFalse(moved.contains(AssistantMenuAction.STOP))
    }
}
