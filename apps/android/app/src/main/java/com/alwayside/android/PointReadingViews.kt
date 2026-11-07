package com.alwayside.android

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

internal fun android.widget.TextView.enableChatReading(owner: String) {
    enableTextActions(read = if (InteractionPreferences(context).load().chatPointReading) ({ selected ->
        PointReadingController.get(context).read(owner, ReadingOrigin.CHAT, selected)
    }) else null)
}

/** Only inserted into enabled experiments. Native rendered spans are preserved in each block. */
@android.annotation.SuppressLint("ViewConstructor") // Requires an owning conversation; never inflated from XML.
internal class ReadingBlocksView(context: Context, private val owner: String) : LinearLayout(context) {
    private val controller = PointReadingController.get(context)
    private val playback = ChatAudioPlayback.get(context)
    private val buttons = mutableListOf<Pair<String, MaterialButton>>()
    private var signature = ""
    private val observer: () -> Unit = { updateButtons() }
    init { orientation = VERTICAL; setPadding(0, 0, 0, 0) }
    fun content(rendered: CharSequence, complete: Boolean) {
        removeAllViews(); buttons.clear()
        var offset = 0
        ReadingParagraphs.split(rendered.toString()).forEach { block ->
            val slice = rendered.subSequence(offset, offset + block.text.length)
            offset += block.text.length + 1
            val line = LinearLayout(context).apply { gravity = Gravity.TOP }
            val text = context.label("", 13f).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                includeFontPadding = false; setLineSpacing(context.dp(4).toFloat(), 1f)
                this.text = slice
                enableTextActions(read = { read(it) })
            }
            line.addView(text, LayoutParams(0, -2, 1f))
            if (complete && block.english && block.text.length <= 3500) {
                val button = context.iconButton(R.drawable.ic_line_audio, "朗读：${block.text.take(70)}") { read(block.text) }
                buttons.add(block.text to button)
                line.addView(button)
            }
            addView(line, LayoutParams(-1, -2))
        }
        signature = ""; updateButtons()
    }
    private fun read(text: String) {
        if (InteractionPreferences(context).load().chatPointReading)
            controller.read(owner, ReadingOrigin.CHAT, text)
    }
    private fun updateButtons() {
        val state = controller.state
        val next = "${state.owner}:${state.text}:${state.generating}:${playback.currentState.playing}:${playback.readingKey}"
        if (signature == next) return
        signature = next
        buttons.forEach { (text, button) ->
            val selected = state.owner == owner && state.origin == ReadingOrigin.CHAT && state.text == normalizeReadingText(text)
            val playing = selected && playback.readingKey == state.key && playback.currentState.playing
            button.icon = context.prototypeIcon(if (playing) R.drawable.ic_line_pause else R.drawable.ic_line_audio)
            button.alpha = if (selected && state.generating) 0.45f else 1f
            button.accessible(when {
                selected && state.generating -> "正在生成：${text.take(70)}"
                playing -> "暂停朗读：${text.take(70)}"
                else -> "朗读：${text.take(70)}"
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.addObserver(observer); playback.addObserver(observer) }
    override fun onDetachedFromWindow() { controller.removeObserver(observer); playback.removeObserver(observer); super.onDetachedFromWindow() }
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
