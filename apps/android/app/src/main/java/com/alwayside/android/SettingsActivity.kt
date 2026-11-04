package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var autoUpdate: CheckBox
    private lateinit var webSearch: CheckBox
    private var readable = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SettingsStore(this)
        body = column()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("设置", 28f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(android.R.drawable.ic_menu_close_clear_cancel, "关闭设置") { finish() })
        }
        body.addView(header)
        body.addView(label("配置只作用于之后主动发起的请求。保存、加载预设或恢复默认值都不会发送内容。", muted = true))
        status = label("", 14f, true)
        body.addView(status)
        input("apiKey", "API Key", "仅保存在本机，由 Android KeyStore 加密", secret = true)
        body.addView(label("密钥输入禁止自动填充及界面状态保存；此页禁止截图。清空密钥并保存可移除本机凭据。", 13f, true))
        input("baseUrl", "API Base URL（以 /v1 结尾）", "https://api.openai.com/v1")
        body.addView(label("仅接受以 /v1 结尾的 HTTPS 地址。密钥会发送到你填写的服务地址，请确认该地址可信。", 13f, true))
        input("model", "图文模型", "例如 gpt-4.1-mini")
        input("transcriptionModel", "转写模型", "由服务端支持的转写模型名称")
        input("instructions", "Instructions", "助手应如何回答", multiline = true)
        input("defaultQuestion", "画面默认问题", "快速解释时发送的问题", multiline = true)
        input("audioQuestion", "音频默认问题", "转写后发送的问题", multiline = true)
        body.addView(label("提示词预设", 22f))
        body.addView(label("预设仅包含以上三项提示词，不包含密钥、地址或模型。加载后仍需保存设置才生效。", 13f, true))
        body.action("保存为命名预设") { savePreset() }
        body.action("加载提示词预设") { loadPreset() }
        autoUpdate = checkbox("每轮发送时更新画面（需要有效屏幕共享）")
        body.addView(label("仅在主动发送时采集新画面，不持续发送视频。", 13f, true))
        input("maxOutputTokens", "最大输出 tokens", "4096", numeric = true)
        input("reasoning", "推理程度（可留空）", "空白使用模型默认；low / medium / high 等")
        body.addView(label("部分模型不支持推理参数；留空不会发送该参数。填写后需由服务端模型支持。", 13f, true))
        webSearch = checkbox("请求联网搜索")
        body.addView(label("此开关请求 Responses API 的 web_search 工具；需要服务端及当前模型支持，可能产生额外费用。不支持时会显示错误，不会声称已搜索；引用以返回结果为准。", 13f, true))
        body.action("保存设置", true) { save() }
        body.action("恢复默认值（先预览）") {
            AlertDialog.Builder(this).setTitle("恢复默认设置？")
                .setMessage(if (readable) "将把表单改为默认值并清空表单中的密钥。点击“保存设置”后才会覆盖已存配置；不会发送请求。预设和历史保留。"
                    else "配置无法读取。确认后将立即清除已存配置及加密凭据，恢复默认设置。预设和历史保留，不会发送请求。")
                .setNegativeButton("取消", null).setPositiveButton(if (readable) "恢复表单" else "重置已存配置") { _, _ ->
                    if (readable) { populate(AppConfig()); status.text = "已恢复表单，尚未保存" }
                    else try { store.resetConfiguration(); reload(); toast("已重置设置，请重新填写密钥") }
                    catch (_: Exception) { toast("重置失败，请检查本机存储") }
                }.show()
        }
        body.action("移除已保存的 API Key") { clearCredential() }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true; isFillViewport = true; addView(body)
        })
        reload()
    }

    private fun input(name: String, title: String, hintValue: String, multiline: Boolean = false,
                      secret: Boolean = false, numeric: Boolean = false) {
        val titleView = label(title)
        val field = EditText(this).apply {
            id = View.generateViewId()
            hint = hintValue; setTextColor(INK); setHintTextColor(MUTED)
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = card(SURFACE, 12f)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (secret) {
                transformationMethod = PasswordTransformationMethod.getInstance()
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                setAutofillHints(null)
            }
            isSaveEnabled = false; isSaveFromParentEnabled = false
            setSingleLine(!multiline)
            if (multiline) minLines = 3
            contentDescription = title
        }
        titleView.labelFor = field.id
        fields[name] = field
        body.addView(titleView)
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
    }

    private fun checkbox(title: String) = CheckBox(this).apply {
        text = title; setTextColor(INK); isSaveEnabled = false
        minHeight = dp(48); body.addView(this)
    }

    private fun populate(value: AppConfig) {
        val values = mapOf("apiKey" to value.apiKey, "baseUrl" to value.baseUrl, "model" to value.model,
            "transcriptionModel" to value.transcriptionModel, "instructions" to value.instructions,
            "defaultQuestion" to value.defaultQuestion, "audioQuestion" to value.audioQuestion,
            "maxOutputTokens" to value.maxOutputTokens.toString(), "reasoning" to value.reasoning)
        values.forEach { (name, text) -> fields.getValue(name).setText(text) }
        autoUpdate.isChecked = value.autoUpdateImage; webSearch.isChecked = value.webSearch
    }

    private fun reload() {
        try { populate(store.load()); readable = true; status.text = "配置保存在本机" }
        catch (_: Exception) {
            readable = false; fields.getValue("apiKey").setText("")
            status.text = "无法读取配置或解密密钥，已停止加载。可移除已存密钥；如配置仍损坏，可恢复默认值。不会使用明文备用密钥。"
        }
    }

    private fun value(name: String) = fields.getValue(name).text.toString()
    private fun save() {
        if (!readable) { toast("请先移除无法读取的已存密钥，再保存设置"); return }
        val tokens = value("maxOutputTokens").toIntOrNull()
        if (tokens == null) { toast("最大输出 tokens 请填写整数"); return }
        val config = AppConfig(apiKey = value("apiKey").trim(), baseUrl = value("baseUrl").trim(),
            model = value("model").trim(), transcriptionModel = value("transcriptionModel").trim(),
            instructions = value("instructions"), defaultQuestion = value("defaultQuestion"), audioQuestion = value("audioQuestion"),
            autoUpdateImage = autoUpdate.isChecked, maxOutputTokens = tokens,
            reasoning = value("reasoning").trim(), webSearch = webSearch.isChecked)
        try { SettingsStore.validate(config) }
        catch (error: IllegalArgumentException) { toast(error.message ?: "设置内容无效"); return }
        try { store.save(config); status.text = "已保存，只影响之后的请求"; toast("设置已保存") }
        catch (_: Exception) { status.text = "保存失败。请检查设备安全存储及可用空间；未使用明文保存密钥。" }
    }

    private fun clearCredential() {
        AlertDialog.Builder(this).setTitle("移除本机 API Key？")
            .setMessage("将立即移除已保存的加密密钥值。之后需要重新填写并保存才能调用服务。其他设置及历史保留。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                try { store.clearCredential(); reload(); toast("已移除本机密钥") }
                catch (_: Exception) { toast("移除失败，请检查本机存储") }
            }.show()
    }

    private fun savePreset() {
        val name = EditText(this).apply { hint = "预设名称（最多 60 字）"; isSaveEnabled = false }
        val dialog = AlertDialog.Builder(this).setTitle("保存提示词预设").setView(name)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isBlank() || title.length > 60) { toast("名称需为 1 至 60 字"); return@setOnClickListener }
                try {
                    val perform = {
                        try { store.savePreset(title, PromptPreset(value("instructions"), value("defaultQuestion"), value("audioQuestion")))
                            dialog.dismiss(); toast("预设已保存") }
                        catch (_: Exception) { toast("保存失败：最多 20 个预设，单项提示词最多 32000 字") }
                    }
                    if (store.presets().containsKey(title)) {
                        AlertDialog.Builder(this).setTitle("覆盖同名预设？").setMessage(title)
                            .setNegativeButton("取消", null).setPositiveButton("覆盖") { _, _ -> perform() }.show()
                    } else perform()
                } catch (_: Exception) { toast("无法读取预设") }
            }
        }
        dialog.show()
    }

    private fun loadPreset() {
        try {
            val presets = store.presets()
            if (presets.isEmpty()) { toast("尚未保存预设"); return }
            val names = presets.keys.toTypedArray()
            AlertDialog.Builder(this).setTitle("选择提示词预设").setItems(names) { _, index ->
                val preset = presets.getValue(names[index])
                fields.getValue("instructions").setText(preset.instructions)
                fields.getValue("defaultQuestion").setText(preset.defaultQuestion)
                fields.getValue("audioQuestion").setText(preset.audioQuestion)
                status.text = "已加载“${names[index]}”，保存设置后生效"
            }.setNegativeButton("取消", null).show()
        } catch (_: Exception) { toast("无法读取提示词预设") }
    }

    private fun icon(resource: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = android.content.res.ColorStateList.valueOf(INK)
        background = card(SURFACE); accessible(description)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { action() }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
