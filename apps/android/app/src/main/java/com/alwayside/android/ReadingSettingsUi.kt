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
    fun render(): Unit = with(context) {
        val group = rows ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this@ReadingSettingsUi.parent.addView(settingsSectionHeader("朗读服务", help = "说明", onHelp = {
                showDialog(MaterialAlertDialogBuilder(context).setTitle("朗读服务")
                    .setMessage("复用 API 管理中的 Key，独立填写语音合成模型与音色。第一版支持 Bearer 认证的 audio/speech 接口，输出 MP3；服务能问答不代表支持朗读。\n\n保存设置不联网。初次点读会请求语音服务，重播优先读取本机文件。播放速度在点读条调整，不重复合成。\n\n普通音频上限 256 MiB，按最近播放时间清理。收藏会话中已生成的音频不自动淘汰；关闭开关不会删除音频。")
                    .setPositiveButton("知道了", null))
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            this@ReadingSettingsUi.parent.addView(settingsListGroup(this), LinearLayout.LayoutParams(-1, -2))
            rows = this
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
            edit("model", "朗读模型", preferences.voice().model)
        })
        group.addView(settingsListRow("音色", preferences.voice().voice, inlineValue = true) {
            edit("voice", "音色 ID", preferences.voice().voice)
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
