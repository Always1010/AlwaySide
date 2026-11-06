package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

internal data class SettingsMultiChoice(
    val checkedIndices: Set<Int>,
    val onBlockedClick: (Int) -> Unit,
    val onSave: (Set<Int>) -> Boolean,
)

/** Settings choices keep their full descriptions; long lists scroll within the sheet. */
internal class SettingsChoiceSheet(
    context: Context,
    title: String,
    values: List<String>,
    checkedIndex: Int = -1,
    descriptions: Map<Int, String> = emptyMap(),
    disabledIndices: Set<Int> = emptySet(),
    multiChoice: SettingsMultiChoice? = null,
    onSelected: (Int) -> Boolean = { true },
) : BottomSheetDialog(context) {
    init {
        val selected = multiChoice?.checkedIndices?.toMutableSet() ?: mutableSetOf<Int>()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(16))
            setBackgroundColor(BACKGROUND)
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(4), context.dp(4), 0)
            addView(context.label(title, 16f).apply { textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.iconButton(R.drawable.ic_line_close, "关闭") { cancel() })
        }, LinearLayout.LayoutParams(-1, -2))

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        values.forEachIndexed { index, value ->
            if (index > 0) rows.addView(View(context).apply { setBackgroundColor(BORDER) },
                LinearLayout.LayoutParams(-1, context.dp(1)).apply {
                    leftMargin = context.dp(16); rightMargin = context.dp(16)
                })
            val name = value.substringBefore('\n')
            val description = descriptions[index] ?: value.substringAfter('\n', "")
            rows.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(48)
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                isSaveEnabled = false
                isEnabled = multiChoice != null || index !in disabledIndices
                fun blocked() = index in disabledIndices && index !in selected
                fun checked() = if (multiChoice != null) index in selected else index == checkedIndex
                alpha = if (blocked()) 0.38f else 1f
                background = context.touchFeedback()
                isClickable = true; isFocusable = true
                contentDescription = listOf(name, description).filter(String::isNotBlank).joinToString("，")
                if (multiChoice != null || checkedIndex >= 0) accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = if (multiChoice != null) "android.widget.CheckBox" else "android.widget.RadioButton"
                        info.isCheckable = !blocked(); info.isChecked = checked()
                    }
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(context.label(name, 15f).apply { textSize = 15f })
                    if (description.isNotBlank()) addView(context.label(description, 13f, true).apply {
                        textSize = 13f; setLineSpacing(context.dp(2).toFloat(), 1f)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val check = ImageView(context).apply {
                    setImageDrawable(context.prototypeIcon(R.drawable.ic_line_check))
                    imageTintList = ColorStateList.valueOf(ACCENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = if (checked()) View.VISIBLE else View.GONE
                }
                addView(check, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { leftMargin = context.dp(12) })
                setOnClickListener {
                    if (multiChoice != null) {
                        if (blocked()) multiChoice.onBlockedClick(index)
                        else {
                            if (!selected.remove(index)) selected.add(index)
                            check.visibility = if (checked()) View.VISIBLE else View.GONE
                            alpha = if (blocked()) 0.38f else 1f
                            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                        }
                    } else if (index !in disabledIndices && onSelected(index)) dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenLimit = (resources.displayMetrics.heightPixels * 0.65f).toInt()
                val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) screenLimit
                    else minOf(screenLimit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            isSaveEnabled = false; addView(rows)
        }, LinearLayout.LayoutParams(-1, -2, 1f))
        if (multiChoice != null) content.addView(LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), 0)
            addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "取消"; applyActionStyle(false)
                setOnClickListener { cancel() }
            }, LinearLayout.LayoutParams(-2, -2))
            addView(MaterialButton(context).apply {
                text = "保存"; applyActionStyle(true)
                setOnClickListener { if (multiChoice.onSave(selected.toSet())) dismiss() }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(content)
        setOnShowListener {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
