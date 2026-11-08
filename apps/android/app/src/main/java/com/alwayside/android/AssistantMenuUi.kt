package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import com.google.android.material.checkbox.MaterialCheckBox
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun AssistantMenuAction.iconResource(recording: Boolean = false): Int = when (this) {
    AssistantMenuAction.SCREEN -> R.drawable.ic_line_scan
    AssistantMenuAction.CROP -> R.drawable.ic_line_crop
    AssistantMenuAction.AUDIO -> if (recording) R.drawable.ic_line_stop else R.drawable.ic_line_audio
    AssistantMenuAction.LAST -> R.drawable.ic_line_text
    AssistantMenuAction.PLAY -> R.drawable.ic_line_play
    AssistantMenuAction.HISTORY -> R.drawable.ic_line_history
    AssistantMenuAction.SETTINGS -> R.drawable.ic_line_settings
    AssistantMenuAction.STOP -> R.drawable.ic_line_power
    AssistantMenuAction.CANCEL_AUDIO -> R.drawable.ic_line_close
    AssistantMenuAction.APPEARANCE -> R.drawable.ic_line_sliders
}

/** Layout and native hit testing share the same transform, including inside a ScrollView. */
internal class AssistantMenuScaleLayout(context: Context, scale: Float) : FrameLayout(context) {
    var contentScale = scale
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val baseWidth = (width / contentScale).roundToInt().coerceAtLeast(1)
        child.measure(View.MeasureSpec.makeMeasureSpec(baseWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(ceil(child.measuredHeight * contentScale).toInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0).apply {
            pivotX = 0f; pivotY = 0f; scaleX = contentScale; scaleY = contentScale
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }
}

internal fun MenuAppearance.panelColor(): Int =
    ((backgroundColor ?: SURFACE) and 0x00FFFFFF) or (panelAlpha shl 24)

/** Entire cell is the touch target; children form one accessible button. */
internal class AssistantMenuTileView(context: Context, resource: Int, title: String,
    description: String = title, available: Boolean = true, onClick: () -> Unit) : LinearLayout(context) {
    init { with(context) {
        orientation = VERTICAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(dp(3), dp(8), dp(3), dp(8)); minimumHeight = dp(88)
        background = touchFeedback(); isFocusable = true; isClickable = true; isEnabled = available
        accessible(description); alpha = if (available) 1f else .4f
        addView(ImageView(this).apply {
            setImageDrawable(prototypeIcon(resource)); imageTintList = ColorStateList.valueOf(INK)
            background = card(BORDER, dp(24).toFloat()); setPadding(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(48), dp(48)))
        addView(label(title, 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(-1, -2))
        setOnClickListener { onClick() }
    } }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}

/** Small native grid: row height follows the tallest wrapped label, including large fonts. */
internal fun Context.assistantMenuGrid(cells: List<View>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    cells.chunked(3).forEach { rowCells ->
        addView(LinearLayout(context).apply {
            // Real cells must supply the row's height, including when spacers complete a row.
            rowCells.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(3 - rowCells.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

/** Shared options remain checkboxes, never configurable tool tiles. No preference access here. */
internal class AssistantMenuOptionsViews(context: Context, webSearch: Boolean, useDefaultQuestion: Boolean,
    webSearchAvailable: Boolean, onWebSearchChanged: (Boolean) -> Unit,
    onDefaultQuestionChanged: (Boolean) -> Unit) {
    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    init { with(context) {
        fun option(title: String, checked: Boolean, description: String, enabled: Boolean = true,
            changed: (Boolean) -> Unit) {
            view.addView(MaterialCheckBox(this).apply {
                text = title; isChecked = checked; textSize = 13f; setTextColor(INK); letterSpacing = 0f
                minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0); compoundDrawablePadding = dp(4)
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ACCENT, MUTED))
                accessible(title); tooltipText = description; isEnabled = enabled || checked
                setOnCheckedChangeListener { _, selected -> isEnabled = enabled || selected; changed(selected) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        option("联网搜索", webSearch, if (webSearchAvailable) "允许新悬浮会话搜索网页；关闭后仍需联网调用模型"
            else "当前模型或协议不支持搜索，可关闭已有选择", webSearchAvailable, onWebSearchChanged)
        option("使用默认提问方式", useDefaultQuestion, "关闭后展开输入框，补充问题再发送", changed = onDefaultQuestionChanged)
    } }
}

/** Same presentation for real overlays and local tutorials; callbacks supply every side effect. */
internal class AssistantMenuViews(context: Context, recording: Boolean = false, hasClip: Boolean = false,
    webSearch: Boolean = false, useDefaultQuestion: Boolean = true, webSearchAvailable: Boolean = true,
    items: List<AssistantMenuAction> = AssistantMenuAction.defaults,
    private var appearance: MenuAppearance = MenuAppearance(), private val displayScale: Float = 1f, onCustomize: () -> Unit = {},
    onWebSearchChanged: (Boolean) -> Unit = {}, onDefaultQuestionChanged: (Boolean) -> Unit = {},
    onAction: (AssistantMenuAction) -> Unit) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
        background = card(appearance.panelColor(), context.dp(appearance.cornerRadiusDp).toFloat())
        elevation = 0f; clipToOutline = true
        // Consume empty grid space instead of closing the overlay underneath it.
        isClickable = true
    }
    val view = AssistantMenuScaleLayout(context, appearance.scale * displayScale).apply {
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }
    val buttons = linkedMapOf<AssistantMenuAction, View>()
    val preferredWidthDp: Int get() =
        (252 * content.resources.configuration.fontScale.coerceAtLeast(1f) * appearance.scale * displayScale).roundToInt()

    fun applyAppearance(next: MenuAppearance) {
        appearance = next
        content.background = card(next.panelColor(), content.context.dp(next.cornerRadiusDp).toFloat())
        view.contentScale = next.scale * displayScale
    }
    init { with(context) {
        val cells = items.distinct().map { action ->
            val title = if (action == AssistantMenuAction.AUDIO && recording) "结束录音" else action.title
            val available = when (action) {
                AssistantMenuAction.PLAY -> hasClip
                AssistantMenuAction.CANCEL_AUDIO -> recording
                AssistantMenuAction.SCREEN, AssistantMenuAction.CROP -> !recording
                else -> true
            }
            val description = when {
                action == AssistantMenuAction.AUDIO && recording -> "结束录音并处理"
                action == AssistantMenuAction.PLAY && !hasClip -> "回听上一段，暂无可回听的录音"
                action == AssistantMenuAction.CANCEL_AUDIO -> if (recording) "取消并丢弃录音" else "取消录音，当前没有录音"
                !available -> "$title，请先结束或取消录音"
                action == AssistantMenuAction.LAST -> "最近悬浮对话"
                else -> title
            }
            AssistantMenuTileView(this, action.iconResource(recording), title, description, available) {
                onAction(action)
            }.also { buttons[action] = it }
        }
        content.addView(assistantMenuGrid(cells.ifEmpty {
            listOf(AssistantMenuTileView(this, R.drawable.ic_line_plus, "添加工具", onClick = onCustomize))
        }), LinearLayout.LayoutParams(-1, -2))
        content.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(8), dp(8), dp(8), dp(6))
        })
        content.addView(AssistantMenuOptionsViews(this, webSearch, useDefaultQuestion, webSearchAvailable,
            onWebSearchChanged, onDefaultQuestionChanged).view, LinearLayout.LayoutParams(-1, -2))
    } }
}
