package com.alwayside.android

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.URLSpan
import android.text.style.UpdateAppearance
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

/** Decorations and short taps are independent of TextView's native selection movement method. */
internal class ChatReadingBinding(private val view: TextView) : View.OnAttachStateChangeListener {
    private val context get() = view.context
    private val preferences = InteractionPreferences(context)
    private var readingEnabled = preferences.load().chatPointReading
    private var controller: PointReadingController? = null
    private var playback: ChatAudioPlayback? = null
    private var owner = ""
    private var block = ""
    private var complete = false
    private var overlay = false
    private var observing = false
    private var attached = false
    private var recordsButton: MaterialButton? = null
    private var dialog: AlertDialog? = null
    private var marks = emptyList<ReadingMark>()
    private var ranges = emptyList<Pair<ReadingMark, IntRange>>()
    private var available = emptySet<String>()
    private var decoration = ""
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var selecting = false
    private val changed: () -> Unit = { refresh() }
    private val playing: () -> Unit = { decorate() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "chatPointReading") {
            readingEnabled = preferences.load().chatPointReading
            if (!readingEnabled) dialog?.dismiss()
            observe(); refresh()
        }
    }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { updateRecordsVisibility() }
    private class Tint(private val color: Int) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(paint: TextPaint) { paint.bgColor = color }
    }
    private val lines = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = context.resources.displayMetrics.density
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 2, strokeWidth * 2), 0f)
        }
        private val path = Path()
        private val rectangle = RectF()
        override fun draw(canvas: Canvas) {
            val layout = view.layout ?: return
            if (!enabled() || selectionActive()) return
            val saved = canvas.save()
            canvas.clipRect(view.compoundPaddingLeft, view.extendedPaddingTop,
                view.width - view.compoundPaddingRight, view.height - view.extendedPaddingBottom)
            canvas.translate((view.compoundPaddingLeft - view.scrollX).toFloat(), (view.extendedPaddingTop - view.scrollY).toFloat())
            ranges.forEach { (mark, range) ->
                paint.color = if (mark.key in available) ACCENT else MUTED
                for (line in layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last)) {
                    val start = maxOf(range.first, layout.getLineStart(line))
                    val end = minOf(range.last + 1, layout.getLineVisibleEnd(line))
                    if (start >= end) continue
                    path.reset(); layout.getSelectionPath(start, end, path); path.computeBounds(rectangle, true)
                    val y = minOf(layout.getLineBaseline(line) + view.paint.descent() + paint.strokeWidth, layout.getLineBottom(line).toFloat() - paint.strokeWidth)
                    canvas.drawLine(rectangle.left, y, rectangle.right, y, paint)
                }
            }
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    init {
        view.addOnAttachStateChangeListener(this)
        installTouchHandler()
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            lines.setBounds(0, 0, view.width, view.height); lines.invalidateSelf()
        }
    }

    // This decorates a stock TextView; its native performClick and selection remain in place.
    // Every replay is also reachable through the separate, labelled records/play buttons.
    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchHandler() {
        view.setOnTouchListener { _, event ->
            val handled = touch(event)
            if (handled && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            handled
        }
    }

    fun bind(owner: String, block: String, complete: Boolean, overlay: Boolean) {
        val changed = this.owner != owner || this.block != block || this.complete != complete || this.overlay != overlay
        this.owner = owner; this.block = block; this.complete = complete; this.overlay = overlay
        if (changed) dialog?.dismiss()
        if (view.isAttachedToWindow && !attached) onViewAttachedToWindow(view)
        else if (view.isAttachedToWindow) observe()
        refresh()
    }

    private fun enabled() = complete && readingEnabled
    private fun selectionActive() = view.getTag(R.id.point_reading_selection) == true
    private fun observe() {
        if (!observing && view.isAttachedToWindow && enabled()) {
            observing = true
            controller = PointReadingController.get(context)
            playback = ChatAudioPlayback.get(context)
            controller?.addObserver(changed); playback?.addObserver(playing)
        }
    }

    private fun refresh() {
        installRecordsButton()
        val store = controller?.cache
        marks = if (enabled() && store != null) runCatching { store.marks.list(owner, block) }.getOrElse {
            recordsButton?.accessible("点读记录暂时无法读取")
            emptyList()
        } else emptyList()
        val displayed = view.text.toString()
        val digest = if (marks.isEmpty()) "" else readingDigest(displayed)
        ranges = marks.mapNotNull { mark -> mark.anchor.locate(displayed, digest)?.let { mark to it } }
        available = marks.filter { store?.contains(it.key) == true }.map { it.key }.toSet()
        updateRecordsVisibility()
        recordsButton?.accessible("查看本条消息的 ${marks.size} 个点读片段")
        decoration = ""
        decorate()
    }

    private fun updateRecordsVisibility() {
        val visibility = if (enabled() && view.visibility == View.VISIBLE && marks.isNotEmpty()) View.VISIBLE else View.GONE
        if (recordsButton?.visibility != visibility) recordsButton?.visibility = visibility
    }

    private fun installRecordsButton() {
        if (recordsButton != null) return
        val parent = view.parent as? LinearLayout ?: return
        recordsButton = context.iconButton(R.drawable.ic_line_audio, "查看点读片段") { showRecords() }.apply {
            visibility = View.GONE
            iconTint = ColorStateList.valueOf(MUTED)
        }.also { parent.addView(it, parent.indexOfChild(view) + 1, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { gravity = Gravity.END }) }
    }

    private fun decorate() {
        val active = controller?.state
        val transport = playback
        val activeMark = if (transport?.currentState?.playing == true && active?.owner == owner && active.anchor?.block == block)
            active.anchor.id else ""
        val pending = active?.anchor?.takeIf { enabled() && active.owner == owner && active.generating && it.block == block }
        val signature = "$activeMark:${pending?.id}:${enabled()}"
        if (signature == decoration) return
        decoration = signature
        if (view.text !is Spannable) view.setText(SpannableString(view.text), TextView.BufferType.SPANNABLE)
        val text = view.text as Spannable
        text.getSpans(0, text.length, Tint::class.java).forEach(text::removeSpan)
        if (enabled()) {
            ranges.sortedBy { if (it.first.id == activeMark) 1 else 0 }.forEach { (mark, range) ->
                val color = if (mark.key in available) ACCENT else MUTED
                text.setSpan(Tint((color and 0x00ffffff) or (if (mark.id == activeMark) 0x30000000 else 0x14000000)),
                    range.first, range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            pending?.locate(text.toString())?.let { range ->
                text.setSpan(Tint((ACCENT and 0x00ffffff) or 0x20000000), range.first, range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        lines.invalidateSelf(); view.invalidate()
    }

    fun readSelection(selected: String) {
        val selection = ReadingAnchor.create(block, view.text.toString(), minOf(view.selectionStart, view.selectionEnd), maxOf(view.selectionStart, view.selectionEnd))
        // Keep a restored marker's identity when the overlay had displayed only a tail of the message.
        val anchor = ranges.lastOrNull { selection != null && it.second.first == selection.start && it.second.last + 1 == selection.end }
            ?.first?.anchor ?: selection
        PointReadingController.get(context).read(owner, ReadingOrigin.CHAT, selected, anchor = anchor)
    }

    private fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                moved = false; selecting = selectionActive() || (view.selectionStart >= 0 && view.selectionEnd > view.selectionStart)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop ||
                abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> moved = true
            MotionEvent.ACTION_UP -> {
                if (selecting || selectionActive() || moved || event.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) return false
                val offset = offset(event.x, event.y) ?: return false
                val text = view.text as? android.text.Spanned
                val link = text?.getSpans(offset, minOf(offset + 1, text.length), URLSpan::class.java)?.firstOrNull()
                if (link != null) {
                    cancelTap(event)
                    runCatching { link.onClick(view) }.onFailure {
                        Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                if (!enabled()) return false
                val hits = ranges.filter { offset in it.second }.map { it.first }
                if (hits.isEmpty()) return false
                // Cancel only this confirmed short tap. Long presses and drags stay native.
                cancelTap(event)
                if (hits.size == 1) play(hits.single()) else showRecords(hits)
                return true
            }
        }
        return false
    }

    private fun cancelTap(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        view.onTouchEvent(cancel); cancel.recycle()
    }

    private fun offset(x: Float, y: Float): Int? {
        val layout = view.layout ?: return null
        val px = x - view.totalPaddingLeft + view.scrollX
        val py = y - view.totalPaddingTop + view.scrollY
        if (py < 0 || py >= layout.height) return null
        val line = layout.getLineForVertical(py.toInt())
        if (px < layout.getLineLeft(line) || px > layout.getLineRight(line)) return null
        return layout.getOffsetForHorizontal(line, px).takeIf { it < view.text.length }
    }

    private fun show(builder: MaterialAlertDialogBuilder): AlertDialog {
        dialog?.dismiss()
        val next = builder.create()
        if (overlay) next.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog = next
        next.show()
        return next
    }

    private fun play(mark: ReadingMark) {
        dialog?.dismiss()
        val requestedOwner = owner
        val requestedBlock = block
        PointReadingController.get(context).playMark(owner, mark) {
            if (view.isAttachedToWindow && enabled() && owner == requestedOwner && block == requestedBlock) {
                refresh()
                show(MaterialAlertDialogBuilder(context).setTitle("音频已清理或不可用")
                    .setMessage("${mark.anchor.quote.take(160)}\n\n重新生成将使用当前朗读服务，可能收费。")
                    .setNegativeButton("取消", null).setPositiveButton("重新生成") { _, _ -> regenerate(mark) })
            }
        }
    }

    private fun regenerate(mark: ReadingMark) {
        if (!enabled()) return
        PointReadingController.get(context).read(owner, ReadingOrigin.CHAT, mark.anchor.quote, toggle = false, anchor = mark.anchor)
    }

    private fun showRecords(values: List<ReadingMark> = marks) {
        if (!enabled() || values.isEmpty()) return
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        values.forEach { mark ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(context.iconButton(R.drawable.ic_line_play, "播放 ${mark.anchor.quote.take(60)}") { locate(mark); play(mark) })
            row.addView(context.label(mark.anchor.quote, 13f).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                if (mark.key !in available) append(" · 音频已清理")
                setOnClickListener { locate(mark); play(mark) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(context.iconButton(R.drawable.ic_line_more, "点读片段操作") { options(mark) })
            content.addView(row)
        }
        show(MaterialAlertDialogBuilder(context).setTitle("点读片段")
            .setView(ScrollView(context).apply { addView(content) }).setPositiveButton("关闭", null))
    }

    private fun options(mark: ReadingMark) {
        show(MaterialAlertDialogBuilder(context).setTitle(mark.anchor.quote.take(60))
            .setItems(arrayOf("定位原文", "用当前音色重新生成", "移除标记")) { _, which ->
                when (which) {
                    0 -> locate(mark)
                    1 -> show(MaterialAlertDialogBuilder(context).setTitle("重新生成此片段？")
                        .setMessage("使用当前朗读服务与音色，可能收费。生成成功后更新此处标记。")
                        .setNegativeButton("取消", null).setPositiveButton("生成") { _, _ -> regenerate(mark) })
                    2 -> controller?.removeMark(owner, mark)
                }
            })
    }

    private fun locate(mark: ReadingMark) {
        val range = mark.anchor.locate(view.text.toString())
        val layout = view.layout
        if (range == null || layout == null) {
            Toast.makeText(context, "原文已变化，无法准确定位", Toast.LENGTH_SHORT).show(); return
        }
        val rectangle = android.graphics.Rect(0, layout.getLineTop(layout.getLineForOffset(range.first)), view.width,
            layout.getLineBottom(layout.getLineForOffset(range.last)))
        rectangle.offset(0, view.totalPaddingTop)
        view.requestRectangleOnScreen(rectangle, false)
        val text = view.text as? Spannable ?: return
        val flash = Tint((ACCENT and 0x00ffffff) or 0x30000000)
        text.setSpan(flash, range.first, range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.postDelayed({ text.removeSpan(flash) }, 1200)
    }

    override fun onViewAttachedToWindow(v: View) {
        if (attached) return
        attached = true
        readingEnabled = preferences.load().chatPointReading
        lines.setBounds(0, 0, view.width, view.height)
        view.overlay.add(lines)
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        preferences.addListener(listener)
        observe(); refresh()
    }
    override fun onViewDetachedFromWindow(v: View) {
        attached = false
        dialog?.dismiss(); dialog = null
        preferences.removeListener(listener)
        controller?.removeObserver(changed); playback?.removeObserver(playing)
        observing = false
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        view.overlay.remove(lines)
    }
}
