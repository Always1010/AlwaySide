package com.alwayside.android

internal enum class BubbleGesture(val title: String, val directional: Boolean = false) {
    SINGLE("单击"), DOUBLE("双击"), TRIPLE("三击"), HOLD("长按"),
    UP("长按后上移", true), DOWN("长按后下移", true), INWARD("长按后向内移", true);

    companion object {
        fun taps(count: Int) = when (count) { 1 -> SINGLE; 2 -> DOUBLE; 3 -> TRIPLE; else -> HOLD }
    }
}

/** A gesture has exactly one owner in each state; a function can own several gestures. */
internal data class GestureBinding(val gesture: BubbleGesture, val recording: Boolean, val action: String)

internal data class GestureFunction(val action: String, val title: String, val group: String, val states: Set<Boolean>)

internal object GestureBindings {
    val functions = listOf(
        GestureFunction(AssistantActions.SCREEN, "截图问答", "屏幕", setOf(false)),
        GestureFunction(AssistantActions.CROP, "框选问答", "屏幕", setOf(false)),
        GestureFunction(AssistantActions.START_AUDIO, "开始录音", "录音", setOf(false)),
        GestureFunction(AssistantActions.END_AUDIO, "结束录音", "录音", setOf(true)),
        GestureFunction(AssistantActions.CANCEL_AUDIO, "取消并丢弃录音", "录音", setOf(true)),
        GestureFunction(AssistantActions.MENU, "展开菜单", "基本操作", setOf(false, true)),
    )
    fun title(action: String) = functions.find { it.action == action }?.title ?: "不执行动作"

    fun defaults(): List<GestureBinding> = listOf(
        GestureBinding(BubbleGesture.SINGLE, false, AssistantActions.MENU),
        GestureBinding(BubbleGesture.SINGLE, true, AssistantActions.MENU),
        GestureBinding(BubbleGesture.DOUBLE, false, AssistantActions.START_AUDIO),
        GestureBinding(BubbleGesture.DOUBLE, true, AssistantActions.END_AUDIO),
        GestureBinding(BubbleGesture.TRIPLE, false, AssistantActions.CROP),
        GestureBinding(BubbleGesture.TRIPLE, true, AssistantActions.CANCEL_AUDIO),
        GestureBinding(BubbleGesture.HOLD, false, AssistantActions.SCREEN),
    )

    fun visibleGestures(slideEnabled: Boolean) = BubbleGesture.entries.filter { slideEnabled || !it.directional }

    fun conflicts(bindings: List<GestureBinding>, action: String, gesture: BubbleGesture, states: Set<Boolean>) =
        bindings.filter { it.gesture == gesture && it.recording in states && it.action != action }

    fun assign(bindings: List<GestureBinding>, action: String, gesture: BubbleGesture, states: Set<Boolean>): List<GestureBinding> {
        val function = functions.first { it.action == action }
        require(states == function.states)
        require(conflicts(bindings, action, gesture, states).isEmpty()) { "手势已被其他功能占用" }
        return bindings.filterNot { it.gesture == gesture && it.action == action } +
            states.map { GestureBinding(gesture, it, action) }
    }

    /** Apply only changed checkboxes. Invisible gestures and untouched bindings are preserved. */
    fun updateSelection(bindings: List<GestureBinding>, action: String, visibleGestures: Set<BubbleGesture>,
        selected: Set<BubbleGesture>): List<GestureBinding> {
        require(valid(bindings) && selected.all { it in visibleGestures })
        val function = functions.first { it.action == action }
        val owned = bindings.filter { it.action == action }.map { it.gesture }.toSet()
        var result = bindings.filterNot { it.action == action && it.gesture in visibleGestures && it.gesture !in selected }
        for (gesture in selected - owned) result = assign(result, action, gesture, function.states)
        return result
    }

    fun valid(bindings: List<GestureBinding>) = bindings.size <= BubbleGesture.entries.size * 2 &&
        bindings.map { it.gesture to it.recording }.distinct().size == bindings.size &&
        bindings.all { binding -> functions.any { it.action == binding.action && binding.recording in it.states } } &&
        bindings.groupBy { it.action to it.gesture }.all { (key, values) ->
            values.map { it.recording }.toSet() == functions.first { it.action == key.first }.states
        }
}
