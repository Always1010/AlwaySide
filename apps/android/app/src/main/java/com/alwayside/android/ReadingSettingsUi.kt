package com.alwayside.android

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal class ReadingSettingsUi(private val context: Context, private val parent: LinearLayout,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val showChoice: (SettingsChoiceSheet) -> Unit) {
    private val preferences = ReadingPreferences(context)
    private var rows: LinearLayout? = null
    private val choices = ReadingChoicesUi(context, preferences, showDialog, showChoice, ::edit, ::render)
    fun render(): Unit = with(context) {
        val group = rows ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this@ReadingSettingsUi.parent.addView(settingsSectionHeader("朗读服务", help = "说明", onHelp = {
                showDialog(MaterialAlertDialogBuilder(context).setTitle("朗读服务")
                    .setMessage("选择 API 管理中已有的密钥，再选择模型和音色，也可手动填写。「从服务读取」会联网获取模型列表，列出的模型仍需实际试用。\n\n朗读会将文字发送到所选语音服务，可能收费；声音由 AI 合成。重播已保存音频和调整播放速度不产生新的语音请求。\n\n普通点读缓存上限为 256 MiB，收藏会话关联的音频不自动清理。\n\nOpenAI 已公告兼容选项中的旧模型于 2027-01-06 停用。")
                    .setPositiveButton("知道了", null))
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            this@ReadingSettingsUi.parent.addView(settingsListGroup(this), LinearLayout.LayoutParams(-1, -2))
            rows = this
            choices.bind(this)
        }
        group.removeAllViews()
        val catalog = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
        val key = catalog?.credentials?.firstOrNull { it.id == preferences.credentialId() }
        group.addView(settingsListRow("API 密钥", key?.name ?: "未选择", inlineValue = true) {
            val current = runCatching { SettingsStore(context).load().serviceCatalog }.getOrNull()
            val options = current?.credentials.orEmpty().mapNotNull { credential ->
                current?.endpoints?.firstOrNull { it.id in credential.endpointIds &&
                    it.answerProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS) }
                    ?.let { credential to it }
            }
            if (options.isEmpty()) toast("请先在 AI 服务的 API 管理中添加兼容语音合成服务的 Key")
            else showChoice(SettingsChoiceSheet(context, "朗读 API 密钥", options.map { (credential, _) ->
                listOf(current?.providers?.firstOrNull { it.id == credential.providerId }?.name, credential.name)
                    .filterNotNull().joinToString(" · ")
            }, options.indexOfFirst { it.first.id == preferences.credentialId() }) { index ->
                runCatching { preferences.select(options[index].first, options[index].second) }
                    .onSuccess { render() }.onFailure { toast("无法保存朗读服务") }.isSuccess
            })
        })
        group.addView(settingsListRow("模型", preferences.voice().model.ifBlank { "未填写" }, inlineValue = true) {
            choices.chooseModel()
        })
        group.addView(settingsListRow("音色", preferences.voice().voice, inlineValue = true) {
            choices.chooseVoice()
        })
        val used=PointReadingCache(java.io.File(noBackupFilesDir,"point_reading_audio")).bytes()
        group.addView(settingsListRow("清理普通点读缓存", "${used / (1024 * 1024)} MiB", inlineValue=true) {
            showDialog(MaterialAlertDialogBuilder(context).setTitle("清理普通点读缓存？")
                .setMessage("保留收藏会话关联的音频。其他片段之后再听时可能需要重新请求；翻译文字不受影响。")
                .setNegativeButton("取消",null).setPositiveButton("清理") { _,_ ->
                    PointReadingController.get(context).clear(keepFavorites=true) { if(parent.isAttachedToWindow)render() }
                })
        })
    }
    private fun edit(field: String, title: String, initial: String): Unit = with(context) {
        val input = TextInputLayout(this).apply {
            hint = title; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val editor = TextInputEditText(input.context).apply {
            isSaveEnabled = false; setSingleLine(true); setText(initial)
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        input.addView(editor)
        val dialog = showDialog(MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { preferences.save(field, editor.text.toString().trim()) }
                .onSuccess { dialog.dismiss(); render() }
                .onFailure { input.error = "请输入有效的模型或音色 ID" }
        }
    }
    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
