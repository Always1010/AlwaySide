package com.alwayside.android

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

internal class LaboratorySettingsUi(
    private val context: Context,
    private val content: LinearLayout,
    private var config: InteractionConfig,
    private val load: () -> InteractionConfig,
    private val save: ((InteractionConfig) -> InteractionConfig) -> Boolean,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val showChoiceSheet: (SettingsChoiceSheet) -> Unit,
    private val onTutorial: () -> Unit,
) {
    fun render() = with(context) {
        content.removeAllViews()
        content.addView(settingsSectionHeader("实验功能"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(label("长按滑动", 16f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_line_info, "长按滑动说明与体验", ::showHelp))
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
            rows.addView(settingsListRow("长按等待", seconds(config.longPressMillis)) {
                showOption("长按等待", (400..1500 step 100).toList(), 600, InteractionConfig::longPressMillis,
                    { value, selected -> value.copy(longPressMillis = selected) }, ::seconds)
            })
            rows.addView(settingsListRow("滑动距离", distance(config.slideDistanceDp)) {
                showOption("滑动距离", (16..40 step 4).toList(), 24, InteractionConfig::slideDistanceDp,
                    { value, selected -> value.copy(slideDistanceDp = selected) }, ::distance)
            })
            rows.addView(settingsListRow("动作圆大小", distance(config.actionCircleSizeDp)) {
                showOption("动作圆大小", (28..40 step 4).toList(), 32, InteractionConfig::actionCircleSizeDp,
                    { value, selected -> value.copy(actionCircleSizeDp = selected) }, ::distance)
            })
        }
        content.addView(settingsListGroup(rows), LinearLayout.LayoutParams(-1, -2))
        content.addView(settingsSectionHeader("点读"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        val readingRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun readingToggle(title: String, enabled: Boolean, help: String, update: (InteractionConfig, Boolean) -> InteractionConfig) {
            readingRows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4))
                addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_line_info, "查看${title}说明") {
                    showDialog(MaterialAlertDialogBuilder(context).setTitle("$title（实验功能）")
                        .setMessage(help).setPositiveButton("知道了", null))
                })
                addView(MaterialSwitch(this@with).apply {
                    isSaveEnabled = false; isChecked = enabled; contentDescription = title
                    var restoring = false
                    setOnCheckedChangeListener { control, checked ->
                        if (!restoring) {
                            if (save { update(it, checked) }) refresh()
                            else { restoring = true; control.isChecked = !checked; restoring = false }
                        }
                    }
                }, LinearLayout.LayoutParams(-2, dp(48)))
            })
        }
        readingToggle("聊天点读", config.chatPointReading,
            "长按选中文字，在选择菜单中点「点读」。成功保存后，原文留下淡色虚线标记，单击标记可重播，长按仍可选择和复制。消息旁的扬声器可查看点读片段。\n\n相同文字与朗读配置可跨对话复用音频。首次生成会将所选文字发送到语音服务，可能收费；语音由 AI 合成。旧标记保留原音色，音频清理后需主动重新生成。关闭点读会停止朗读，保留音频与标记。") { value, enabled -> value.copy(chatPointReading = enabled) }
        readingToggle("图片点读", config.imagePointReading,
            "默认关闭。开启后，可从截图预览进入图片点读，框选文字并主动识别，确认文字后朗读。普通看图手势不变。\n\n识别会把所选图片区域发送给当前图文问答服务，可能收费；不会修改翻译提示词或聊天记录。识别可能有误，请先核对。关闭会退出图片点读，保留已存音频。") { value, enabled -> value.copy(imagePointReading = enabled) }
        content.addView(settingsListGroup(readingRows), LinearLayout.LayoutParams(-1, -2))
        if (config.chatPointReading || config.imagePointReading)
            ReadingSettingsUi(context, content, showDialog, showChoiceSheet).render()
        SubtitleSettingsUi(context, content, showDialog, showChoiceSheet).render()
    }

    private fun refresh() {
        config = load()
        render()
    }

    private fun showHelp() {
        val dialog = showDialog(MaterialAlertDialogBuilder(context).setTitle("长按滑动（实验功能）")
            .setMessage("此功能仍在试用，交互可能调整。可随时关闭，恢复普通长按。\n\n开启后，按住悬浮球等待动作圆展开，向上、向下或向屏幕内侧滑动预选，松手执行。未滑出时原地松手执行长按；滑出后回到中心松手取消。持续按住不会自动执行。普通拖动仍用于移动悬浮球。\n\n滑动距离决定预选门槛，不必拖到动作圆中心。动作圆大小独立于悬浮球大小。系统无障碍长按时间更长时，以系统设置为准。\n\n方向默认未分配，松手不执行；开启后到「设置 → 快捷动作」绑定方向手势。关闭后保留已有绑定。")
            .setNegativeButton("关闭", null).setPositiveButton("体验", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); onTutorial() }
    }

    private fun showOption(
        title: String,
        values: List<Int>,
        default: Int,
        selected: (InteractionConfig) -> Int,
        update: (InteractionConfig, Int) -> InteractionConfig,
        format: (Int) -> String,
    ) {
        showChoiceSheet(SettingsChoiceSheet(context, title,
            values.map { format(it) + if (it == default) "（默认）" else "" },
            values.indexOf(selected(config))) { index ->
            if (save { update(it, values[index]) }) {
                refresh(); true
            } else false
        })
    }

    private fun seconds(millis: Int) = "${millis / 1000}.${millis % 1000 / 100} 秒"
    private fun distance(dp: Int) = "$dp dp"
}
