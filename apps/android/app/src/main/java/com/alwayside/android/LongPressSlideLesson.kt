package com.alwayside.android

/** Local practice progress only. No binding writes, capture, recording, history or API requests. */
internal class LongPressSlideLesson {
    enum class Step(val title: String, val instruction: String, val gesture: BubbleGesture?) {
        RELEASE("长按后松手", "按住小球，看到提示后原地松手。", BubbleGesture.HOLD),
        UP("向上选择后松手", "按住到圆圈展开，向上轻滑，选中后松手。", BubbleGesture.UP),
        DOWN("向下选择后松手", "按住到圆圈展开，向下轻滑，选中后松手。", BubbleGesture.DOWN),
        INWARD("向内选择后松手", "按住到圆圈展开，向屏幕内侧轻滑，选中后松手。", BubbleGesture.INWARD),
        CANCEL("回中取消", "先长按并滑出选中，再拉回小球，看到取消后松手。", null),
    }
    var index = 0
        private set
    var passed = false
        private set
    val complete get() = index >= Step.entries.size
    val step get() = Step.entries[index.coerceAtMost(Step.entries.lastIndex)]
    fun accept(gesture: BubbleGesture?): Boolean {
        if (complete || gesture != step.gesture) return false
        passed = true
        return true
    }
    fun next() { if (passed && !complete) { index++; passed = false } }
}
