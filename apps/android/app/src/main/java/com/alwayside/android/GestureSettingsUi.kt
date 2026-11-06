package com.alwayside.android

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
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
    fun render() = with(context) {
        content.removeAllViews()
        header("扩展手势")
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
            header(group, help = if (group == "屏幕") "每个功能可绑定多个手势，点开功能后逐项设置。公共功能可分别绑定未录音、录音中或两种状态。\n\n同一状态的同一手势只执行一个功能；替换前会确认。单击与双击需短暂等待以识别连续点击。" else null)
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            functions.forEach { function ->
                rows.addView(settingsListRow(function.title, functionSummary(function)) { showFunction(function) })
            }
            addGroup(rows)
        }
        header("绑定管理")
        addGroup(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsListRow("清除全部绑定") { confirmReset(false) })
            addView(settingsListRow("恢复默认绑定") { confirmReset(true) })
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
            .setMessage("开启后，按住悬浮球等待提示，可向上、向下或向屏幕内侧滑动。方向位移成立立即执行；原地松手或持续不动直到选择时间结束，执行长按动作。普通拖动仍用于移动悬浮球。\n\n长按等待决定何时进入选择；滑动选择时间决定可选择多久。系统无障碍长按时间更长时，以系统设置为准。\n\n关闭后保留方向手势绑定，重新开启即可使用。")
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
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderRows() {
            rows.removeAllViews()
            BubbleGesture.entries.forEach { gesture ->
                val enabled = !gesture.directional || config.longPressSlide
                rows.addView(settingsListRow(gesture.title, gestureSummary(function, gesture), showArrow = enabled,
                    onClick = if (enabled) ({ showStates(function, gesture) { refresh(); renderRows() } }) else null).apply {
                    isEnabled = enabled; alpha = if (enabled) 1f else .45f
                })
            }
        }
        config = load(); renderRows()
        showDialog(MaterialAlertDialogBuilder(this).setTitle(function.title)
            .setView(ScrollView(this).apply { addView(rows) }).setPositiveButton("完成", null))
    }

    private fun showStates(function: GestureFunction, gesture: BubbleGesture, onSaved: () -> Unit) {
        val current = load().resolvedBindings()
        val own = current.filter { it.action == function.action && it.gesture == gesture }.map { it.recording }.toSet()
        val states = listOf(emptySet<Boolean>()) + when (function.states) {
            setOf(false, true) -> listOf(setOf(false), setOf(true), setOf(false, true))
            else -> listOf(function.states)
        }
        lateinit var picker: AlertDialog
        fun restoreSelection() {
            val actual = runCatching { load().resolvedBindings() }.getOrDefault(current)
                .filter { it.action == function.action && it.gesture == gesture }.map { it.recording }.toSet()
            val selectedIndex = states.indexOf(actual)
            picker.listView.apply { clearChoices(); if (selectedIndex >= 0) setItemChecked(selectedIndex, true) }
        }
        picker = showDialog(MaterialAlertDialogBuilder(context).setTitle("${function.title} · ${gesture.title}")
            .setSingleChoiceItems(states.map(::stateTitle).toTypedArray(), states.indexOf(own)) { _, index ->
                val selected = states[index]
                val latest = load().resolvedBindings()
                val conflicts = GestureBindings.conflicts(latest, function.action, gesture, selected)
                val commit = {
                    val saved = save { value ->
                        // A confirmation must never overwrite mappings changed while it was open.
                        require(value.resolvedBindings() == latest) { "手势绑定已改变，请重新选择" }
                        val remaining = latest.filterNot {
                            it.action == function.action && it.gesture == gesture
                        }
                        value.copy(bindings = if (selected.isEmpty()) remaining
                            else GestureBindings.assign(remaining, function.action, gesture, selected))
                    }
                    if (saved) { picker.dismiss(); onSaved() } else restoreSelection()
                    saved
                }
                if (conflicts.isEmpty()) commit()
                else {
                    val summary = conflicts.joinToString("\n") {
                        "${if (it.recording) "录音中" else "未录音"}：${GestureBindings.title(it.action)}"
                    }
                    val confirmation = showDialog(MaterialAlertDialogBuilder(context).setTitle("替换已有绑定？")
                        .setMessage("${gesture.title}在以下状态已绑定：\n$summary\n\n替换为「${function.title}」，其他状态的绑定保留。")
                        .setNegativeButton("取消") { _, _ -> restoreSelection() }
                        .setOnCancelListener { restoreSelection() }.setPositiveButton("替换", null))
                    confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (commit()) confirmation.dismiss()
                    }
                }
            }.setNegativeButton("取消", null))
    }

    private fun confirmReset(defaults: Boolean) {
        val title = if (defaults) "恢复默认绑定" else "清除全部绑定"
        val dialog = showDialog(MaterialAlertDialogBuilder(context).setTitle("$title？")
            .setMessage(if (defaults) "仅恢复所有功能的默认手势绑定。长按滑动开关、时间与其他设置保持不变。"
                else "移除所有功能在未录音与录音中的手势绑定，包括已停用的方向手势。长按滑动开关、时间与其他设置保持不变。")
            .setNegativeButton("取消", null).setPositiveButton(if (defaults) "恢复默认" else "清除", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (save { it.copy(bindings = if (defaults) InteractionConfig().resolvedBindings() else emptyList()) }) {
                dialog.dismiss(); refresh()
            }
        }
    }

    private fun functionSummary(function: GestureFunction): String {
        val bindings = config.resolvedBindings().filter { it.action == function.action }
        if (bindings.isEmpty()) return "未绑定"
        return bindings.groupBy { it.gesture }.entries.joinToString("、") { (gesture, values) ->
            val states = values.map { it.recording }.toSet()
            val details = buildList {
                if (function.states.size > 1 || states != function.states) add(stateTitle(states))
                if (gesture.directional && !config.longPressSlide) add("已停用")
            }
            gesture.title + if (details.isEmpty()) "" else "（${details.joinToString("，")}）"
        }
    }

    private fun gestureSummary(function: GestureFunction, gesture: BubbleGesture): String {
        val states = config.resolvedBindings().filter { it.action == function.action && it.gesture == gesture }
            .map { it.recording }.toSet()
        val value = if (function.states.size == 1 && states == function.states) "已绑定" else stateTitle(states)
        return value + if (gesture.directional && !config.longPressSlide) " · 已停用" else ""
    }

    private fun stateTitle(states: Set<Boolean>) = when (states) {
        setOf(false, true) -> "两种状态"
        setOf(true) -> "录音中"
        setOf(false) -> "未录音"
        else -> "未绑定"
    }

    private fun seconds(millis: Int) = "${millis / 1000}.${millis % 1000 / 100} 秒"
}
