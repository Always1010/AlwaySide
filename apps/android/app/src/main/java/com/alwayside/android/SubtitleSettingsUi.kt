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
                help("音频字幕（实验功能）", "开启后，在录音回听面板点击制作字幕，生成原文和中文字幕。可切换双语、中文、英文或关闭显示。\n\n需选择 API 管理中使用官方地址的 OpenAI Key。制作时会将录音及转写文字发送到 OpenAI，可能收费；播放已有字幕不联网。\n\n关闭功能会取消制作，保留已有字幕。")
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
                    help(method.title, "为录音生成原文和中文字幕，按段显示，不提供逐词高亮。开启后需在录音回听中点击制作，不会自动上传录音。\n\n录音和转写文字会发送到 OpenAI，可能收费。关闭会取消制作，保留已有字幕。\n\nwhisper-1 已公告于 2027-02-26 停用，届时可能无法制作新字幕。")
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
        group.addView(toggle("音频提问同时生成字幕", preferences.questionSubtitles(),
            "默认关闭。开启后，新发起的音频处理使用 Whisper 字幕方案识别原文和时间轴，直接复用原文提问，不再调用日常转写服务。\n\n原文完成即可回答，中文字幕独立继续；回答失败可复用原文，翻译失败可在回听面板手动继续。取消识别会中断字幕任务；原文就绪后取消回答不会取消字幕翻译。\n\n目前按英语识别；使用本页选择的 OpenAI Key 和翻译模型，不使用日常转写卡片的模型、提示词和语言。录音会随字幕长期保留；音频、原文分别发送至所选字幕与问答服务，可能收费。已有对话原文不会自动改写。") {
            preferences.saveQuestionSubtitles(it)
        })
        val catalog = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
        val key = catalog?.credentials?.firstOrNull { it.id == preferences.credentialId() }
        val valid = catalog != null && runCatching { SubtitleConfiguration.connection(catalog, preferences.credentialId()) }.isSuccess
        group.addView(settingsListRow("OpenAI Key", if (valid) key?.name.orEmpty() else "未选择或不可用", inlineValue = true) {
            chooseCredential()
        })
        group.addView(settingsListRow("翻译模型", preferences.translationModel().ifBlank { "未填写" }, inlineValue = true) {
            chooseModel()
        })
        group.addView(toggle("分批翻译", preferences.batchedTranslation(),
            "默认关闭，将整段英文字幕一次发送给翻译模型，结合全文生成中文字幕，输出上限为 128000 tokens。\n\n开启后每批最多翻译 16 条，适合长字幕或输出容量较小的模型。已完成的译文会保留，继续时只翻译缺失条目。\n\n整段翻译失败不会自动拆批或重试；可开启分批翻译后手动继续。模型自身的输出限制仍然适用。") {
            preferences.saveBatchedTranslation(it)
        })
        group.addView(settingsListRow("字幕显示", preferences.display().title, inlineValue = true) {
            showChoice(SettingsChoiceSheet(context, "字幕显示", SubtitleDisplay.entries.map { it.title },
                SubtitleDisplay.entries.indexOf(preferences.display())) { index ->
                save { preferences.saveDisplay(SubtitleDisplay.entries[index]) }
            })
        })
    }

    private fun toggle(title: String, checked: Boolean, description: String, update: (Boolean) -> Unit): LinearLayout = with(context) {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4))
            addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_line_info, "查看${title}说明") { help(title, description) })
            addView(MaterialSwitch(this@with).apply {
                isSaveEnabled = false; isChecked = checked; contentDescription = title
                var restoring = false
                setOnCheckedChangeListener { control, enabled ->
                    if (!restoring && !save { update(enabled) }) {
                        restoring = true; control.isChecked = !enabled; restoring = false
                    }
                }
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }
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
