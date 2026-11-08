package com.alwayside.android

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/** A bounded, non-focusable window. Only this rectangle consumes touches in other apps. */
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
    private val windows = appContext.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        elevation = context.dp(8).toFloat(); clipToOutline = true
    }
    private val body = context.label("", 17f).apply {
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        setLineSpacing(0f, 1.2f)
    }
    private val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
    private val clock = context.label("", 11f, true)
    private val chinese: MaterialButton
    private val english: MaterialButton
    private val play: MaterialButton
    private var attached = false
    private var popup: PopupMenu? = null
    private var previousText: String? = null
    private var previousPlaying: Boolean? = null
    private var previousSecond = -1
    private var previousFont = -1
    private var previousOpacity = -1
    private val layout = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        setTitle("AlwaySide 音频字幕")
    }

    init {
        with(context) {
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val handle = smallIcon(R.drawable.ic_line_subtitles, "拖动字幕浮窗；点按打开完整字幕", onOpen)
            header.addView(handle); installDrag(handle, resizing = false)
            fun language(title: String, value: SubtitleDisplay) = MaterialButton(this, null,
                androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = title; applyActionStyle(false); minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); isCheckable = true
                accessible("显示$title 字幕")
                setOnClickListener { save { preferences.saveMode(SubtitleOverlayState.toggleLanguage(preferences.mode(), value)) } }
            }
            chinese = language("中文", SubtitleDisplay.CHINESE)
            english = language("英文", SubtitleDisplay.ENGLISH)
            header.addView(chinese, LinearLayout.LayoutParams(dp(48), dp(40)))
            header.addView(english, LinearLayout.LayoutParams(dp(48), dp(40)))
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            play = smallIcon(R.drawable.ic_line_pause, "暂停回听", onPlayback)
            header.addView(play)
            header.addView(smallIcon(R.drawable.ic_line_more, "字幕浮窗选项") { showMenu(it = header) })
            header.addView(smallIcon(R.drawable.ic_line_close, "关闭字幕浮窗，音频继续播放", onClose))
            root.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(clock, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                val resize = smallIcon(R.drawable.ic_resize, "拖动调整字幕浮窗大小；点按切换大小") {
                    val expanded = layout.height < dp(280)
                    layout.width = dp(if (expanded) 380 else 320)
                    layout.height = dp(if (expanded) 300 else 216)
                    clamp(); update(); saveWindow()
                }
                addView(resize); installDrag(resize, resizing = true)
            }, LinearLayout.LayoutParams(-1, dp(40)))
            val saved = preferences.window()
            layout.width = dp(saved?.widthDp ?: 320)
            layout.height = dp(saved?.heightDp ?: 216)
            val size = screenSize()
            layout.x = ((size.first - layout.width) * (saved?.xFraction ?: .5f)).toInt()
            layout.y = ((size.second - layout.height) * (saved?.yFraction ?: .72f)).toInt()
            clamp()
        }
    }

    fun show() {
        root.visibility = View.INVISIBLE
        windows.addView(root, layout)
        attached = true
    }

    fun close() {
        runCatching { popup?.dismiss() }; popup = null
        if (attached) { attached = false; runCatching { windows.removeViewImmediate(root) } }
    }

    fun render(text: String, mode: SubtitleDisplay, playing: Boolean, position: Int, duration: Int) {
        if (!attached) return
        if (text != previousText) {
            body.text = text; previousText = text
            scroll.scrollTo(0, 0)
        }
        // Keep controls available through pauses and silence; only the caption text changes.
        root.visibility = View.VISIBLE
        chinese.isChecked = mode != SubtitleDisplay.ENGLISH
        english.isChecked = mode != SubtitleDisplay.CHINESE
        chinese.setTextColor(if (chinese.isChecked) ACCENT else MUTED)
        english.setTextColor(if (english.isChecked) ACCENT else MUTED)
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
        val font = preferences.fontSize()
        if (font != previousFont) { previousFont = font; body.textSize = font.toFloat() }
        val opacity = preferences.opacity()
        if (opacity != previousOpacity) {
            previousOpacity = opacity
            root.background = context.outlinedCard((SURFACE and 0x00FFFFFF) or ((255 * opacity / 100) shl 24), 12)
        }
    }

    private fun smallIcon(resource: Int, description: String, action: () -> Unit) =
        context.iconButton(resource, description, action).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        }

    private fun showMenu(it: View) {
        popup?.dismiss()
        popup = PopupMenu(context, it).apply {
            menu.add("打开完整字幕").setOnMenuItemClickListener { onOpen(); true }
            val fonts = menu.addSubMenu("字幕字号")
            for ((label, size) in listOf("小" to 14, "中" to 17, "大" to 20)) {
                fonts.add(label).apply {
                    isCheckable = true; isChecked = preferences.fontSize() == size
                    setOnMenuItemClickListener { save { preferences.saveFontSize(size) }; true }
                }
            }
            val backgrounds = menu.addSubMenu("背景不透明度")
            for (opacity in listOf(70, 90, 100)) {
                backgrounds.add("$opacity%").apply {
                    isCheckable = true; isChecked = preferences.opacity() == opacity
                    setOnMenuItemClickListener { save { preferences.saveOpacity(opacity) }; true }
                }
            }
            menu.add("停止播放").setOnMenuItemClickListener { ChatAudioPlayback.get(context).stop(); true }
            show()
        }
    }

    private fun save(action: () -> Unit) {
        runCatching(action).onSuccess { onChanged() }.onFailure {
            onChanged()
            Toast.makeText(context, it.message ?: "无法保存字幕浮窗设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun installDrag(handle: View, resizing: Boolean) {
        var downX = 0f; var downY = 0f
        var initialX = 0; var initialY = 0; var initialWidth = 0; var initialHeight = 0
        var moved = false
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialX = layout.x; initialY = layout.y; initialWidth = layout.width; initialHeight = layout.height
                    moved = false; true
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
    }

    private fun update() {
        if (attached) runCatching { windows.updateViewLayout(root, layout) }.onFailure { onUnavailable() }
    }

    private fun clamp() = with(context) {
        val size = screenSize()
        val maxWidth = (size.first - dp(16)).coerceAtLeast(1)
        val maxHeight = (size.second - dp(96)).coerceAtLeast(1)
        layout.width = layout.width.coerceIn(minOf(dp(272), maxWidth), maxWidth)
        layout.height = layout.height.coerceIn(minOf(dp(152), maxHeight), maxHeight)
        layout.x = layout.x.coerceIn(dp(8), (size.first - layout.width - dp(8)).coerceAtLeast(dp(8)))
        layout.y = layout.y.coerceIn(dp(8), (size.second - layout.height - dp(64)).coerceAtLeast(dp(8)))
    }

    private fun saveWindow() {
        val size = screenSize()
        val density = context.resources.displayMetrics.density
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
