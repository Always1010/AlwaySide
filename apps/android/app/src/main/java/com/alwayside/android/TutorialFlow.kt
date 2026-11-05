package com.alwayside.android

/** A closed, local script. There is no provider, configuration, capture or persistence dependency. */
internal enum class TutorialStep {
    BUBBLE, MENU, ANSWER, COMPOSER, QUESTION, SEND, FOLLOWUP, COMPLETE,
    LESSONS, LONG_PRESS, LONG_PRESS_RESULT, AUDIO_START, AUDIO_RECORDING, AUDIO_RESULT,
    CANCEL_START, CANCEL_RECORDING, CANCEL_RESULT;

    companion object {
        fun restore(value: String?): TutorialStep = entries.firstOrNull { it.name == value } ?: LESSONS
    }
}

internal enum class TutorialLesson { MENU, SCREENSHOT, AUDIO, CANCEL }
internal enum class TutorialAction {
    OPEN_MENU, CLOSE_MENU, CAPTURE, PRACTISE, EXPAND, FILL, SEND, FINISH,
    LESSONS, SELECT_MENU, SELECT_SCREENSHOT, SELECT_AUDIO, SELECT_CANCEL, START_AUDIO, STOP_AUDIO, CANCEL_AUDIO,
}

internal class TutorialFlow(initial: TutorialStep = TutorialStep.LESSONS, completed: Set<TutorialLesson> = emptySet()) {
    var step = initial
        private set
    var completedLessons = completed.toSet()
        private set
    val recording: Boolean get() = step == TutorialStep.AUDIO_RECORDING || step == TutorialStep.CANCEL_RECORDING

    /** Same default mapping as the service, but effects only advance this local script. */
    fun tap(count: Int): Boolean = when {
        step==TutorialStep.BUBBLE && count==1 -> act(TutorialAction.OPEN_MENU)
        step==TutorialStep.MENU && count==1 -> act(TutorialAction.CLOSE_MENU)
        (step==TutorialStep.AUDIO_START || step==TutorialStep.CANCEL_START) && count==2 -> act(TutorialAction.START_AUDIO)
        step==TutorialStep.AUDIO_RECORDING && count==2 -> act(TutorialAction.STOP_AUDIO)
        step==TutorialStep.CANCEL_RECORDING && count==3 -> act(TutorialAction.CANCEL_AUDIO)
        else -> false
    }
    fun longPress(): Boolean = step==TutorialStep.LONG_PRESS && act(TutorialAction.CAPTURE)

    fun act(action: TutorialAction): Boolean {
        if(action==TutorialAction.LESSONS) {
            if(step==TutorialStep.LESSONS)return false
            step=TutorialStep.LESSONS;return true
        }
        val next = when (step) {
            TutorialStep.LESSONS -> when(action) {
                TutorialAction.SELECT_MENU -> TutorialStep.BUBBLE
                TutorialAction.SELECT_SCREENSHOT -> TutorialStep.LONG_PRESS
                TutorialAction.SELECT_AUDIO -> TutorialStep.AUDIO_START
                TutorialAction.SELECT_CANCEL -> TutorialStep.CANCEL_START
                else -> null
            }
            TutorialStep.BUBBLE -> if (action == TutorialAction.OPEN_MENU) TutorialStep.MENU else null
            TutorialStep.MENU -> when (action) {
                TutorialAction.CAPTURE -> TutorialStep.ANSWER
                TutorialAction.CLOSE_MENU -> TutorialStep.BUBBLE
                else -> null
            }
            TutorialStep.ANSWER -> when (action) {
                TutorialAction.PRACTISE -> TutorialStep.COMPOSER
                TutorialAction.FINISH -> TutorialStep.COMPLETE
                else -> null
            }
            TutorialStep.COMPOSER -> if (action == TutorialAction.EXPAND) TutorialStep.QUESTION else null
            TutorialStep.QUESTION -> if (action == TutorialAction.FILL) TutorialStep.SEND else null
            TutorialStep.SEND -> if (action == TutorialAction.SEND) TutorialStep.FOLLOWUP else null
            TutorialStep.FOLLOWUP -> if (action == TutorialAction.FINISH) TutorialStep.COMPLETE else null
            TutorialStep.COMPLETE -> null
            TutorialStep.LONG_PRESS -> if(action==TutorialAction.CAPTURE)TutorialStep.LONG_PRESS_RESULT else null
            TutorialStep.LONG_PRESS_RESULT, TutorialStep.AUDIO_RESULT, TutorialStep.CANCEL_RESULT ->
                if(action==TutorialAction.FINISH)TutorialStep.COMPLETE else null
            TutorialStep.AUDIO_START -> if(action==TutorialAction.START_AUDIO)TutorialStep.AUDIO_RECORDING else null
            TutorialStep.AUDIO_RECORDING -> if(action==TutorialAction.STOP_AUDIO)TutorialStep.AUDIO_RESULT else null
            TutorialStep.CANCEL_START -> if(action==TutorialAction.START_AUDIO)TutorialStep.CANCEL_RECORDING else null
            TutorialStep.CANCEL_RECORDING -> if(action==TutorialAction.CANCEL_AUDIO)TutorialStep.CANCEL_RESULT else null
        } ?: return false
        if(next==TutorialStep.COMPLETE) {
            completedLessons=completedLessons+when(step) {
                TutorialStep.LONG_PRESS_RESULT -> TutorialLesson.SCREENSHOT
                TutorialStep.AUDIO_RESULT -> TutorialLesson.AUDIO
                TutorialStep.CANCEL_RESULT -> TutorialLesson.CANCEL
                else -> TutorialLesson.MENU
            }
        }
        step = next
        return true
    }

    fun back(): Boolean {
        step = when (step) {
            TutorialStep.LESSONS -> return false
            TutorialStep.BUBBLE, TutorialStep.COMPLETE, TutorialStep.LONG_PRESS, TutorialStep.AUDIO_START, TutorialStep.CANCEL_START -> TutorialStep.LESSONS
            TutorialStep.MENU -> TutorialStep.BUBBLE
            TutorialStep.ANSWER -> TutorialStep.MENU
            TutorialStep.COMPOSER -> TutorialStep.ANSWER
            TutorialStep.QUESTION -> TutorialStep.COMPOSER
            TutorialStep.SEND -> TutorialStep.QUESTION
            TutorialStep.FOLLOWUP -> TutorialStep.SEND
            TutorialStep.LONG_PRESS_RESULT -> TutorialStep.LONG_PRESS
            TutorialStep.AUDIO_RECORDING, TutorialStep.AUDIO_RESULT -> TutorialStep.AUDIO_START
            TutorialStep.CANCEL_RECORDING, TutorialStep.CANCEL_RESULT -> TutorialStep.CANCEL_START
        }
        return true
    }

    companion object {
        const val PASSAGE = "Small steps every day lead to big changes."
        const val QUESTION = "请把图中的英文翻译成中文。"
        const val ANSWER = "每天迈出一小步，终会带来大改变。"
        const val FOLLOWUP_QUESTION = "能解释得更简单一点吗？"
        const val FOLLOWUP_ANSWER = "意思是：每天做一点点，慢慢积累，就能看到明显的进步。"
        const val AUDIO_TRANSCRIPT = "Let's take a short break and continue in ten minutes."
        const val AUDIO_ANSWER = "我们先休息一下，十分钟后继续。"
    }
}
