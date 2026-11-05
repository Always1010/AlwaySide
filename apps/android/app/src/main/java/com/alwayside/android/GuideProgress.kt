package com.alwayside.android

internal enum class GuideStatus { NEW, AVAILABLE, IN_PROGRESS, SKIPPED, COMPLETED }

/** Tutorial completion is independent of real credentials, permissions and service availability. */
internal data class GuideProgress(val status: GuideStatus = GuideStatus.NEW, val step: TutorialStep = TutorialStep.BUBBLE) {
    val showOnHome: Boolean get() = status == GuideStatus.NEW || status == GuideStatus.IN_PROGRESS

    fun start(): GuideProgress = GuideProgress(GuideStatus.IN_PROGRESS,
        if (status == GuideStatus.IN_PROGRESS) step else TutorialStep.BUBBLE)

    fun advance(next: TutorialStep): GuideProgress = GuideProgress(
        if (next == TutorialStep.COMPLETE) GuideStatus.COMPLETED else GuideStatus.IN_PROGRESS, next)

    fun skip(): GuideProgress = GuideProgress(GuideStatus.SKIPPED)

    companion object {
        fun initial(existingUser: Boolean) = GuideProgress(if (existingUser) GuideStatus.AVAILABLE else GuideStatus.NEW)
        fun restore(status: String?, step: String?) = GuideProgress(
            GuideStatus.entries.firstOrNull { it.name == status } ?: GuideStatus.NEW, TutorialStep.restore(step))
    }
}
