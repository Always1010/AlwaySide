package com.alwayside.android

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal class SubtitleSettingsUi(private val context: Context, private val parent: LinearLayout,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val showChoice: (SettingsChoiceSheet) -> Unit) {
    private val preferences = SubtitlePreferences(context)
    private var rows: LinearLayout? = null

    fun render(): Unit = with(context) {
        val group = rows ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this@SubtitleSettingsUi.parent.addView(settingsSectionHeader("音频字幕", help = "音频字幕说明", onHelp = {
                help("音频字幕（实验功能）", "Whisper 默认关闭。开启后，在录音回听面板主动制作字幕；不会自动处理已有或新录音。\n\nWhisper 转写音频并返回片段时间戳，再由所选文本模型翻译。原文和译文分别保存为 SRT，可在回听中切换双语、中文、英文或关闭。\n\n仅复用 API 管理中已有的 OpenAI 官方 Key，地址须为 https://api.openai.com/v1。生成时会将所选录音及转写文字发送给 OpenAI，可能收费。翻译模型独立于日常问答配置；保存设置和播放已生成字幕不联网。\n\n关闭 Whisper 会取消生成任务，保留已生成字幕。whisper-1 已公告于 2027-02-26 停用，届时可能无法继续制作新字幕。")
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
            this@SubtitleSettingsUi.parent.addView(settingsListGroup(this), LinearLayout.LayoutParams(-1, -2))
            rows = this
        }
        group.removeAllViews()
        SubtitleMethod.entries.forEach { method ->
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4))
                addView(label(method.title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_line_info, "查看${method.title}说明") {
                    help(method.title, "使用 whisper-1 返回的片段时间戳，再由所选文本模型翻译。不提供逐词高亮。whisper-1 已公告于 2027-02-26 停用。\n\n默认关闭。开启后需在录音回听中主动生成。录音和文字将发送至 OpenAI，可能收费。关闭会取消生成任务并保留已生成结果。")
                })
                addView(MaterialSwitch(this@with).apply {
                    isSaveEnabled = false; isChecked = preferences.enabled(method); contentDescription = method.title
                    var restoring = false
                    setOnCheckedChangeListener { control, enabled ->
                        if (!restoring) runCatching { preferences.setEnabled(method, enabled) }
                            .onSuccess { render() }.onFailure {
                                restoring = true; control.isChecked = !enabled; restoring = false
                                toast("无法保存字幕设置，请重试")
                            }
                    }
                }, LinearLayout.LayoutParams(-2, dp(48)))
            })
        }
        if (!preferences.anyEnabled()) return@with
        val catalog = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
        val key = catalog?.credentials?.firstOrNull { it.id == preferences.credentialId() }
        val valid = catalog != null && runCatching { SubtitleConfiguration.connection(catalog, preferences.credentialId()) }.isSuccess
        group.addView(settingsListRow("OpenAI Key", if (valid) key?.name.orEmpty() else "未选择或不可用", inlineValue = true) {
            chooseCredential()
        })
        group.addView(settingsListRow("翻译模型", preferences.translationModel().ifBlank { "未填写" }, inlineValue = true) {
            chooseModel()
        })
        group.addView(settingsListRow("字幕显示", preferences.display().title, inlineValue = true) {
            showChoice(SettingsChoiceSheet(context, "字幕显示", SubtitleDisplay.entries.map { it.title },
                SubtitleDisplay.entries.indexOf(preferences.display())) { index ->
                save { preferences.saveDisplay(SubtitleDisplay.entries[index]) }
            })
        })
    }

    private fun chooseCredential() {
        val catalog = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
        if (catalog == null) { toast("无法读取 API 管理，请先检查 OpenAI Key"); return }
        val choices = catalog.credentials.filter {
            runCatching { SubtitleConfiguration.connection(catalog, it.id) }.isSuccess
        }
        if (choices.isEmpty()) {
            toast("请先在 API 管理中添加使用官方地址的 OpenAI Key"); return
        }
        showChoice(SettingsChoiceSheet(context, "字幕 OpenAI Key", choices.map { credential ->
            listOf(catalog.providers.firstOrNull { it.id == credential.providerId }?.name, credential.name)
                .filterNotNull().joinToString(" · ")
        }, choices.indexOfFirst { it.id == preferences.credentialId() }) { index ->
            save { preferences.selectCredential(choices[index].id) }
        })
    }

    private fun chooseModel() {
        val recommended = preferences.recommendations()
        val models = recommended.models
        showChoice(SettingsChoiceSheet(context, "字幕翻译模型", models.map {
            it.name + if (it.id == recommended.defaultModelId) "（默认）" else ""
        } + "手动填写", models.indexOfFirst { it.id == preferences.translationModel() }) { index ->
            if (index == models.size) { editModel(); true }
            else save { preferences.saveTranslationModel(models[index].id) }
        })
    }

    private fun editModel(): Unit = with(context) {
        val input = TextInputLayout(this).apply {
            hint = "翻译模型 ID"; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val editor = TextInputEditText(input.context).apply {
            isSaveEnabled = false; setSingleLine(true); setText(preferences.translationModel())
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        input.addView(editor)
        val dialog = showDialog(MaterialAlertDialogBuilder(this).setTitle("字幕翻译模型").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { preferences.saveTranslationModel(editor.text.toString().trim()) }
                .onSuccess { dialog.dismiss(); render() }
                .onFailure { input.error = it.message ?: "无法保存字幕设置" }
        }
    }

    private fun save(action: () -> Unit) = runCatching(action).onSuccess { render() }
        .onFailure { toast(it.message ?: "无法保存字幕设置") }.isSuccess
    private fun help(title: String, message: String) {
        showDialog(MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setPositiveButton("知道了", null))
    }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
