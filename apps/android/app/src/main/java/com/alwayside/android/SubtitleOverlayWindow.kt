package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/** Only the caption rectangle and its settings sheet consume touches in other apps. */
internal class SubtitleOverlayWindow(
    appContext: Context,
    private val preferences: SubtitleOverlayPreferences,
    private val onClose: () -> Unit,
    private val onOpen: () -> Unit,
    private val onPlayback: () -> Unit,
    private val onChanged: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val context = AppAppearance.themedContext(appContext)
    private val reading = SubtitleReadingPreferences(appContext)
    private val windows = appContext.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; elevation = context.dp(8).toFloat(); clipToOutline = true
    }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(context.dp(5), 0, context.dp(5), 0) }
    private val chineseText = context.label("", 17f).apply {
        typeface = android.graphics.Typeface.DEFAULT; setLineSpacing(0f, 1.3f)
    }
    private val englishText = context.label("", 14f, true).apply {
        typeface = android.graphics.Typeface.DEFAULT; setLineSpacing(0f, 1.3f); setPadding(0, context.dp(7), 0, 0)
    }
    private val scroll = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(context.dp(15), context.dp(10), context.dp(15), context.dp(7))
            addView(chineseText); addView(englishText)
        })
    }
    private val clock = context.label("", 11f, true).apply { textSize = 11f }
    private val status = context.label("", 11f, true).apply { textSize = 10f }
    private val chinese: MaterialButton
    private val english: MaterialButton
    private val play: MaterialButton
    private val icons = mutableListOf<MaterialButton>()
    private var attached = false
    private var settings: SubtitleDisplaySettings? = null
    private var unregisterSettingsBack: (() -> Unit)? = null
    private var outsideDismissedAt = 0L
    private var normalGeometry: IntArray? = null
    private var preview: Style? = null
    private var previousText = ""
    private var previousPlaying: Boolean? = null
    private var previousSecond = -1
    private var previousStyle: Style? = null
    private var mode = preferences.mode()
    private data class Style(val font: Int, val opacity: Int, val theme: SubtitleOverlayTheme, val custom: Int)
    private val layout = params().apply { setTitle("AlwaySide 音频字幕") }

    init {
        with(context) {
            header.addView(smallIcon(R.drawable.ic_line_subtitles, "打开音频字幕", onOpen))
            fun language(title: String, value: SubtitleDisplay) = MaterialButton(this, null,
                androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = title; applyActionStyle(false); textSize = 12f; isCheckable = true
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0); insetTop = 0; insetBottom = 0; cornerRadius = dp(7)
                accessible("显示${if (value == SubtitleDisplay.CHINESE) "中文" else "英文"}字幕")
                setOnClickListener { save { preferences.saveMode(SubtitleOverlayState.toggleLanguage(preferences.mode(), value)) } }
            }
            chinese = language("中", SubtitleDisplay.CHINESE); english = language("英", SubtitleDisplay.ENGLISH)
            header.addView(chinese, LinearLayout.LayoutParams(dp(28), dp(32)))
            header.addView(english, LinearLayout.LayoutParams(dp(28), dp(32)))
            val handle = smallIcon(R.drawable.ic_line_grip, "拖动字幕浮窗") { toast("拖动此处移动字幕浮窗") }
            header.addView(handle, LinearLayout.LayoutParams(0, dp(38), 1f)); installDrag(handle, false)
            play = smallIcon(R.drawable.ic_line_pause, "暂停回听", onPlayback)
            header.addView(play)
            header.addView(smallIcon(R.drawable.ic_line_sliders, "浮窗显示设置", ::showSettings))
            header.addView(smallIcon(R.drawable.ic_line_close, "关闭字幕浮窗，音频继续播放", onClose))
            root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), 0, dp(4), 0)
                addView(clock, LinearLayout.LayoutParams(0, -2, 1f))
                addView(status)
                val resize = smallIcon(R.drawable.ic_line_resize_grip, "拖动调整字幕浮窗大小") { toast("拖动右下角调整大小") }
                addView(resize, LinearLayout.LayoutParams(dp(32), dp(30))); installDrag(resize, true)
            }, LinearLayout.LayoutParams(-1, dp(32)))
            restoreGeometry()
            applyAppearance()
        }
    }

    fun show() { windows.addView(root, layout); attached = true }
    fun close() {
        closeSettings(restore = false)
        if (attached) { attached = false; runCatching { windows.removeViewImmediate(root) } }
    }

    fun render(bundle: SubtitleBundle, index: Int, mode: SubtitleDisplay, playing: Boolean, position: Int, duration: Int) {
        if (!attached) return
        this.mode = mode
        val cue = bundle.cues.getOrNull(index)
        val chineseValue = cue?.let { bundle.translations[it.id].orEmpty().ifBlank { "中文尚未完成" } }.orEmpty()
        val englishValue = cue?.text.orEmpty()
        val text = "$chineseValue\n$englishValue"
        if (text != previousText) {
            previousText = text; chineseText.text = chineseValue; englishText.text = englishValue
            scroll.scrollTo(0, 0)
        }
        chineseText.visibility = if (mode == SubtitleDisplay.ENGLISH || chineseValue.isEmpty()) View.GONE else View.VISIBLE
        englishText.visibility = if (mode == SubtitleDisplay.CHINESE || englishValue.isEmpty()) View.GONE else View.VISIBLE
        if (previousPlaying != playing) {
            previousPlaying = playing
            play.icon = context.prototypeIcon(if (playing) R.drawable.ic_line_pause else R.drawable.ic_line_play)
            play.accessible(if (playing) "暂停回听" else "继续回听")
        }
        if (previousSecond != position / 1000) {
            previousSecond = position / 1000
            clock.text = android.text.format.DateUtils.formatElapsedTime(position / 1000L) + " / " +
                android.text.format.DateUtils.formatElapsedTime(duration / 1000L)
        }
        val state = if (!playing) "已暂停" else if (cue == null) "句间停顿" else "播放中"
        if (status.text.toString() != state) status.text = state
        applyAppearance()
    }

    private fun applyAppearance() {
        val style = preview ?: Style(preferences.fontSize(), preferences.opacity(), preferences.theme(), preferences.customColor())
        val palette = SubtitleAppearance.palette(style.theme, style.custom, SubtitlePalette(SURFACE, INK, MUTED, BORDER, ACCENT, TONAL_SURFACE))
        if (style != previousStyle) {
            previousStyle = style
            chineseText.textSize = style.font.toFloat(); englishText.textSize = style.font * .85f
            chineseText.setTextColor(palette.text); englishText.setTextColor(palette.muted)
            clock.setTextColor(palette.muted); status.setTextColor(palette.muted)
            root.background = GradientDrawable().apply {
                cornerRadius = context.dp(16).toFloat(); setColor(alpha(palette.background, style.opacity * 255 / 100))
                setStroke(context.dp(1).coerceAtLeast(1), alpha(palette.border, 170))
            }
            header.setBackgroundColor(alpha(palette.background, 240))
            icons.forEach { it.iconTint = ColorStateList.valueOf(palette.text) }
        }
        chinese.isChecked = mode != SubtitleDisplay.ENGLISH; english.isChecked = mode != SubtitleDisplay.CHINESE
        listOf(chinese, english).forEach { button ->
            button.setTextColor(if (button.isChecked) palette.accent else palette.muted)
            button.backgroundTintList = ColorStateList.valueOf(if (button.isChecked) palette.selected else android.graphics.Color.TRANSPARENT)
        }
    }

    private fun showSettings() {
        if (android.os.SystemClock.uptimeMillis() - outsideDismissedAt < 200) return
        if (settings != null) { closeSettings(); return }
        normalGeometry = intArrayOf(layout.x, layout.y, layout.width, layout.height)
        val sheet = SubtitleDisplaySettings(context, reading, preferences,
            onPreview = { font, opacity, theme, color -> preview = Style(font, opacity, theme, color); applyAppearance() },
            onClose = { closeSettings() }, onResetWindow = {
                preferences.resetWindow(); closeSettings(); restoreGeometry(); update(); saveWindow(); showSettings()
            })
        val size = screenSize()
        val sheetLayout = params().apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = (size.first - context.dp(20)).coerceAtLeast(1)
            height = minOf(context.dp(410), size.second * 52 / 100)
            x = context.dp(10); y = size.second - height - context.dp(64)
            setTitle("AlwaySide 字幕显示设置")
        }
        sheet.view.isFocusableInTouchMode = true
        sheet.view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                outsideDismissedAt = android.os.SystemClock.uptimeMillis(); closeSettings(); true
            } else false
        }
        runCatching { windows.addView(sheet.view, sheetLayout) }.onSuccess {
            settings = sheet
            sheet.view.requestFocus()
            if (Build.VERSION.SDK_INT >= 33) {
                val dispatcher = sheet.view.findOnBackInvokedDispatcher()
                val callback = android.window.OnBackInvokedCallback { closeSettings() }
                dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                unregisterSettingsBack = { dispatcher?.unregisterOnBackInvokedCallback(callback) }
            }
            layout.height = minOf(layout.height, (sheetLayout.y - context.dp(44)).coerceAtLeast(context.dp(158)))
            layout.y = minOf(layout.y, sheetLayout.y - layout.height - context.dp(12))
            clamp(); update()
        }.onFailure { normalGeometry = null; toast("无法打开字幕显示设置") }
    }

    private fun closeSettings(restore: Boolean = true) {
        val sheet = settings ?: return
        settings = null; sheet.finish(); unregisterSettingsBack?.invoke(); unregisterSettingsBack = null
        runCatching { windows.removeViewImmediate(sheet.view) }
        preview = null; previousStyle = null
        normalGeometry?.let { if (restore) { layout.x = it[0]; layout.y = it[1]; layout.width = it[2]; layout.height = it[3] } }
        normalGeometry = null
        if (restore) { clamp(); update(); applyAppearance(); onChanged() }
    }

    private fun smallIcon(resource: Int, description: String, action: () -> Unit) =
        context.iconButton(resource, description, action).apply {
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            iconSize = context.dp(18); layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(38))
            icons += this
        }

    private fun save(action: () -> Unit) {
        runCatching(action).onFailure { toast(it.message ?: "无法保存字幕设置") }
        onChanged()
    }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    private fun alpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun installDrag(handle: View, resizing: Boolean) {
        var downX = 0f; var downY = 0f
        var initialX = 0; var initialY = 0; var initialWidth = 0; var initialHeight = 0
        var moved = false
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initialX = layout.x; initialY = layout.y
                    initialWidth = layout.width; initialHeight = layout.height; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        if (resizing) { layout.width = initialWidth + dx; layout.height = initialHeight + dy }
                        else { layout.x = initialX + dx; layout.y = initialY + dy }
                        clamp(); update()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (moved) saveWindow() else view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { if (moved) saveWindow(); true }
                else -> false
            }
        }
        handle.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = context.dp(8)
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (resizing) layout.width -= step else layout.x -= step
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (resizing) layout.width += step else layout.x += step
                KeyEvent.KEYCODE_DPAD_UP -> if (resizing) layout.height -= step else layout.y -= step
                KeyEvent.KEYCODE_DPAD_DOWN -> if (resizing) layout.height += step else layout.y += step
                else -> return@setOnKeyListener false
            }
            clamp(); update(); saveWindow(); true
        }
    }

    private fun params() = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
    }
    private fun update() {
        if (attached) runCatching { windows.updateViewLayout(root, layout) }.onFailure { onUnavailable() }
    }
    private fun restoreGeometry() = with(context) {
        val saved = preferences.window(); val size = screenSize()
        layout.width = dp(saved?.widthDp ?: 340); layout.height = dp(saved?.heightDp ?: 244)
        layout.x = saved?.let { ((size.first - layout.width) * it.xFraction).toInt() } ?: dp(24)
        layout.y = saved?.let { ((size.second - layout.height) * it.yFraction).toInt() } ?: dp(305)
        clamp()
    }
    private fun clamp() = with(context) {
        val size = screenSize(); val maxWidth = (size.first - dp(16)).coerceAtLeast(1)
        val maxHeight = (size.second - dp(96)).coerceAtLeast(1)
        layout.width = layout.width.coerceIn(minOf(dp(272), maxWidth), maxWidth)
        layout.height = layout.height.coerceIn(minOf(dp(158), maxHeight), maxHeight)
        layout.x = layout.x.coerceIn(dp(8), (size.first - layout.width - dp(8)).coerceAtLeast(dp(8)))
        layout.y = layout.y.coerceIn(dp(8), (size.second - layout.height - dp(64)).coerceAtLeast(dp(8)))
    }
    private fun saveWindow() {
        if (normalGeometry != null) return
        val size = screenSize(); val density = context.resources.displayMetrics.density
        preferences.saveWindow(SavedWindow(
            (layout.x.toFloat() / (size.first - layout.width).coerceAtLeast(1)).coerceIn(0f, 1f),
            (layout.y.toFloat() / (size.second - layout.height).coerceAtLeast(1)).coerceIn(0f, 1f),
            (layout.width / density).toInt(), (layout.height / density).toInt()))
    }
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30)
        windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    else android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
}
