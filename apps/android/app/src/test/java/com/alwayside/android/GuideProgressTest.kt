package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class GuideProgressTest {
    @Test fun onlyNewInstallsAndInterruptedTutorialsInviteOnHome() {
        assertTrue(GuideProgress.initial(false).showOnHome)
        assertFalse(GuideProgress.initial(true).showOnHome)
        assertFalse(GuideProgress().skip().showOnHome)
        assertTrue(GuideProgress().start().advance(TutorialStep.QUESTION).showOnHome)
    }

    @Test fun reentryContinuesInterruptedProgressAndReplayStartsClean() {
        val interrupted = GuideProgress().start().advance(TutorialStep.SEND)
        val restored = GuideProgress.restore(interrupted.status.name, interrupted.step.name)
        assertEquals(TutorialStep.SEND, restored.start().step)
        val completed = restored.advance(TutorialStep.COMPLETE)
        assertFalse(completed.showOnHome)
        assertEquals(TutorialStep.BUBBLE, completed.start().step)
        assertEquals(TutorialStep.BUBBLE, completed.skip().start().step)
    }

    @Test fun unknownStoredStepsRecoverToTheFirstSafeLocalStep() {
        assertEquals(TutorialStep.BUBBLE, GuideProgress.restore("IN_PROGRESS", "removed_step").step)
        assertEquals(GuideStatus.NEW, GuideProgress.restore("unknown", null).status)
    }
}
