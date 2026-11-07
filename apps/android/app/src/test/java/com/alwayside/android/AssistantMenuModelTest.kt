package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class AssistantMenuModelTest {
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
