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
        val completed = restored.advance(TutorialStep.COMPLETE,TutorialLesson.entries.toSet())
        assertFalse(completed.showOnHome)
        assertEquals(TutorialStep.LESSONS, completed.start().step)
        assertEquals(TutorialStep.LESSONS, completed.skip().start().step)
        assertEquals(TutorialLesson.entries.toSet(),completed.start().completedLessons)
    }

    @Test fun unknownStoredStepsRecoverToTheFirstSafeLocalStep() {
        assertEquals(TutorialStep.LESSONS, GuideProgress.restore("IN_PROGRESS", "removed_step").step)
        assertEquals(GuideStatus.NEW, GuideProgress.restore("unknown", null).status)
    }
    @Test fun previousScreenshotOnlyCompletionDoesNotClaimNewGestureLessonsAreDone() {
        val migrated=GuideProgress.restore("COMPLETED","COMPLETE")
        assertEquals(setOf(TutorialLesson.MENU),migrated.completedLessons)
        assertEquals(TutorialStep.LESSONS,migrated.start().step)
        assertTrue(migrated.showOnHome)
        val partial=migrated.advance(TutorialStep.COMPLETE,setOf(TutorialLesson.MENU,TutorialLesson.AUDIO))
        assertTrue(partial.showOnHome)
        val restored=GuideProgress.restore(partial.status.name,partial.step.name,partial.completedLessons.map { it.name }.toSet())
        assertEquals(partial.completedLessons,restored.start().completedLessons)
        assertEquals(partial.completedLessons,partial.skip().completedLessons)
    }
}
