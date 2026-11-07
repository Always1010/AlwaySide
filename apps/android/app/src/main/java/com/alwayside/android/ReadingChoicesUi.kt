package com.alwayside.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Offline choices first; model discovery is explicit and cancelled with its settings view. */
internal class ReadingChoicesUi(private val context: Context, private val preferences: ReadingPreferences,
    private val showDialog: (MaterialAlertDialogBuilder) -> AlertDialog,
    private val showChoice: (SettingsChoiceSheet) -> Unit,
    private val edit: (String, String, String) -> Unit,
    private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var owner: View? = null
    private var request: RequestCancellation? = null
    private var progress: AlertDialog? = null
    private var epoch = 0L
    fun bind(view: View) {
        owner = view
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { cancelRequest() }
        })
    }
    fun chooseModel() {
        val known = if (preferences.kind() in setOf(ProviderKind.OPENAI, ProviderKind.CUSTOM)) ReadingPresets.currentModels else emptyList()
        val current = preferences.voice().model
        val models = known.toMutableList()
        if (current.isNotBlank() && models.none { it.id == current }) models.add(ModelPreset(current, current, "当前"))
        val labels = models.map(::modelLabel) + listOf("从服务读取", "兼容旧接口模型", "手动填写")
        showChoice(SettingsChoiceSheet(context, "选择朗读模型", labels, models.indexOfFirst { it.id == current }) { index ->
            if (!active()) false else {
                when {
                    index < models.size -> save("model", models[index].id)
                    index == models.size -> { fetchModels(); true }
                    index == models.size + 1 -> { legacyModels(); true }
                    else -> { edit("model", "朗读模型 ID", current); true }
                }
            }
        })
    }
    private fun legacyModels() {
        val models = ReadingPresets.legacyModels
        showChoice(SettingsChoiceSheet(context, "兼容旧接口模型", models.map(::modelLabel),
            models.indexOfFirst { it.id == preferences.voice().model }) { index -> save("model", models[index].id) })
    }
    fun chooseVoice() {
        val current = preferences.voice()
        val voices = ReadingPresets.voices(current.model).toMutableList()
        if (current.voice !in voices) voices.add(current.voice)
        val labels = voices.map { voice ->
            val name = voice.replaceFirstChar(Char::uppercase)
            when {
                voice == ReadingPresets.defaultVoice(current.model) -> "$name · 默认"
                voice == "cedar" -> "$name · 推荐"
                else -> name
            }
        } + "手动填写音色 ID"
        showChoice(SettingsChoiceSheet(context, "选择音色", labels, voices.indexOf(current.voice)) { index ->
            if (!active()) false else if (index < voices.size) save("voice", voices[index])
            else { edit("voice", "音色 ID", current.voice); true }
        })
    }
    private fun save(field: String, value: String): Boolean {
        if (!active()) return false
        val previous = preferences.voice().voice
        return runCatching { preferences.save(field, value) }.onSuccess {
            if (field == "model" && previous != preferences.voice().voice) toast("已切换为该模型支持的 ${preferences.voice().voice} 音色")
            changed()
        }.onFailure { toast("无法保存朗读配置，请重新打开设置后重试") }.isSuccess
    }
    private fun fetchModels() {
        cancelRequest()
        val configuration = runCatching { preferences.connection(context) }.getOrElse {
            toast(it.message?.take(120) ?: "请先选择有效的 API 密钥"); return
        }
        val token = epoch
        val cancel = RequestCancellation().also { request = it }
        progress = showDialog(MaterialAlertDialogBuilder(context).setTitle("读取模型")
            .setMessage("正在读取所选服务的模型目录，不生成语音。")
            .setNegativeButton("取消") { _, _ -> cancelRequest() })
        progress?.setOnCancelListener { cancelRequest() }
        worker.execute {
            try {
                val models = AiProvider().listModelCatalog(configuration, cancel)
                main.post {
                    if (epoch != token || cancel.isCancelled || !active()) return@post
                    request = null; progress?.dismiss(); progress = null
                    val latest = runCatching { preferences.connection(context) }.getOrNull()
                    if (latest == null || latest.credentialId != configuration.credentialId || latest.baseUrl != configuration.baseUrl ||
                        latest.credentialRevision != configuration.credentialRevision || latest.apiKey != configuration.apiKey) {
                        toast("朗读服务已改变，请重新读取模型"); return@post
                    }
                    showRemoteModels(models)
                }
            } catch (_: java.util.concurrent.CancellationException) {
                main.post { if (epoch == token) { cancelRequest(); toast("模型读取已取消") } }
            } catch (error: Exception) {
                main.post {
                    if (epoch == token && !cancel.isCancelled && active()) {
                        cancelRequest()
                        toast(if (error is ProviderException) error.message.orEmpty().take(150)
                            else "读取模型失败，可继续使用内置选项或手动填写")
                    }
                }
            }
        }
    }
    private fun showRemoteModels(models: List<RemoteModel>) {
        if (models.isEmpty()) { toast("服务未返回模型，可继续使用内置选项"); return }
        val supported = ReadingPresets.supportedModels(models)
        val labels = supported.map { it.id } + "查看全部返回模型（兼容性未验证）"
        if (supported.isEmpty()) toast("目录未列出已知点读模型，可查看全部或使用内置选项")
        showChoice(SettingsChoiceSheet(context, "服务返回的点读模型", labels,
            supported.indexOfFirst { it.id == preferences.voice().model }) { index ->
            if (!active()) false else if (index < supported.size) save("model", supported[index].id)
            else {
                val all = models.distinctBy { it.id }.take(200)
                if (models.size > 200) toast("仅显示前 200 项，其余可手动填写模型 ID")
                showChoice(SettingsChoiceSheet(context, "全部模型 · 未验证朗读能力", all.map { it.id },
                    all.indexOfFirst { it.id == preferences.voice().model }) { chosen -> save("model", all[chosen].id) })
                true
            }
        })
    }
    private fun cancelRequest() {
        epoch++; request?.cancel(); request = null
        progress?.dismiss(); progress = null
    }
    private fun active() = owner?.isAttachedToWindow == true
    private fun modelLabel(model: ModelPreset) = "${model.name} · ${model.tag}\n${model.id}"
    private fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_LONG).show()
    companion object { private val worker by lazy { Executors.newSingleThreadExecutor() } }
}
