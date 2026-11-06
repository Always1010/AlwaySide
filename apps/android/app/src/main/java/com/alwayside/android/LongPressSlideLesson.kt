package com.alwayside.android

/** Local practice progress only. No binding writes, capture, recording, history or API requests. */
internal class LongPressSlideLesson {
    enum class Step(val title: String, val instruction: String, val gesture: BubbleGesture, val timeout: Boolean = false) {
        RELEASE("长按后松手", "按住小球，看到提示后原地松手。", BubbleGesture.HOLD),
        UP("长按后上移", "按住到提示出现，再向上拖动；到位后立即触发。", BubbleGesture.UP),
        DOWN("长按后下移", "按住到提示出现，再向下拖动；到位后立即触发。", BubbleGesture.DOWN),
        INWARD("长按后向内移", "按住到提示出现，再向屏幕内侧拖动。", BubbleGesture.INWARD),
        TIMEOUT("持续按住", "按住小球，提示出现后继续不动，等待自动确认。", BubbleGesture.HOLD, true),
    }
    var index = 0
        private set
    var passed = false
        private set
    val complete get() = index >= Step.entries.size
    val step get() = Step.entries[index.coerceAtMost(Step.entries.lastIndex)]
    fun accept(gesture: BubbleGesture, timedOut: Boolean): Boolean {
        if (complete || gesture != step.gesture || (gesture == BubbleGesture.HOLD && timedOut != step.timeout)) return false
        passed = true
        return true
    }
    fun next() { if (passed && !complete) { index++; passed = false } }
}
