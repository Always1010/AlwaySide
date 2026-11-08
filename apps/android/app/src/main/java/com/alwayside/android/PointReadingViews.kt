package com.alwayside.android

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

internal fun android.widget.TextView.enableChatReading(owner: String, complete: Boolean = true,
    block: String = "source", overlay: Boolean = false) {
    val binding = (getTag(R.id.point_reading_binding) as? ChatReadingBinding)
        ?: ChatReadingBinding(this).also { setTag(R.id.point_reading_binding, it) }
    binding.bind(owner, block, complete, overlay)
    // Check the preference when opening the selection menu, so toggling never replaces message views.
    enableTextActions(read = if (complete) ({ selected ->
        binding.readSelection(selected)
    }) else null)
    // LinkMovementMethod cannot select arbitrary ranges. Links are handled as short taps by the binding.
    movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
}

@android.annotation.SuppressLint("ViewConstructor") // Requires an owning conversation and experiment.
internal class ReadingBar(context: Context, private val owner: String, private val origin: ReadingOrigin) : LinearLayout(context) {
    private val controller = PointReadingController.get(context)
    private val playback = ChatAudioPlayback.get(context)
    private val preview = context.label("", 11f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val replay = context.iconButton(R.drawable.ic_line_retry, "从头重播") { controller.replay() }
    private val toggle = context.iconButton(R.drawable.ic_line_pause, "暂停朗读") { playback.toggle() }
    private val speed = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = "1.0×"; context.let { applyActionStyle() }; minWidth = 0; minimumWidth = 0
        setPadding(context.dp(6), 0, context.dp(6), 0)
        setOnClickListener {
            playback.setReadingSpeed(when (playback.speed) { 0.8f -> 1f; 1f -> 1.2f; else -> 0.8f })
        }
        accessible("切换朗读速度")
    }
    private val observer: () -> Unit = { update() }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        addView(preview, LayoutParams(0, -2, 1f)); addView(replay); addView(toggle)
        addView(speed, LayoutParams(context.dp(48), context.dp(48)))
        addView(context.iconButton(R.drawable.ic_line_close, "关闭点读") { controller.stop() })
        preview.setOnClickListener { preview.maxLines = if (preview.maxLines == 1) 5 else 1 }
    }
    private fun update() {
        val state = controller.state
        val enabled = InteractionPreferences(context).load().readingEnabled(origin)
        visibility = if (enabled && state.owner == owner && state.origin == origin && state.text.isNotBlank()) VISIBLE else GONE
        if (visibility == GONE) return
        preview.text = if (state.generating) "正在生成… ${state.text}" else state.text
        preview.accessible(state.text + when { state.generating -> "，正在生成"; state.error.isNotBlank() -> "，${state.error}"; state.saved -> "，已保存在本机"; else -> "，本次未保存" })
        replay.isEnabled = !state.generating
        val active = playback.readingKey == state.key && playback.isActive(owner)
        toggle.isEnabled = active && !playback.currentState.preparing
        toggle.icon = context.prototypeIcon(if (playback.currentState.playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
        toggle.accessible(if (playback.currentState.playing) "暂停朗读" else "继续朗读")
        speed.isEnabled = active
        speed.text = when (playback.speed) { 0.8f -> "0.8×"; 1.2f -> "1.2×"; else -> "1.0×" }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.addObserver(observer); playback.addObserver(observer) }
    override fun onDetachedFromWindow() { controller.removeObserver(observer); playback.removeObserver(observer); super.onDetachedFromWindow() }
}
