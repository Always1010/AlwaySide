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
        GestureFunction(AssistantActions.START_AUDIO, "开始录音", "声音", setOf(false)),
        GestureFunction(AssistantActions.END_AUDIO, "结束录音", "声音", setOf(true)),
        GestureFunction(AssistantActions.CANCEL_AUDIO, "丢弃本次录音", "声音", setOf(true)),
        GestureFunction(AssistantActions.MENU, "展开菜单", "助手", setOf(false, true)),
        GestureFunction(AssistantActions.LAST, "最近悬浮对话", "助手", setOf(false, true)),
        GestureFunction(AssistantActions.HISTORY, "对话记录", "助手", setOf(false, true)),
        GestureFunction(AssistantActions.SETTINGS, "设置", "助手", setOf(false, true)),
    )
    fun title(action: String) = functions.find { it.action == action }?.title ?: "不执行动作"

    fun legacy(config: InteractionConfig): List<GestureBinding> = buildList {
        for (recording in listOf(false, true)) {
            val legacy = if (recording) listOf(config.recordingSingleAction, config.recordingDoubleAction,
                config.recordingTripleAction, config.recordingLongAction)
            else listOf(config.singleAction, config.doubleAction, config.tripleAction, config.longAction)
            legacy.forEachIndexed { index, raw ->
                val action = if (raw == AssistantActions.AUDIO) {
                    if (recording) AssistantActions.END_AUDIO else AssistantActions.START_AUDIO
                } else raw
                if (action != AssistantActions.NONE) add(GestureBinding(BubbleGesture.taps(index + 1), recording, action))
            }
            add(GestureBinding(BubbleGesture.DOWN, recording, if (recording) AssistantActions.END_AUDIO else AssistantActions.START_AUDIO))
            add(GestureBinding(BubbleGesture.INWARD, recording, AssistantActions.LAST))
        }
        add(GestureBinding(BubbleGesture.UP, false, AssistantActions.CROP))
    }

    fun conflicts(bindings: List<GestureBinding>, action: String, gesture: BubbleGesture, states: Set<Boolean>) =
        bindings.filter { it.gesture == gesture && it.recording in states && it.action != action }

    fun assign(bindings: List<GestureBinding>, action: String, gesture: BubbleGesture, states: Set<Boolean>): List<GestureBinding> {
        require(functions.any { it.action == action } && states.isNotEmpty())
        return bindings.filterNot { it.gesture == gesture && it.recording in states } +
            states.map { GestureBinding(gesture, it, action) }
    }

    fun valid(bindings: List<GestureBinding>) = bindings.size <= BubbleGesture.entries.size * 2 &&
        bindings.map { it.gesture to it.recording }.distinct().size == bindings.size &&
        bindings.all { binding -> functions.any { it.action == binding.action } }
}
