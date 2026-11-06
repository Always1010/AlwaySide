package com.alwayside.android

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Each gesture assignment is an atomic save; cancelling a picker never leaves a partial draft. */
internal class GestureSettingsUi(
    private val context: Context,
    private val content: LinearLayout,
    private var config: InteractionConfig,
    private val load: () -> InteractionConfig,
    private val save: ((InteractionConfig) -> InteractionConfig) -> Boolean,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val showChoiceSheet: (SettingsChoiceSheet) -> Unit,
) {
    private var feedback: Toast? = null

    fun render() = with(context) {
        content.removeAllViews()
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

    private fun showFunction(function: GestureFunction) {
        val initialConfig = load()
        val initial = initialConfig.resolvedBindings()
        val gestures = GestureBindings.visibleGestures(initialConfig.longPressSlide)
        val visible = gestures.toSet()
        val selected = gestures.indices.filterTo(mutableSetOf()) { index ->
            initial.any { it.action == function.action && it.gesture == gestures[index] }
        }
        val conflicts = gestures.map { gesture ->
            GestureBindings.conflicts(initial, function.action, gesture, function.states)
        }
        val multiChoice = SettingsMultiChoice(selected, onBlockedClick = { index ->
            val owners = conflicts[index].map { GestureBindings.title(it.action) }.distinct()
                .joinToString("、") { "「$it」" }
            showFeedback("${gestures[index].title}已用于$owners")
        }, onSave = saveSelection@{ indices ->
            // Keep the draft local and preserve untouched and hidden mappings exactly as loaded.
            val latest = runCatching { load().resolvedBindings() }.getOrNull()
            if (latest != initial) {
                showFeedback("手势绑定已变化，请关闭后重新选择")
                return@saveSelection false
            }
            val chosen = indices.mapTo(mutableSetOf()) { gestures[it] }
            val updated = runCatching {
                GestureBindings.updateSelection(initial, function.action, visible, chosen)
            }.getOrElse {
                showFeedback("手势已被占用，请重新选择")
                return@saveSelection false
            }
            if (updated == initial) return@saveSelection true
            val saved = save { value ->
                require(value.resolvedBindings() == initial) { "手势绑定已变化，请重新选择" }
                value.copy(bindings = GestureBindings.updateSelection(
                    value.resolvedBindings(), function.action, visible, chosen))
            }
            if (saved) refresh()
            saved
        })
        showChoiceSheet(SettingsChoiceSheet(context, function.title, gestures.map { it.title },
            disabledIndices = conflicts.indices.filterTo(mutableSetOf()) { conflicts[it].isNotEmpty() },
            multiChoice = multiChoice))
    }

    private fun confirmReset() {
        val dialog = showDialog(MaterialAlertDialogBuilder(context).setTitle("恢复默认绑定？")
            .setMessage("恢复基础手势绑定，方向手势恢复为未分配。长按滑动开关、等待、距离与动作圆大小保持不变。")
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
}
