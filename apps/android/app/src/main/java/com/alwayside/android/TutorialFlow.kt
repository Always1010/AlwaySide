package com.alwayside.android

/** A closed, local script. There is no provider, configuration, capture or persistence dependency. */
internal enum class TutorialStep {
    BUBBLE, MENU, ANSWER, COMPOSER, QUESTION, SEND, FOLLOWUP, COMPLETE;

    companion object {
        fun restore(value: String?): TutorialStep = entries.firstOrNull { it.name == value } ?: BUBBLE
    }
}

internal enum class TutorialAction { OPEN_MENU, CLOSE_MENU, CAPTURE, PRACTISE, EXPAND, FILL, SEND, FINISH }

internal class TutorialFlow(initial: TutorialStep = TutorialStep.BUBBLE) {
    var step = initial
        private set

    fun act(action: TutorialAction): Boolean {
        val next = when (step) {
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
        } ?: return false
        step = next
        return true
    }

    fun back(): Boolean {
        step = when (step) {
            TutorialStep.BUBBLE, TutorialStep.COMPLETE -> return false
            TutorialStep.MENU -> TutorialStep.BUBBLE
            TutorialStep.ANSWER -> TutorialStep.MENU
            TutorialStep.COMPOSER -> TutorialStep.ANSWER
            TutorialStep.QUESTION -> TutorialStep.COMPOSER
            TutorialStep.SEND -> TutorialStep.QUESTION
            TutorialStep.FOLLOWUP -> TutorialStep.SEND
        }
        return true
    }

    companion object {
        const val PASSAGE = "Small steps every day lead to big changes."
        const val QUESTION = "请把图中的英文翻译成中文。"
        const val ANSWER = "每天迈出一小步，终会带来大改变。"
        const val FOLLOWUP_QUESTION = "能解释得更简单一点吗？"
        const val FOLLOWUP_ANSWER = "意思是：每天做一点点，慢慢积累，就能看到明显的进步。"
    }
}
