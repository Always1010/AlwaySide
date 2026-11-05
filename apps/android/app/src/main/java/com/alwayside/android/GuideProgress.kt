package com.alwayside.android

internal enum class GuideStatus { NEW, AVAILABLE, IN_PROGRESS, SKIPPED, COMPLETED }

/** Tutorial completion is independent of real credentials, permissions and service availability. */
internal data class GuideProgress(val status: GuideStatus = GuideStatus.NEW, val step: TutorialStep = TutorialStep.LESSONS,
    val completedLessons: Set<TutorialLesson> = emptySet()) {
    val showOnHome: Boolean get() = status == GuideStatus.NEW || status == GuideStatus.IN_PROGRESS

    fun start(): GuideProgress = GuideProgress(GuideStatus.IN_PROGRESS,
        if (status == GuideStatus.IN_PROGRESS && step != TutorialStep.COMPLETE) step else TutorialStep.LESSONS,completedLessons)

    fun advance(next: TutorialStep,completed: Set<TutorialLesson> = completedLessons): GuideProgress = GuideProgress(
        if (completed.size == TutorialLesson.entries.size) GuideStatus.COMPLETED else GuideStatus.IN_PROGRESS, next,completed)

    fun skip(): GuideProgress = GuideProgress(GuideStatus.SKIPPED,completedLessons=completedLessons)

    companion object {
        fun initial(existingUser: Boolean) = GuideProgress(if (existingUser) GuideStatus.AVAILABLE else GuideStatus.NEW)
        fun restore(status: String?, step: String?, completed: Set<String>? = null): GuideProgress {
            val lessons=completed?.mapNotNull { value -> TutorialLesson.entries.firstOrNull { it.name==value } }?.toSet()
                ?: if(status=="COMPLETED")setOf(TutorialLesson.MENU) else emptySet()
            val saved=GuideStatus.entries.firstOrNull { it.name == status } ?: GuideStatus.NEW
            val upgraded=saved==GuideStatus.COMPLETED && lessons.size<TutorialLesson.entries.size
            return GuideProgress(if(upgraded)GuideStatus.IN_PROGRESS else saved,
                if(upgraded)TutorialStep.LESSONS else TutorialStep.restore(step),lessons)
        }
    }
}
