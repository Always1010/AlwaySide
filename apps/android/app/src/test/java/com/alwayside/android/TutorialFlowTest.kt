package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class TutorialFlowTest {
    @Test fun screenshotAndFollowupAreAClosedLocalScript() {
        val flow = TutorialFlow(TutorialStep.BUBBLE)
        listOf(TutorialAction.OPEN_MENU, TutorialAction.CAPTURE, TutorialAction.PRACTISE,
            TutorialAction.EXPAND, TutorialAction.FILL, TutorialAction.SEND, TutorialAction.FINISH).forEach {
            assertTrue(flow.act(it))
        }
        assertEquals(TutorialStep.COMPLETE, flow.step)
        assertFalse(flow.act(TutorialAction.SEND))
        assertTrue(flow.back());assertEquals(TutorialStep.LESSONS,flow.step)
    }

    @Test fun screenshotAloneCanCompleteWithoutSendingFollowup() {
        val flow = TutorialFlow(TutorialStep.BUBBLE)
        assertTrue(flow.act(TutorialAction.OPEN_MENU)); assertTrue(flow.act(TutorialAction.CAPTURE))
        assertTrue(flow.act(TutorialAction.FINISH)); assertEquals(TutorialStep.COMPLETE, flow.step)
        assertEquals(setOf(TutorialLesson.MENU),flow.completedLessons)
    }

    @Test fun staleClicksAndOutOfOrderActionsCannotAdvanceOrDuplicateReplies() {
        val flow = TutorialFlow(TutorialStep.BUBBLE)
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
        assertEquals(TutorialStep.LESSONS, TutorialStep.restore("unknown"))
        val flow = TutorialFlow(TutorialStep.SEND)
        assertTrue(flow.back()); assertEquals(TutorialStep.QUESTION, flow.step)
        assertTrue(flow.back()); assertEquals(TutorialStep.COMPOSER, flow.step)
        val menu = TutorialFlow(TutorialStep.MENU)
        assertTrue(menu.act(TutorialAction.CLOSE_MENU)); assertTrue(menu.back())
        assertEquals(TutorialStep.LESSONS,menu.step)
    }

    @Test fun longPressScreenshotRequiresAHoldAndOnlyMarksItsOwnLesson() {
        val flow=TutorialFlow()
        assertTrue(flow.act(TutorialAction.SELECT_SCREENSHOT))
        assertFalse(flow.tap(1));assertFalse(flow.tap(2))
        assertTrue(flow.longPress());assertEquals(TutorialStep.LONG_PRESS_RESULT,flow.step)
        assertFalse(flow.longPress());assertTrue(flow.act(TutorialAction.FINISH))
        assertEquals(setOf(TutorialLesson.SCREENSHOT),flow.completedLessons)
    }

    @Test fun recordingStartsAndStopsOnlyWithDoubleTaps() {
        val flow=TutorialFlow()
        flow.act(TutorialAction.SELECT_AUDIO)
        assertFalse(flow.tap(1));assertFalse(flow.longPress());assertFalse(flow.recording)
        assertTrue(flow.tap(2));assertTrue(flow.recording)
        assertFalse(flow.tap(1));assertFalse(flow.tap(3));assertTrue(flow.recording)
        assertTrue(flow.tap(2));assertFalse(flow.recording);assertEquals(TutorialStep.AUDIO_RESULT,flow.step)
        assertFalse(flow.tap(2));flow.act(TutorialAction.FINISH)
        assertEquals(setOf(TutorialLesson.AUDIO),flow.completedLessons)
    }

    @Test fun tripleTapCancellationDoesNotProduceAnAudioAnswer() {
        val flow=TutorialFlow()
        flow.act(TutorialAction.SELECT_CANCEL);assertTrue(flow.tap(2));assertTrue(flow.recording)
        assertFalse(flow.tap(2));assertFalse(flow.tap(1));assertTrue(flow.tap(3))
        assertEquals(TutorialStep.CANCEL_RESULT,flow.step);assertFalse(flow.recording)
        assertFalse(flow.act(TutorialAction.STOP_AUDIO))
        flow.act(TutorialAction.FINISH);assertEquals(setOf(TutorialLesson.CANCEL),flow.completedLessons)
    }

    @Test fun everyLessonCanBeSelectedAndCompletedWithoutForcingOtherLessons() {
        val flow=TutorialFlow()
        listOf(TutorialAction.SELECT_MENU,TutorialAction.SELECT_SCREENSHOT,TutorialAction.SELECT_AUDIO,TutorialAction.SELECT_CANCEL).forEach { select ->
            assertTrue(flow.act(select))
            when(select) {
                TutorialAction.SELECT_MENU -> {flow.tap(1);flow.act(TutorialAction.CAPTURE)}
                TutorialAction.SELECT_SCREENSHOT -> flow.longPress()
                TutorialAction.SELECT_AUDIO -> {flow.tap(2);flow.tap(2)}
                TutorialAction.SELECT_CANCEL -> {flow.tap(2);flow.tap(3)}
                else -> error("Unexpected lesson")
            }
            assertTrue(flow.act(TutorialAction.FINISH))
            assertTrue(flow.act(TutorialAction.LESSONS))
        }
        assertEquals(TutorialLesson.entries.toSet(),flow.completedLessons)
        val restored=TutorialFlow(TutorialStep.CANCEL_RECORDING,flow.completedLessons)
        assertTrue(restored.back());assertFalse(restored.recording)
        assertEquals(flow.completedLessons,restored.completedLessons)
    }
}
