package com.alwayside.android

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/** The approved caption dialog stays above the current conversation and shares its audio player. */
internal class SubtitlePanel(
    private val activity: Activity,
    private val snapshot: () -> StoredConversation,
    private val source: () -> File?,
) {
    private val owner = snapshot().id
    private val preferences = SubtitlePreferences(activity)
    private val reading = SubtitleReadingPreferences(activity)
    private val controller = SubtitleController.get(activity)
    private val playback = ChatAudioPlayback.get(activity)
    private val method = SubtitleMethod.WHISPER
    private var displayMode = preferences.display().takeUnless { it == SubtitleDisplay.OFF } ?: SubtitleDisplay.BILINGUAL
    private val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val status = label("正在读取字幕…", 11f, true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = (activity.resources.displayMetrics.widthPixels - activity.dp(196)).coerceAtLeast(activity.dp(80))
    }
    private val details = label("", 12f, true)
    private val progress = SeekBar(activity)
    private val clock = label("0:00", 11f, true)
    private val total = label("0:00", 11f, true)
    private val languageButtons = mutableMapOf<SubtitleDisplay, MaterialButton>()
    private val play: MaterialButton
    private val follow: MaterialButton
    private val speed: MaterialButton
    private val floating: MaterialButton
    private val make: MaterialButton
    private val cancel: MaterialButton
    private val empty: LinearLayout
    private val emptyMessage = label("为这段录音制作字幕", 15f)
    private val adapter = CueAdapter()
    private val transcript = ListView(activity).apply {
        divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10)); clipToPadding = false
        this.adapter = this@SubtitlePanel.adapter
    }
    private var dragging = false
    private var following = true
    private var activeIndex = -1
    private var originalBundle: SubtitleBundle? = null
    private var renderedBundle: SubtitleBundle? = null
    private var renderedSegmentation: SubtitleSegmentation? = null
    private var previewFont: Int? = null
    private var childDialog: AlertDialog? = null
    private var settings: SubtitleDisplaySettings? = null
    private var dialog: AlertDialog? = null
    private var attached = false
    private var previousPlaying: Boolean? = null
    private val observer: () -> Unit = { render() }
    private val playbackObserver: () -> Unit = { tick() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        displayMode = preferences.display().takeUnless { it == SubtitleDisplay.OFF } ?: SubtitleDisplay.BILINGUAL
        render()
    }
    private val readingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    init {
        with(activity) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(4), dp(10), 0)
                addView(label("音频字幕", 18f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(icon(R.drawable.ic_line_sliders, "音频字幕显示设置", ::showSettings))
                addView(icon(R.drawable.ic_line_more, "字幕操作", {}).apply { setOnClickListener { anchor -> menu(anchor) } })
                addView(icon(R.drawable.ic_line_close, "关闭音频字幕", { dialog?.dismiss() }))
            })
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(10))
                addView(label("原始录音", 11f, true))
                addView(label(snapshot().title.ifBlank { "原始音频" }, 18f).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(3) })
                addView(LinearLayout(this@with).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("•", 13f).apply { setTextColor(ACCENT); setPadding(0, 0, dp(6), 0) })
                    addView(status)
                    addView(label(" · ${method.title}", 11f, true))
                    addView(icon(R.drawable.ic_line_info, "音频字幕说明", ::help, 30))
                    cancel = icon(R.drawable.ic_line_close, "取消字幕制作", { controller.cancel(owner, method) }, 30)
                    addView(cancel)
                    addView(View(this@with), LinearLayout.LayoutParams(0, 1, 1f))
                })
                addView(details.apply { visibility = View.GONE })
            })
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), dp(10))
                addView(LinearLayout(this@with).apply {
                    setPadding(dp(3), dp(3), dp(3), dp(3)); background = card(SURFACE, dp(10).toFloat())
                    listOf(SubtitleDisplay.BILINGUAL, SubtitleDisplay.CHINESE, SubtitleDisplay.ENGLISH).forEach { mode ->
                        val button = textButton(mode.title).apply {
                            setOnClickListener { save { preferences.saveDisplay(mode) } }
                        }
                        languageButtons[mode] = button
                        addView(button, LinearLayout.LayoutParams(dp(43), dp(34)))
                    }
                })
                addView(View(this@with), LinearLayout.LayoutParams(0, 1, 1f))
                follow = textButton("跟随播放").apply {
                    icon = prototypeIcon(R.drawable.ic_line_follow); iconSize = dp(15); iconPadding = dp(3); textSize = 11f
                    setOnClickListener { following = true; activeIndex = -1; updateFollow(); tick() }
                }
                addView(follow, LinearLayout.LayoutParams(-2, dp(36)))
            })
            root.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
            empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(12), dp(20), dp(12))
                addView(android.widget.ImageView(this@with).apply {
                    setImageDrawable(prototypeIcon(R.drawable.ic_line_subtitles)); imageTintList = ColorStateList.valueOf(ACCENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(14) })
                addView(emptyMessage)
                make = MaterialButton(this@with).apply {
                    text = "制作字幕"; applyActionStyle(true); setOnClickListener { confirmGeneration(false) }
                }
                addView(make, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) })
            }
            root.addView(android.widget.FrameLayout(this).apply {
                addView(transcript, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4))
                addView(LinearLayout(this@with).apply {
                    addView(clock, LinearLayout.LayoutParams(0, -2, 1f)); addView(total)
                })
                addView(progress, LinearLayout.LayoutParams(-1, dp(32)))
                addView(LinearLayout(this@with).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    speed = textButton("1.0×").apply { backgroundTintList = ColorStateList.valueOf(SURFACE); setOnClickListener { speedMenu(this) } }
                    addView(speed, LinearLayout.LayoutParams(dp(52), dp(34)))
                    addView(View(this@with), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(icon(R.drawable.ic_line_rewind, "后退 5 秒", { seekRelative(-5000) }))
                    play = icon(R.drawable.ic_line_play, "回听原始音频", ::togglePlayback, 46).apply {
                        backgroundTintList = ColorStateList.valueOf(ACCENT); iconTint = ColorStateList.valueOf(ON_ACCENT); cornerRadius = dp(24)
                    }
                    addView(play)
                    addView(icon(R.drawable.ic_line_rewind, "前进 5 秒", { seekRelative(5000) }).apply { scaleX = -1f })
                    addView(View(this@with), LinearLayout.LayoutParams(0, 1, 1f))
                    floating = icon(R.drawable.ic_line_floating_subtitles, "显示后台字幕", { SubtitleOverlayController.restore(activity) })
                    floating.iconTint = ColorStateList.valueOf(ACCENT); addView(floating)
                })
            })
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false; following = true; updateFollow()
                source()?.let { playback.seekTo(owner, it, seekBar.progress.toLong()) }
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) clock.text = time(value.toLong()) }
        })
        transcript.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { following = false; updateFollow() }
            false
        }
        transcript.setOnItemClickListener { _, _, index, _ ->
            val file = source()
            val cue = renderedBundle?.cues?.getOrNull(index)
            if (file == null) toast("原始音频已不可用")
            else if (cue != null) { following = true; updateFollow(); playback.seekTo(owner, file, cue.startMs) }
        }
    }

    fun show(): AlertDialog {
        root.layoutParams = ViewGroup.LayoutParams(-1, -1)
        val height = (activity.resources.displayMetrics.heightPixels * .80f).toInt()
        return MaterialAlertDialogBuilder(activity).setView(root).setNegativeButton("返回对话", null).create().also { shown ->
            dialog = shown; shown.setOnDismissListener { detach() }; shown.show()
            shown.window?.apply {
                setBackgroundDrawable(card(BACKGROUND, activity.dp(23).toFloat()))
                setLayout(activity.resources.displayMetrics.widthPixels - activity.dp(36), height)
                attributes = attributes.apply { dimAmount = .22f }
                androidx.core.view.WindowInsetsControllerCompat(this, decorView).apply {
                    isAppearanceLightStatusBars = !AppAppearance.isNight
                    isAppearanceLightNavigationBars = !AppAppearance.isNight
                }
            }
            attached = true
            SubtitleOverlayController.panelVisible(activity, this, owner, true)
            preferences.addListener(listener); reading.addListener(readingListener)
            controller.addObserver(observer); playback.addObserver(playbackObserver); controller.refresh(owner)
        }
    }

    fun dismissForOverlay() { dialog?.dismiss() }
    private fun detach() {
        if (!attached) return
        attached = false
        childDialog?.dismiss(); childDialog = null; settings?.finish(); settings = null
        preferences.removeListener(listener); reading.removeListener(readingListener)
        controller.removeObserver(observer); playback.removeObserver(playbackObserver)
        dialog = null; SubtitleOverlayController.panelVisible(activity, this, owner, false)
    }

    private fun render() {
        if (!attached) return
        val record = controller.record(owner, method)
        status.text = record.status; details.text = record.error
        details.visibility = if (record.error.isBlank()) View.GONE else View.VISIBLE
        cancel.visibility = if (record.busy) View.VISIBLE else View.GONE
        val segmentation = reading.segmentation()
        if (originalBundle !== record.playable || renderedSegmentation != segmentation) {
            originalBundle = record.playable; renderedSegmentation = segmentation
            renderedBundle = originalBundle?.let { SubtitleReading.project(it, segmentation) }; activeIndex = -1
        }
        val hasCues = !renderedBundle?.cues.isNullOrEmpty()
        transcript.visibility = if (hasCues) View.VISIBLE else View.GONE
        empty.visibility = if (hasCues) View.GONE else View.VISIBLE
        emptyMessage.text = if (record.busy) record.status else if (source() == null) "原始音频未保留，无法制作字幕" else "为这段录音制作字幕"
        make.visibility = if (record.busy) View.GONE else View.VISIBLE
        make.isEnabled = record.loaded && source() != null && preferences.enabled(method)
        make.text = if (record.draft == null) "制作字幕" else if (record.draft.transcriptionComplete) "继续中文翻译" else "继续制作"
        languageButtons.forEach { (mode, button) ->
            val selected = mode == displayMode; button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) BACKGROUND else Color.TRANSPARENT)
            button.setTextColor(if (selected) ACCENT else MUTED)
        }
        adapter.notifyDataSetChanged(); tick()
    }

    private fun tick() {
        if (!attached) return
        val active = playback.isActive(owner) && playback.readingKey.isEmpty()
        val state = if (active) playback.currentState else ChatAudioPlayback.State()
        val duration = if (active) playback.duration.toLong() else renderedBundle?.durationMs ?: sourceDuration()
        val position = if (active) playback.position.toLong() else 0L
        if (previousPlaying != state.playing) {
            previousPlaying = state.playing
            play.icon = activity.prototypeIcon(if (state.playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
            play.accessible(if (state.playing) "暂停回听" else "继续回听")
        }
        play.isEnabled = source() != null && !state.preparing; progress.isEnabled = source() != null && duration > 0 && !state.preparing
        floating.isEnabled = !renderedBundle?.cues.isNullOrEmpty() && active
        speed.text = String.format(Locale.ROOT, "%.2f×", playback.originalSpeed).replace(".00", ".0").replace(".50", ".5")
        if (!dragging) { progress.max = duration.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(); progress.progress = position.toInt(); clock.text = time(position) }
        total.text = time(duration)
        val next = if (active) SubtitleReading.activeIndex(renderedBundle?.cues.orEmpty(), position, state.playing) else -1
        if (next == activeIndex) return
        activeIndex = next; adapter.notifyDataSetChanged()
        if (following && next >= 0) transcript.post { if (attached && following) transcript.smoothScrollToPositionFromTop(next, activity.dp(12)) }
    }

    private fun updateFollow() {
        follow.text = if (following) "跟随播放" else "回到当前"
        follow.setTextColor(if (following) ACCENT else MUTED); follow.iconTint = ColorStateList.valueOf(if (following) ACCENT else MUTED)
    }

    private inner class CueAdapter : BaseAdapter() {
        override fun getCount() = renderedBundle?.cues?.size ?: 0
        override fun getItem(position: Int) = renderedBundle!!.cues[position]
        override fun getItemId(position: Int) = getItem(position).id.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? CueViews) ?: createCue()
            val cue = getItem(position); val font = (previewFont ?: reading.fontSize()).toFloat()
            holder.time.text = time(cue.startMs); holder.time.setTextColor(if (position == activeIndex) ACCENT else MUTED)
            holder.chinese.text = renderedBundle?.translations?.get(cue.id).orEmpty().ifBlank { "中文尚未完成" }
            holder.chinese.textSize = font; holder.english.text = cue.text
            holder.english.textSize = if (displayMode == SubtitleDisplay.ENGLISH) font else font * .85f
            holder.chinese.visibility = if (displayMode == SubtitleDisplay.ENGLISH) View.GONE else View.VISIBLE
            holder.english.visibility = if (displayMode == SubtitleDisplay.CHINESE) View.GONE else View.VISIBLE
            holder.english.setPadding(0, if (displayMode == SubtitleDisplay.BILINGUAL) activity.dp(7) else 0, 0, 0)
            holder.root.background = if (position == activeIndex) card(TONAL_SURFACE, activity.dp(12).toFloat()) else activity.touchFeedback()
            holder.root.contentDescription = "${time(cue.startMs)} ${if (displayMode == SubtitleDisplay.ENGLISH) cue.text else holder.chinese.text}"
            return holder.root
        }
    }
    private data class CueViews(val root: LinearLayout, val time: TextView, val chinese: TextView, val english: TextView)
    private fun createCue(): CueViews {
        val stamp = label("", 11f, true)
        val chinese = label("", 17f).apply { typeface = android.graphics.Typeface.DEFAULT; setLineSpacing(0f, 1.3f) }
        val english = label("", 14f, true).apply { typeface = android.graphics.Typeface.DEFAULT; setLineSpacing(0f, 1.3f) }
        val row = LinearLayout(activity).apply {
            gravity = Gravity.TOP; setPadding(activity.dp(12), activity.dp(14), activity.dp(12), activity.dp(14))
            addView(stamp, LinearLayout.LayoutParams(activity.dp(42), -2).apply { topMargin = activity.dp(3) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; addView(chinese); addView(english)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return CueViews(row, stamp, chinese, english).also { row.tag = it }
    }

    private fun showSettings() {
        childDialog?.dismiss()
        val panel = SubtitleDisplaySettings(activity, reading, onPreview = { font, _, _, _ -> previewFont = font; adapter.notifyDataSetChanged() },
            onClose = { childDialog?.dismiss() })
        settings = panel
        childDialog = MaterialAlertDialogBuilder(activity).setView(panel.view).create().also { child ->
            child.setOnDismissListener { panel.finish(); settings = null; previewFont = null; if (attached) adapter.notifyDataSetChanged() }
            child.show(); child.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                setLayout(activity.resources.displayMetrics.widthPixels - activity.dp(20), -2)
                attributes = attributes.apply { y = activity.dp(24) }
            }
        }
    }
    private fun speedMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                menu.add("${value}×").apply {
                    isCheckable = true; isChecked = playback.originalSpeed == value
                    setOnMenuItemClickListener { playback.setOriginalSpeed(value); tick(); true }
                }
            }; show()
        }
    }
    private fun sourceDuration() = source()?.let { maxOf(0, it.length() - 44) * 1000 / (RecordingLimits.SAMPLE_RATE * 2) } ?: 0L
    private fun togglePlayback() {
        val file = source() ?: return toast("原始音频未保留或已被删除")
        if (playback.isActive(owner) && playback.readingKey.isEmpty()) playback.toggle() else playback.play(owner, file)
    }
    private fun seekRelative(delta: Long) {
        val position = if (playback.isActive(owner) && playback.readingKey.isEmpty()) playback.position.toLong() else 0L
        source()?.let { playback.seekTo(owner, it, (position + delta).coerceAtLeast(0)) }
    }
    private fun menu(it: View) {
        PopupMenu(activity, it).apply {
            menu.add("导出双语字幕").apply {
                isEnabled = originalBundle?.bilingualComplete == true
                setOnMenuItemClickListener { export(); true }
            }
            menu.add("重新制作字幕").apply {
                isEnabled = preferences.enabled(method) && !controller.record(owner, method).busy
                setOnMenuItemClickListener { confirmGeneration(true); true }
            }
            menu.add("删除字幕").setOnMenuItemClickListener {
                showChild(MaterialAlertDialogBuilder(activity).setTitle("删除字幕？").setMessage("保留原始录音。再次制作会重新请求服务。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        if (attached) save { controller.delete(owner, method) }
                    }); true
            }; show()
        }
    }
    private fun export() {
        val bundle = originalBundle?.takeIf { it.bilingualComplete } ?: return
        save {
            val directory = File(activity.cacheDir, "subtitle-exports").apply { mkdirs() }
            val file = File(directory, "audio-subtitles-${System.currentTimeMillis()}.srt")
            file.writeText(SubtitleFormat.srt(bundle.cues, bundle.cues.associate { it.id to "${bundle.translations[it.id]}\n${it.text}" }), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.subtitles", file)
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-subrip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出双语字幕"))
        }
    }
    private fun confirmGeneration(recreate: Boolean) {
        val file = source() ?: return toast("原始音频不可用")
        val recording = snapshot(); val record = controller.record(owner, method)
        if (record.busy) return
        val estimate = sourceDuration() / 60_000.0 * method.dollarsPerMinute
        val retained = runCatching { ConversationMediaStore(activity).loadAudio(owner) != null }.getOrDefault(false)
        val title = if (recreate) "重新制作${method.title}字幕？" else if (retained) "制作${method.title}字幕" else "保留本段录音并制作字幕"
        showChild(MaterialAlertDialogBuilder(activity).setTitle(title)
            .setMessage("录音 ${time(sourceDuration())} · 转写约 ${String.format(Locale.US, "$%.4f", estimate)}\n中文翻译另计；继续制作只处理未完成部分。" +
                if (recreate) "\n将重新上传音频，成功后替换旧字幕。" else "")
            .setNegativeButton("取消", null).setPositiveButton(if (record.draft != null && !recreate) "继续制作" else "开始制作") { _, _ ->
                if (attached) save { controller.enqueue(recording, file, method, recreate) }
            })
    }
    private fun help() {
        showChild(MaterialAlertDialogBuilder(activity).setTitle("音频字幕")
            .setMessage("点击制作，为当前录音生成原文和中文字幕。录音及转写文字会发送到 OpenAI，可能收费；切换显示或重播已有字幕不收费。\n\n制作中断后可继续；重新制作失败时保留原有字幕。应用被系统结束后，需重新打开并手动继续。\n\n手动滚动后暂停自动跟随，点击「回到当前」恢复。点按任意字幕定位音频；后台字幕可从播放通知或当前音频对话重新打开。")
            .setPositiveButton("知道了", null))
    }
    private fun showChild(builder: MaterialAlertDialogBuilder) { childDialog?.dismiss(); childDialog = builder.show() }
    private fun save(action: () -> Unit) { runCatching(action).onFailure { toast(it.message?.take(160) ?: "操作失败，请重试") } }
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun time(ms: Long) = android.text.format.DateUtils.formatElapsedTime(maxOf(0, ms) / 1000)
    private fun label(text: String, size: Float, muted: Boolean = false) = activity.label(text, size, muted).apply { textSize = size; setLineSpacing(0f, 1.15f) }
    private fun textButton(title: String) = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = title; applyActionStyle(false); textSize = 12f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        insetTop = 0; insetBottom = 0; setPadding(activity.dp(5), 0, activity.dp(5), 0); cornerRadius = activity.dp(7)
    }
    private fun icon(resource: Int, description: String, action: () -> Unit, size: Int = 36) = activity.iconButton(resource, description, action).apply {
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; iconSize = activity.dp(18)
        layoutParams = LinearLayout.LayoutParams(activity.dp(size), activity.dp(if (size == 36) 40 else size))
    }
}
