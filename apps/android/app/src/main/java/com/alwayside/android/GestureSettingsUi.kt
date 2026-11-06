package com.alwayside.android

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Each gesture assignment is an atomic save; cancelling a picker never leaves a partial draft. */
internal class GestureSettingsUi(
    private val context: Context,
    private val content: LinearLayout,
    private var config: InteractionConfig,
    private val load: () -> InteractionConfig,
    private val save: ((InteractionConfig) -> InteractionConfig) -> Boolean,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val onTutorial: () -> Unit,
) {
    private var feedback: Toast? = null

    fun render() = with(context) {
        content.removeAllViews()
        header("拓展选项")
        val extension = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        extension.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(label("长按滑动", 16f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_line_help, "长按滑动说明与体验", ::showHelp))
            addView(MaterialSwitch(this@with).apply {
                isChecked = config.longPressSlide; contentDescription = "长按滑动"
                var restoring = false
                setOnCheckedChangeListener { control, enabled ->
                    if (!restoring) {
                        if (save { it.copy(longPressSlide = enabled) }) refresh()
                        else {
                            restoring = true; control.isChecked = !enabled; restoring = false
                        }
                    }
                }
            }, LinearLayout.LayoutParams(-2, dp(48)))
        })
        if (config.longPressSlide) {
            extension.addView(settingsListRow("长按等待", seconds(config.longPressMillis)) {
                showTiming("长按等待", 400..1500, config.longPressMillis, true)
            })
            extension.addView(settingsListRow("滑动选择时间", seconds(config.slideWindowMillis)) {
                showTiming("滑动选择时间", 500..3000, config.slideWindowMillis, false)
            })
        }
        addGroup(extension)

        GestureBindings.functions.groupBy { it.group }.forEach { (group, functions) ->
            header(group, help = if (group == "屏幕") "点开功能可选择多个手势；已被互斥功能占用的手势不可选择。单击与双击需短暂等待以识别连续点击。" else null)
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            functions.forEach { function ->
                rows.addView(settingsListRow(function.title, functionSummary(function)) { showFunction(function) })
            }
            addGroup(rows)
        }
        header("绑定管理")
        addGroup(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsListRow("恢复默认绑定") { confirmReset() })
        })
    }

    private fun header(title: String, help: String? = null) = with(context) {
        content.addView(settingsSectionHeader(title, help, onHelp = {
            showDialog(MaterialAlertDialogBuilder(this).setTitle("功能绑定").setMessage(help).setPositiveButton("知道了", null))
        }), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(if (content.childCount == 0) 4 else 16); bottomMargin = dp(8)
        })
    }

    private fun addGroup(rows: View) = with(context) {
        content.addView(settingsListGroup(rows), LinearLayout.LayoutParams(-1, -2))
    }

    private fun refresh() {
        config = load()
        render()
    }

    private fun showHelp() {
        val dialog = showDialog(MaterialAlertDialogBuilder(context).setTitle("长按滑动")
            .setMessage("开启后，按住悬浮球等待提示，可向上、向下或向屏幕内侧滑动。方向位移成立立即执行；原地松手或持续不动直到选择时间结束，执行长按动作。普通拖动仍用于移动悬浮球。\n\n长按等待决定何时进入选择；滑动选择时间决定可选择多久。系统无障碍长按时间更长时，以系统设置为准。\n\n方向手势默认未分配，开启不会自动绑定。关闭后保留已有绑定，重新开启即可使用。")
            .setNegativeButton("关闭", null).setPositiveButton("体验", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); onTutorial() }
    }

    private fun showTiming(title: String, range: IntRange, initial: Int, hold: Boolean) {
        val values = range.step(100).toList()
        showDialog(MaterialAlertDialogBuilder(context).setTitle(title)
            .setSingleChoiceItems(values.map(::seconds).toTypedArray(), values.indexOf(initial)) { dialog, index ->
                if (save { if (hold) it.copy(longPressMillis = values[index]) else it.copy(slideWindowMillis = values[index]) }) {
                    dialog.dismiss(); refresh()
                } else {
                    val actual = runCatching { load() }.getOrDefault(config)
                    val savedIndex = values.indexOf(if (hold) actual.longPressMillis else actual.slideWindowMillis)
                    (dialog as? AlertDialog)?.listView?.apply {
                        clearChoices(); if (savedIndex >= 0) setItemChecked(savedIndex, true)
                    }
                }
            }.setNegativeButton("取消", null))
    }

    private fun showFunction(function: GestureFunction) = with(context) {
        val initialConfig = load()
        val initial = initialConfig.resolvedBindings()
        val gestures = GestureBindings.visibleGestures(initialConfig.longPressSlide).toSet()
        val selected = initial.filter { it.action == function.action && it.gesture in gestures }
            .mapTo(mutableSetOf()) { it.gesture }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gestures.forEach { gesture ->
            val conflicts = GestureBindings.conflicts(initial, function.action, gesture, function.states)
            val checkbox = MaterialCheckBox(this).apply {
                isChecked = gesture in selected
                isClickable = false; isFocusable = false; isSaveEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val name = label(gesture.title, 15f).apply { textSize = 15f }
            rows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48); setPadding(dp(16), 0, dp(16), 0)
                background = touchFeedback()
                isClickable = true; isFocusable = true; isSaveEnabled = false
                contentDescription = gesture.title
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                addView(checkbox, LinearLayout.LayoutParams(dp(48), dp(48)))
                fun updateCheck() {
                    val blocked = gesture !in selected && conflicts.isNotEmpty()
                    checkbox.isChecked = gesture in selected
                    checkbox.isEnabled = !blocked
                    name.alpha = if (blocked) 0.38f else 1f
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.CheckBox"
                        info.isCheckable = gesture in selected || conflicts.isEmpty()
                        info.isChecked = gesture in selected
                    }
                }
                updateCheck()
                setOnClickListener {
                    if (gesture !in selected && conflicts.isNotEmpty()) {
                        val owners = conflicts.map { GestureBindings.title(it.action) }.distinct()
                            .joinToString("、") { "「$it」" }
                        showFeedback("${gesture.title}已用于$owners")
                    } else {
                        if (!selected.remove(gesture)) selected.add(gesture)
                        updateCheck()
                        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val picker = showDialog(MaterialAlertDialogBuilder(this).setTitle(function.title)
            .setView(ScrollView(this).apply { isSaveEnabled = false; addView(rows) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null))
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Keep the draft local and preserve untouched and hidden mappings exactly as loaded.
            val latest = runCatching { load().resolvedBindings() }.getOrNull()
            if (latest != initial) {
                showFeedback("手势绑定已变化，请关闭后重新选择")
                return@setOnClickListener
            }
            val updated = runCatching {
                GestureBindings.updateSelection(initial, function.action, gestures, selected)
            }.getOrElse {
                showFeedback("手势已被占用，请重新选择")
                return@setOnClickListener
            }
            if (updated == initial) {
                picker.dismiss()
                return@setOnClickListener
            }
            val saved = save { value ->
                require(value.resolvedBindings() == initial) { "手势绑定已变化，请重新选择" }
                value.copy(bindings = GestureBindings.updateSelection(
                    value.resolvedBindings(), function.action, gestures, selected))
            }
            if (saved) { picker.dismiss(); refresh() }
        }
    }

    private fun confirmReset() {
        val dialog = showDialog(MaterialAlertDialogBuilder(context).setTitle("恢复默认绑定？")
            .setMessage("恢复基础手势绑定，方向手势恢复为未分配。长按滑动开关与时间保持不变。")
            .setNegativeButton("取消", null).setPositiveButton("恢复默认", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (save { it.copy(bindings = GestureBindings.defaults()) }) {
                dialog.dismiss(); refresh()
            }
        }
    }

    private fun functionSummary(function: GestureFunction): String {
        val gestures = config.resolvedBindings().filter {
            it.action == function.action && (!it.gesture.directional || config.longPressSlide)
        }.map { it.gesture }.toSet()
        return BubbleGesture.entries.filter { it in gestures }.joinToString("、") { it.title }
            .ifEmpty { "未分配手势" }
    }

    private fun showFeedback(message: String) {
        feedback?.cancel()
        feedback = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun seconds(millis: Int) = "${millis / 1000}.${millis % 1000 / 100} 秒"
}
