package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/** The same settings content is hosted in a dialog or a bounded overlay window. */
internal class SubtitleDisplaySettings(
    private val context: Context,
    private val reading: SubtitleReadingPreferences,
    private val overlay: SubtitleOverlayPreferences? = null,
    private val onPreview: (Int, Int, SubtitleOverlayTheme, Int) -> Unit,
    private val onClose: () -> Unit,
    private val onResetWindow: (() -> Unit)? = null,
) {
    private var font = overlay?.fontSize() ?: reading.fontSize()
    private var opacity = overlay?.opacity() ?: 90
    private var selectedTheme = overlay?.theme() ?: SubtitleOverlayTheme.SYSTEM
    private var custom = overlay?.customColor() ?: SubtitleAppearance.DEFAULT_CUSTOM
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(context.dp(22), context.dp(10), context.dp(22), context.dp(16))
    }
    val view = ScrollView(context).apply {
        isFillViewport = false; addView(content)
        background = context.outlinedCard(BACKGROUND, 22); clipToOutline = true
        elevation = context.dp(12).toFloat()
    }
    private val themeButtons = mutableMapOf<SubtitleOverlayTheme, LinearLayout>()
    private val swatches = mutableMapOf<SubtitleOverlayTheme, View>()
    private val sentenceButtons = mutableMapOf<SubtitleSegmentation, MaterialButton>()
    private var themeValue: android.widget.TextView? = null
    private var colorValue: android.widget.TextView? = null
    private var customRow: LinearLayout? = null
    private var colorControls: LinearLayout? = null
    private val sliders = mutableListOf<Pair<Slider, () -> Float>>()
    private var refreshing = false

    init {
        with(context) {
            content.addView(View(this).apply { background = card(BORDER, dp(3).toFloat()) },
                LinearLayout.LayoutParams(dp(30), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(if (overlay == null) "音频字幕显示设置" else "浮窗显示设置", 17f), LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(iconButton(R.drawable.ic_line_close, "收起设置") { finish(); onClose() }.compact(36))
            })
            if (overlay != null) addThemes()
            addSlider("字幕字号", 12f, 32f, { font.toFloat() }, { it.roundToInt().toString() }) { font = it.roundToInt(); preview() }
            if (overlay != null) addSlider("背景不透明度", 0f, 100f, { opacity.toFloat() }, { "${it.roundToInt()}%" }) {
                opacity = it.roundToInt(); preview()
            }
            val explanation = label("完整句会组合相邻片段，可能提前显示尚未读到的后半句。原始分段更贴近逐段播放。", 12f, true).apply {
                visibility = View.GONE; setPadding(0, dp(6), 0, dp(6))
            }
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("分句方式", 13f), LinearLayout.LayoutParams(0, dp(36), 1f))
                addView(iconButton(R.drawable.ic_line_info, "分句方式说明") {
                    explanation.visibility = if (explanation.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }.compact(32))
            })
            content.addView(LinearLayout(this).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3)); background = card(SURFACE, dp(10).toFloat())
                SubtitleSegmentation.entries.forEach { value ->
                    val button = textButton(value.title).apply {
                        setOnClickListener { save { reading.saveSegmentation(value) }; refreshChoices() }
                    }
                    sentenceButtons[value] = button
                    addView(button, LinearLayout.LayoutParams(0, dp(38), 1f))
                }
            })
            content.addView(explanation)
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(textButton("恢复默认外观").apply { setOnClickListener {
                    font = 17; opacity = 90; selectedTheme = SubtitleOverlayTheme.SYSTEM; custom = SubtitleAppearance.DEFAULT_CUSTOM
                    save { reading.saveSegmentation(SubtitleSegmentation.SENTENCE) }
                    persist(); refreshChoices(); refreshSliders(); preview()
                } }, LinearLayout.LayoutParams(0, dp(44), 1f))
                if (onResetWindow != null) addView(textButton("重置浮窗位置").apply {
                    setOnClickListener { onResetWindow.invoke() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            })
            content.addView(label("✓ 设置自动保存", 11f, true))
        }
        refreshChoices()
    }

    private fun addThemes() = with(context) {
        themeValue = label(selectedTheme.title, 12f).apply { setTextColor(ACCENT) }
        content.addView(row("颜色主题", requireNotNull(themeValue)))
        content.addView(LinearLayout(this).apply {
            SubtitleOverlayTheme.entries.forEach { value ->
                val swatch = View(this@with).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                swatches[value] = swatch
                val button = LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                    accessible(value.title); setPadding(dp(1), dp(5), dp(1), dp(5))
                    addView(swatch, LinearLayout.LayoutParams(dp(26), dp(26)).apply { bottomMargin = dp(5) })
                    addView(label(value.title, 11f).apply { textSize = 10f; gravity = Gravity.CENTER })
                    setOnClickListener { selectedTheme = value; persist(); refreshChoices(); preview() }
                }
                themeButtons[value] = button
                addView(button, LinearLayout.LayoutParams(0, dp(62), 1f))
            }
        })
        colorValue = label("", 12f, true)
        customRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("背景颜色", 13f), LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(colorValue)
            addView(iconButton(R.drawable.ic_line_edit, "选择自定义背景颜色") {
                colorControls?.visibility = if (colorControls?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }.compact(36))
        }
        content.addView(customRow)
        colorControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        content.addView(colorControls)
        listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (name, shift) ->
            addSlider(name, 0f, 255f, { (custom ushr shift and 255).toFloat() }, { it.roundToInt().toString() }, colorControls!!) { value ->
                custom = custom and (255 shl shift).inv() or (value.roundToInt() shl shift)
                refreshChoices(); preview()
            }
        }
    }

    private fun addSlider(title: String, min: Float, max: Float, value: () -> Float,
        format: (Float) -> String, parent: LinearLayout = content, changed: (Float) -> Unit) {
        val output = context.label(format(value()), 12f).apply { setTextColor(ACCENT) }
        parent.addView(row(title, output))
        val slider = Slider(context).apply {
            valueFrom = min; valueTo = max; stepSize = 1f; this.value = value()
            contentDescription = title; setLabelFormatter { format(it) }
            addOnChangeListener { _, selected, fromUser ->
                output.text = format(selected)
                if (fromUser && !refreshing) changed(selected)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) { persist() }
            })
        }
        sliders += slider to value
        parent.addView(slider, LinearLayout.LayoutParams(-1, context.dp(34)))
    }

    private fun row(title: String, value: View) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.dp(6), 0, 0)
        addView(context.label(title, 13f), LinearLayout.LayoutParams(0, context.dp(28), 1f)); addView(value)
    }

    private fun textButton(title: String) = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = title; applyActionStyle(false); textSize = 12f; minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0; insetTop = 0; insetBottom = 0
        setPadding(context.dp(5), 0, context.dp(5), 0); cornerRadius = context.dp(7)
    }

    private fun MaterialButton.compact(size: Int) = apply {
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
    }

    private fun refreshChoices() {
        themeValue?.text = selectedTheme.title
        val system = SubtitlePalette(SURFACE, INK, MUTED, BORDER, ACCENT, TONAL_SURFACE)
        themeButtons.forEach { (value, button) ->
            button.isSelected = selectedTheme == value
            button.background = card(if (selectedTheme == value) TONAL_SURFACE else android.graphics.Color.TRANSPARENT, context.dp(9).toFloat())
            swatches[value]?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (value == SubtitleOverlayTheme.SYSTEM) {
                    colors = intArrayOf(0xFFF7F9FC.toInt(), 0xFF17191E.toInt()); orientation = GradientDrawable.Orientation.LEFT_RIGHT
                } else setColor(SubtitleAppearance.palette(value, custom, system).background)
                setStroke(context.dp(if (selectedTheme == value) 2 else 1).coerceAtLeast(1), if (selectedTheme == value) ACCENT else BORDER)
            }
        }
        customRow?.visibility = if (selectedTheme == SubtitleOverlayTheme.CUSTOM) View.VISIBLE else View.GONE
        if (selectedTheme != SubtitleOverlayTheme.CUSTOM) colorControls?.visibility = View.GONE
        colorValue?.text = String.format(java.util.Locale.ROOT, "#%06X", custom and 0xFFFFFF)
        sentenceButtons.forEach { (value, button) ->
            val selected = reading.segmentation() == value
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) BACKGROUND else android.graphics.Color.TRANSPARENT)
            button.setTextColor(if (selected) ACCENT else MUTED)
        }
    }

    private fun refreshSliders() {
        refreshing = true
        sliders.forEach { (slider, value) -> slider.value = value() }
        refreshing = false
    }
    private fun preview() = onPreview(font, opacity, selectedTheme, custom)
    private fun persist() = save {
        if (overlay == null) {
            if (reading.fontSize() != font) reading.saveFontSize(font)
        } else {
            if (overlay.fontSize() != font) overlay.saveFontSize(font)
            if (overlay.opacity() != opacity) overlay.saveOpacity(opacity)
            if (overlay.theme() != selectedTheme) overlay.saveTheme(selectedTheme)
            if (overlay.customColor() != custom) overlay.saveCustomColor(custom)
        }
    }
    fun finish() { persist() }
    private fun save(action: () -> Unit) {
        runCatching(action).onFailure { Toast.makeText(context, it.message ?: "无法保存字幕设置", Toast.LENGTH_LONG).show() }
    }
}
