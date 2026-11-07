package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class InteractionDefaultsTest {
    @Test fun readingExperimentsAreIndependentForAllFourCombinations() {
        for (chat in listOf(false,true)) for (image in listOf(false,true)) {
            val value=InteractionConfig(chatPointReading=chat,imagePointReading=image)
            assertEquals(chat,value.readingEnabled(ReadingOrigin.CHAT))
            assertEquals(image,value.readingEnabled(ReadingOrigin.IMAGE))
            assertEquals(InteractionConfig().resolvedBindings(),value.resolvedBindings())
        }
    }
    @Test fun factoryBindingsUseOnlyTheAgreedFourBasicGestures() {
        val config = InteractionConfig()
        assertFalse(config.longPressSlide)
        assertFalse(config.chatPointReading)
        assertFalse(config.imagePointReading)
        assertFalse(config.readingEnabled(ReadingOrigin.CHAT))
        assertTrue(config.copy(imagePointReading = true).readingEnabled(ReadingOrigin.IMAGE))
        assertFalse(config.copy(imagePointReading = true).readingEnabled(ReadingOrigin.CHAT))
        assertEquals(600, config.longPressMillis)
        assertEquals(24, config.slideDistanceDp)
        assertEquals(32, config.actionCircleSizeDp)
        assertEquals(AssistantActions.MENU, config.action(1, false))
        assertEquals(AssistantActions.MENU, config.action(1, true))
        assertEquals(AssistantActions.START_AUDIO, config.action(2, false))
        assertEquals(AssistantActions.END_AUDIO, config.action(2, true))
        assertEquals(AssistantActions.CROP, config.action(3, false))
        assertEquals(AssistantActions.CANCEL_AUDIO, config.action(3, true))
        assertEquals(AssistantActions.SCREEN, config.action(4, false))
        assertEquals(AssistantActions.NONE, config.action(4, true))
        assertTrue(GestureBindings.valid(config.resolvedBindings()))
        assertFalse(config.resolvedBindings().any { it.gesture.directional })
        for (gesture in BubbleGesture.entries.filter { it.directional }) for (recording in listOf(false, true))
            assertEquals(AssistantActions.NONE, config.copy(longPressSlide = true).gestureAction(gesture, recording))
    }

    @Test fun menuExtrasCannotBeBoundAndInapplicableBindingsAreInvalid() {
        for (action in listOf(AssistantActions.LAST, AssistantActions.SETTINGS, AssistantActions.HISTORY)) {
            assertFalse(GestureBindings.functions.any { it.action == action })
            assertFalse(GestureBindings.valid(listOf(GestureBinding(BubbleGesture.HOLD, false, action))))
        }
        assertFalse(GestureBindings.valid(listOf(GestureBinding(BubbleGesture.HOLD, true, AssistantActions.SCREEN))))
        assertFalse(GestureBindings.valid(listOf(GestureBinding(BubbleGesture.SINGLE, false, AssistantActions.MENU))))
    }
}
