package com.alwayside.android

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/** A shared player and local Whisper subtitles; opening and browsing never sends audio. */
internal class SubtitlePanel(
    private val activity: Activity,
    private val snapshot: () -> StoredConversation,
    private val source: () -> File?,
) {
    private val owner = snapshot().id
    private val preferences = SubtitlePreferences(activity)
    private val controller = SubtitleController.get(activity)
    private val playback = ChatAudioPlayback.get(activity)
    private val method = SubtitleMethod.WHISPER
    private var displayMode = preferences.display()
    private val root = activity.column()
    private val status = activity.label("正在读取字幕…", 13f, true)
    private val details = activity.label("", 12f, true)
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(activity).apply { addView(rows); isFillViewport = false }
    private val modes = LinearLayout(activity).apply { gravity = Gravity.CENTER }
    private val play: MaterialButton
    private val make: MaterialButton
    private val cancel: MaterialButton
    private val progress = SeekBar(activity)
    private val clock = activity.label("", 12f, true)
    private val pageLabel = activity.label("", 12f, true)
    private var dragging = false
    private var following = true
    private var pageStart = 0
    private var activeIndex = -1
    private var renderedBundle: SubtitleBundle? = null
    private var rowsBuilt = false
    private var childDialog: AlertDialog? = null
    private val cueRows = mutableMapOf<Int, View>()
    private var dialog: AlertDialog? = null
    private var attached = false
    private val observer: () -> Unit = { render() }
    private val playbackObserver: () -> Unit = { tick() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!preferences.anyEnabled()) dialog?.dismiss()
        else {
            displayMode = preferences.display(); renderedBundle = null; rowsBuilt = false; render()
        }
    }

    init {
        with(activity) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(method.title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_line_info, "音频字幕说明") { help() })
                addView(iconButton(R.drawable.ic_line_more, "字幕操作") { menu(it = this) })
            })
            root.addView(status)
            root.addView(details.apply { setPadding(0, dp(6), 0, dp(6)) })
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                play = iconButton(R.drawable.ic_line_play, "回听原始音频", ::togglePlayback)
                addView(play)
                addView(progress, LinearLayout.LayoutParams(0, -2, 1f))
            })
            root.addView(clock)
            root.addView(modes, LinearLayout.LayoutParams(-1, -2))
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(android.R.drawable.ic_media_previous, "上一页字幕") { movePage(-1) })
                addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_line_subtitles, "跟随当前播放字幕") {
                    following = true; activeIndex = -1; tick()
                })
                addView(iconButton(android.R.drawable.ic_media_next, "下一页字幕") { movePage(1) })
            })
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(LinearLayout(this).apply {
                make = action("制作字幕", true) { confirmGeneration(false) }
                cancel = action("取消制作") { controller.cancel(owner, method) }
            })
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                source()?.let { playback.seekTo(owner, it, seekBar.progress.toLong()) }
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) clock.text = time(value.toLong()) + " / " + time(seekBar.max.toLong())
            }
        })
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) following = false
            false
        }
    }

    fun show(): AlertDialog {
        val height = (activity.resources.displayMetrics.heightPixels * 0.76f).toInt()
        root.layoutParams = android.view.ViewGroup.LayoutParams(-1, height)
        return MaterialAlertDialogBuilder(activity).setTitle("音频字幕").setView(root)
            .setNegativeButton("返回对话", null).create().also { shown ->
                dialog = shown
                shown.setOnDismissListener { detach() }
                shown.show()
                attached = true
                preferences.addListener(listener)
                controller.addObserver(observer)
                playback.addObserver(playbackObserver)
                controller.refresh(owner)
            }
    }

    private fun detach() {
        if (!attached) return
        attached = false
        childDialog?.dismiss(); childDialog = null
        preferences.removeListener(listener)
        controller.removeObserver(observer)
        playback.removeObserver(playbackObserver)
        dialog = null
    }

    private fun render() {
        if (!attached) return
        val record = controller.record(owner, method)
        status.text = record.status
        details.text = record.error
        details.visibility = if (record.error.isBlank()) View.GONE else View.VISIBLE
        make.visibility = if (record.busy) View.GONE else View.VISIBLE
        make.isEnabled = record.loaded && source() != null && preferences.enabled(method)
        make.text = when {
            record.draft != null -> if (record.draft.transcriptionComplete) "继续中文翻译" else "继续制作"
            record.published != null -> "已制作"
            else -> "制作字幕"
        }
        if (record.published != null && record.draft == null) make.visibility = View.GONE
        cancel.visibility = if (record.busy) View.VISIBLE else View.GONE
        modes.removeAllViews()
        SubtitleDisplay.entries.forEach { mode ->
            modes.addView(MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = mode.title; applyActionStyle(false); minWidth = 0; minimumWidth = 0
                isCheckable = true; isChecked = mode == displayMode
                setTextColor(if (isChecked) ACCENT else MUTED)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    runCatching { preferences.saveDisplay(mode) }.onSuccess {
                        displayMode = mode; rowsBuilt = false; render()
                    }.onFailure { isChecked = mode == displayMode; toast("无法保存字幕显示方式") }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val bundle = record.playable
        if (!rowsBuilt || bundle !== renderedBundle) {
            renderedBundle = bundle
            pageStart = pageStart.coerceIn(0, maxOf(0, (bundle?.cues?.size ?: 1) - 1))
            buildRows()
        }
        tick()
    }

    private fun buildRows() {
        rowsBuilt = true
        rows.removeAllViews(); cueRows.clear()
        val cues = renderedBundle?.cues.orEmpty()
        if (displayMode == SubtitleDisplay.OFF) {
            rows.addView(activity.label("字幕已关闭", 14f, true)); pageLabel.text = ""; return
        }
        if (cues.isEmpty()) {
            rows.addView(activity.label(if (source() == null) "原始音频未保留，无法制作字幕" else "为这段录音制作 Whisper 字幕", 14f, true))
            pageLabel.text = ""; return
        }
        val end = minOf(cues.size, pageStart + PAGE_SIZE)
        pageLabel.text = "${pageStart + 1}–$end / ${cues.size}"
        for (index in pageStart until end) {
            val cue = cues[index]
            val chinese = renderedBundle?.translations?.get(cue.id).orEmpty()
            val block = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10))
                background = activity.touchFeedback()
                isClickable = true; isFocusable = true
                addView(activity.label(time(cue.startMs), 11f, true))
                if (displayMode != SubtitleDisplay.CHINESE) addView(activity.label(cue.text, 15f))
                if (displayMode != SubtitleDisplay.ENGLISH) addView(activity.label(chinese.ifBlank { "中文尚未完成" }, 15f, chinese.isBlank()).apply {
                    setPadding(0, activity.dp(5), 0, 0)
                })
                setOnClickListener {
                    val file = source()
                    if (file == null) toast("原始音频已不可用")
                    else { following = true; playback.seekTo(owner, file, cue.startMs) }
                }
            }
            rows.addView(block, LinearLayout.LayoutParams(-1, -2))
            cueRows[index] = block
        }
        activeIndex = -1
    }

    private fun tick() {
        if (!attached) return
        val active = playback.isActive(owner) && playback.readingKey.isEmpty()
        val state = if (active) playback.currentState else ChatAudioPlayback.State()
        val duration = if (active) playback.duration.toLong() else renderedBundle?.durationMs ?: sourceDuration()
        val position = if (active) playback.position.toLong() else 0L
        play.icon = activity.prototypeIcon(if (state.playing) android.R.drawable.ic_media_pause else R.drawable.ic_line_play)
        play.accessible(if (state.playing) "暂停回听" else "回听原始音频")
        play.isEnabled = source() != null && !state.preparing
        progress.isEnabled = source() != null && duration > 0 && !state.preparing
        if (!dragging) {
            progress.max = duration.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            progress.progress = position.coerceIn(0, progress.max.toLong()).toInt()
            clock.text = time(position) + " / " + time(duration)
        }
        val cues = renderedBundle?.cues.orEmpty()
        val next = if (active && displayMode != SubtitleDisplay.OFF) SubtitleTimeline.activeIndex(cues, position) else -1
        if (next == activeIndex) return
        if (following && next >= 0 && next !in cueRows.keys) {
            pageStart = next / PAGE_SIZE * PAGE_SIZE
            buildRows()
        }
        cueRows[activeIndex]?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        cueRows[next]?.setBackgroundColor(TONAL_SURFACE)
        activeIndex = next
        if (following && next >= 0) cueRows[next]?.let { row -> scroll.post { if (attached && following) scroll.smoothScrollTo(0, row.top) } }
    }

    private fun sourceDuration(): Long = source()?.let { maxOf(0, it.length() - 44) * 1000 / (RecordingLimits.SAMPLE_RATE * 2) } ?: 0
    private fun togglePlayback() {
        val file = source() ?: return toast("原始音频未保留或已被删除")
        if (playback.isActive(owner) && playback.readingKey.isEmpty()) playback.toggle() else playback.play(owner, file)
    }
    private fun movePage(direction: Int) {
        val size = renderedBundle?.cues?.size ?: return
        if (size == 0) return
        following = false
        pageStart = (pageStart + direction * PAGE_SIZE).coerceIn(0, (size - 1) / PAGE_SIZE * PAGE_SIZE)
        buildRows(); scroll.scrollTo(0, 0); tick()
    }
    private fun menu(it: View) {
        PopupMenu(activity, it).apply {
            menu.add("重新制作").setOnMenuItemClickListener { confirmGeneration(true); true }
            menu.add("删除字幕").setOnMenuItemClickListener {
                val selected = method
                showChild(MaterialAlertDialogBuilder(activity).setTitle("删除${selected.title}字幕？")
                    .setMessage("保留原始录音。再次制作会重新请求服务。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        if (attached) runCatching { controller.delete(owner, selected) }.onFailure { toast("无法删除字幕，请重试") }
                    }); true
            }
            show()
        }
    }
    private fun confirmGeneration(recreate: Boolean) {
        val selected = method
        val file = source() ?: return toast("原始音频不可用")
        val recording = snapshot()
        val record = controller.record(owner, selected)
        if (record.busy) return
        val estimate = sourceDuration() / 60_000.0 * selected.dollarsPerMinute
        val retained = runCatching { ConversationMediaStore(activity).loadAudio(owner) != null }.getOrDefault(false)
        val title = if (recreate) "重新制作${selected.title}字幕？" else if (retained) "制作${selected.title}字幕" else "保留本段录音并制作字幕"
        showChild(MaterialAlertDialogBuilder(activity).setTitle(title)
            .setMessage("录音 ${time(sourceDuration())} · 转写约 ${String.format(Locale.US, "$%.4f", estimate)}\n中文翻译另计；继续制作只处理未完成部分。" +
                if (recreate) "\n将重新上传音频，成功后替换旧字幕。" else "")
            .setNegativeButton("取消", null).setPositiveButton(if (record.draft != null && !recreate) "继续制作" else "开始制作") { _, _ ->
                if (!attached) return@setPositiveButton
                runCatching { controller.enqueue(recording, file, selected, recreate) }
                    .onFailure { toast(it.message?.take(160) ?: "无法开始制作，请检查实验室设置") }
            })
    }
    private fun help() {
        showChild(MaterialAlertDialogBuilder(activity).setTitle("回听字幕")
            .setMessage("仅处理你主动选择的录音。Whisper 使用服务返回的片段时间戳，再由所选文本模型翻译为中文。\n\n中英文共享时间轴，切换显示或重播不收费。新字幕制作失败时保留旧版本，成功的转写和翻译批次可手动继续。\n\n手动翻页或滚动后停止自动跟随，点字幕图标恢复。应用被系统结束后需要手动继续制作，不保证后台完成。")
            .setPositiveButton("知道了", null))
    }
    private fun showChild(builder: MaterialAlertDialogBuilder) {
        childDialog?.dismiss()
        childDialog = builder.show()
    }
    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    private fun time(ms: Long): String {
        val seconds = maxOf(0, ms) / 1000
        return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    private companion object { const val PAGE_SIZE = 60 }
}
