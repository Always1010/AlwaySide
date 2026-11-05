package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class TutorialFlowTest {
    @Test fun screenshotAndFollowupAreAClosedLocalScript() {
        val flow = TutorialFlow()
        listOf(TutorialAction.OPEN_MENU, TutorialAction.CAPTURE, TutorialAction.PRACTISE,
            TutorialAction.EXPAND, TutorialAction.FILL, TutorialAction.SEND, TutorialAction.FINISH).forEach {
            assertTrue(flow.act(it))
        }
        assertEquals(TutorialStep.COMPLETE, flow.step)
        assertFalse(flow.act(TutorialAction.SEND))
        assertFalse(flow.back())
    }

    @Test fun screenshotAloneCanCompleteWithoutSendingFollowup() {
        val flow = TutorialFlow()
        assertTrue(flow.act(TutorialAction.OPEN_MENU)); assertTrue(flow.act(TutorialAction.CAPTURE))
        assertTrue(flow.act(TutorialAction.FINISH)); assertEquals(TutorialStep.COMPLETE, flow.step)
    }

    @Test fun staleClicksAndOutOfOrderActionsCannotAdvanceOrDuplicateReplies() {
        val flow = TutorialFlow()
        assertFalse(flow.act(TutorialAction.SEND)); assertFalse(flow.act(TutorialAction.CAPTURE))
        flow.act(TutorialAction.OPEN_MENU)
        assertFalse(flow.act(TutorialAction.OPEN_MENU))
        flow.act(TutorialAction.CAPTURE)
        assertFalse(flow.act(TutorialAction.CAPTURE)); assertEquals(TutorialStep.ANSWER, flow.step)
        val send = TutorialFlow(TutorialStep.SEND)
        assertTrue(send.act(TutorialAction.SEND)); assertFalse(send.act(TutorialAction.SEND))
        assertEquals(TutorialStep.FOLLOWUP, send.step)
    }

    @Test fun interruptedStepsRestoreAndBackNeverTriggersAnAction() {
        for (step in TutorialStep.entries) assertEquals(step, TutorialStep.restore(step.name))
        assertEquals(TutorialStep.BUBBLE, TutorialStep.restore("unknown"))
        val flow = TutorialFlow(TutorialStep.SEND)
        assertTrue(flow.back()); assertEquals(TutorialStep.QUESTION, flow.step)
        assertTrue(flow.back()); assertEquals(TutorialStep.COMPOSER, flow.step)
        val menu = TutorialFlow(TutorialStep.MENU)
        assertTrue(menu.act(TutorialAction.CLOSE_MENU)); assertFalse(menu.back())
    }
}
