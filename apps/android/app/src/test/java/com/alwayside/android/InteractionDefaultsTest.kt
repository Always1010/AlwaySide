package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class InteractionDefaultsTest {
    @Test fun upgradesOldIdleDefaultWithoutChangingAppearanceOrRecordingControls() {
        val old = InteractionConfig(singleAction=AssistantActions.SCREEN,longAction=AssistantActions.MENU,
            ballSize=37,ballOpacity=80,saveHistoryImages=true,recordingLongAction=AssistantActions.CANCEL_AUDIO,
            collapseFollowUpByDefault=false)
        val updated = InteractionDefaults.upgrade(old)
        assertEquals(AssistantActions.MENU,updated.action(1,false))
        assertEquals(AssistantActions.SCREEN,updated.action(4,false))
        assertEquals(old.copy(singleAction=AssistantActions.MENU,longAction=AssistantActions.SCREEN),updated)
        assertEquals(AssistantActions.MENU,updated.action(1,true))
        assertEquals(AssistantActions.CANCEL_AUDIO,updated.action(4,true))
    }
    @Test fun preservesAnyCustomizedIdleScheme() {
        val customized = InteractionConfig(singleAction=AssistantActions.SCREEN,longAction=AssistantActions.MENU,
            doubleAction=AssistantActions.LAST)
        assertEquals(customized,InteractionDefaults.upgrade(customized))
        val customTriple = customized.copy(doubleAction=AssistantActions.AUDIO,tripleAction=AssistantActions.SETTINGS)
        assertEquals(customTriple,InteractionDefaults.upgrade(customTriple))
    }
    @Test fun newDefaultsAreStableAndKeepRecordingCancellation() {
        val defaults=InteractionConfig()
        assertEquals(defaults,InteractionDefaults.upgrade(defaults))
        assertEquals(AssistantActions.MENU,defaults.action(1,false))
        assertEquals(AssistantActions.SCREEN,defaults.action(4,false))
        assertEquals(AssistantActions.MENU,defaults.action(1,true))
        assertEquals(AssistantActions.SCREEN,defaults.action(4,true))
        assertEquals(AssistantActions.CANCEL_AUDIO,defaults.action(3,true))
    }
}
