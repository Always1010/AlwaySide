package com.alwayside.android

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.MenuItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.util.concurrent.CancellationException

/** Category pages intentionally keep their draft only in the current view, never saved instance state. */
internal class SettingsScreen(
    private val activity: Activity,
    private val onContent: (View) -> Unit,
    private val onBack: () -> Unit,
    initialPage: String? = null,
) : ContextWrapper(activity) {
    private enum class Page(val title: String, val description: String, val icon: Int) {
        HOME("设置", "", android.R.drawable.ic_menu_preferences),
        API("API 连接", "服务地址、加密密钥与可用模型", android.R.drawable.ic_menu_manage),
        MODELS("模型与生成参数", "图文与转写模型、推理、搜索及采样", android.R.drawable.ic_menu_sort_by_size),
        PROMPTS("提示词", "截图与选中文字问题、转写提示与音频输出", android.R.drawable.ic_menu_edit),
        APPEARANCE("应用外观", "跟随系统、浅色或深色主题", android.R.drawable.ic_menu_gallery),
        BALL("悬浮球外观", "大小、颜色与透明度预览", android.R.drawable.ic_menu_view),
        GESTURES("快捷动作", "分别设置空闲和录音中的快捷动作", android.R.drawable.ic_menu_directions),
        WINDOW("结果窗口", "位置、尺寸、默认固定与自动提问", android.R.drawable.ic_menu_crop),
        MEDIA("历史与媒体", "截图保留策略与证据说明", android.R.drawable.ic_menu_recent_history),
    }

    private lateinit var store: SettingsStore
    private lateinit var interactionStore: InteractionPreferences
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var activeEdit: FieldEditState? = null
    private var closeEditor: (() -> Unit)? = null
    private var discardConfirmation: AlertDialog? = null
    private var templateSheet: BottomSheetDialog? = null
    private var group: LinearLayout? = null
    private val selections = linkedMapOf<String, String>()
    private val audioOutputOptions = linkedMapOf("transcript" to "仅原始转写", "translate" to "原文 + 翻译", "interpret" to "原文 + 翻译与解读")
    private var page = Page.HOME
    private var readable = true
    private var ballSize = 28
    private var ballOpacity = 55
    private var ballColor = InteractionConfig().ballColor
    private var colorPalette: LinearLayout? = null
    private var savedCredential = false
    private var credentialState: TextView? = null
    private val valueViews = linkedMapOf<String, TextView>()
    private var ballPreview: View? = null
    private var modelRequest: RequestCancellation? = null
    private var modelProgress: AlertDialog? = null
    private var generation = 0
    private var currentRoot: View? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private var disposed = false

    init {
        store = SettingsStore(this)
        interactionStore = InteractionPreferences(this)
        showPage(Page.entries.firstOrNull { it.name == initialPage } ?: Page.HOME)
    }

    val isHome: Boolean get() = page == Page.HOME

    fun pageName(): String = page.name

    /** Reattach the existing view so navigating the host does not recreate a draft. */
    fun show() {
        if (!disposed) currentRoot?.let(onContent)
    }

    /** An active editor consumes back before category navigation. */
    fun back(): Boolean {
        if (disposed) return false
        if (activeEdit != null) { finishEditing {}; return true }
        if (isHome) return false
        showPage(Page.HOME)
        return true
    }

    fun requestLeave(leave: () -> Unit) {
        if (!disposed) finishEditing(leave)
    }

    private fun finishEditing(next: () -> Unit) {
        if (discardConfirmation?.isShowing == true) return
        if (activeEdit?.changed == true) {
            discardConfirmation = MaterialAlertDialogBuilder(this).setTitle("放弃未保存的修改？")
                .setMessage("当前编辑内容还未保存。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改") { _, _ -> closeEditor?.invoke(); next() }.showTracked()
        } else { closeEditor?.invoke(); next() }
    }

    private fun showPage(next: Page) {
        if (disposed) return
        cancelModelRequest()
        dismissDialogs()
        clearDraft()
        page = next; generation++; group = null
        body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
        }
        val header = materialToolbar(next.title, if (next == Page.HOME) onBack else ({ back(); Unit })).apply {
            if (next == Page.PROMPTS) {
                addIconAction(4201, android.R.drawable.ic_menu_agenda, "模板") { finishEditing { showTemplateActions() } }
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        root.addView(header)
        root.addView(divider())
        status = label("", 14f, true).apply {
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val config = try { store.load().also { readable = true } } catch (_: Exception) { readable = false; AppConfig() }
        val interaction = interactionStore.load()
        if (!readable) status.text = "无法读取配置或解密密钥。请到 API 连接移除密钥；仍失败时可重置连接与模型配置。不会使用明文备用密钥。"
        when (next) {
            Page.HOME -> Page.entries.filter { it != Page.HOME }.forEach { category(it) }
            Page.API -> apiPage(config)
            Page.MODELS -> modelPage(config)
            Page.PROMPTS -> promptPage(config)
            Page.APPEARANCE -> appearancePage()
            Page.BALL -> ballPage(interaction)
            Page.GESTURES -> gesturePage(interaction)
            Page.WINDOW -> windowPage(config, interaction)
            Page.MEDIA -> mediaPage(interaction)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true; isSaveEnabled = false; clipToPadding = false; addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        currentRoot = root
        onContent(root)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun category(value: Page) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(dp(16), dp(14), dp(16), dp(14)); background = touchFeedback()
            isClickable = true; isFocusable = true; contentDescription = "${value.title}，${value.description}，进入详情"
            addView(LinearLayout(this@SettingsScreen).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(value.title, 16f))
                addView(label(value.description, 14f, true).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            addView(android.widget.ImageView(this@SettingsScreen).apply {
                setImageDrawable(prototypeIcon(android.R.drawable.ic_media_next))
                imageTintList = ColorStateList.valueOf(MUTED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            setOnClickListener { showPage(value) }
        }
        addRow(row)
    }

    private fun apiPage(config: AppConfig) {
        savedCredential = readable && config.apiKey.isNotBlank()
        input("baseUrl", "服务地址", "示例：https://api.openai.com/v1", config.baseUrl)
        note("以 /v1 结尾。密钥会发送到此地址，请确认服务可信；不要包含账号、查询参数或 # 片段。")
        input("apiKey", "API 密钥", "请输入密钥", secret = true)
        note("密钥加密保存，编辑时输入新密钥。")
        action("查看可用模型") { fetchModels() }
        note("仅请求模型列表，不上传屏幕、音频或聊天。列出的模型不一定支持所有功能。")
        action("移除密钥") { finishEditing { clearCredential() } }.destructive()
        if (!readable) action("重置连接配置") {
            MaterialAlertDialogBuilder(this).setTitle("重置无法读取的配置？")
                .setMessage("清除连接、模型、提示词及密钥，恢复默认值。快捷动作、模板和历史保留。")
                .setNegativeButton("取消", null).setPositiveButton("重置") { _, _ ->
                    try { store.resetConfiguration(); showPage(Page.API); toast("已重置，请重新填写密钥") }
                    catch (_: Exception) { toast("重置失败，请检查本机存储") }
                }.showTracked()
        }.destructive()
    }

    private fun appearancePage() {
        choice("appearance", "应用主题", AppAppearance.mode(this),
            linkedMapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"))
        choice("scheme", "配色方案", AppAppearance.scheme(this), linkedMapOf("black" to "黑色", "blue" to "蓝色"))
        note("选择后立即应用。")
    }

    private fun modelPage(config: AppConfig) {
        input("model", "图文模型", "请输入模型 ID", config.model)
        action("从列表选择图文模型") { fetchModels("model") }
        input("transcriptionModel", "转写模型", "请输入转写模型 ID", config.transcriptionModel)
        action("从列表选择转写模型") { fetchModels("transcriptionModel") }
        note("列表使用已保存连接。请按服务说明选择模型用途。")
        choice("reasoning", "推理程度", config.reasoning, linkedMapOf("" to "不设置（模型默认）", "none" to "none", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high", "xhigh" to "xhigh", "max" to "max"))
        choice("verbosity", "回答详细程度", config.verbosity, linkedMapOf("" to "不设置（模型默认）", "low" to "low · 简短", "medium" to "medium · 适中", "high" to "high · 详细"))
        input("maxOutputTokens", "输出上限（tokens）", "请输入上限（选填）", config.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), numeric = true)
        note("可填 16–131072，实际上限由模型决定。留空或 0 使用模型默认。")
        check("webSearch", "默认联网搜索", config.webSearch)
        note("问答窗口可单独切换。需服务和模型支持，可能增加费用。")
        input("temperature", "Temperature", "0–2，留空使用默认值", config.temperature?.toString().orEmpty(), decimal = true)
        note("值低更稳定，值高更灵活。")
        input("topP", "Top P", "0–1，留空使用默认值", config.topP?.toString().orEmpty(), decimal = true)
        note("值越小，候选用词范围越窄。")
        note("一般留空即可；需要时只调整其中一项，部分模型不支持。不支持时会显示服务错误。")
    }

    private fun promptPage(config: AppConfig) {
        section("回复规则")
        input("instructions", "助手回答方式", "设置语气、格式及希望助手遵循的要求", config.instructions, multiline = true)
        section("快捷提问")
        input("defaultQuestion", "截图问题", "截图后自动填入的问题", config.defaultQuestion, multiline = true)
        input("textQuestion", "选中文字问题", "选中文字后自动填入的问题", config.textQuestion, multiline = true)
        input("audioQuestion", "音频问题", "转写后交给助手的问题", config.audioQuestion, multiline = true)
        section("音频处理")
        input("transcriptionPrompt", "转写提示", "示例：专有名词、背景或语言", config.transcriptionPrompt, multiline = true)
        choice("audioOutput", "音频默认输出", config.audioOutput, audioOutputOptions)
        note("仅原始转写会跳过首次 AI 回复，之后仍可追问。转写提示用于识别语言与专有名词。")
    }

    private fun showTemplateActions() {
        templateSheet?.dismiss()
        val sheet = BottomSheetDialog(this)
        templateSheet = sheet
        val content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        content.addView(label("模板包含六项已保存的提示配置。", 14f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        fun templateAction(title: String, run: () -> Unit) {
            content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = title; applyActionStyle(false)
                setOnClickListener { sheet.dismiss(); finishEditing(run) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        templateAction("保存为模板") { savePreset() }
        templateAction("使用已有模板") { loadPreset() }
        templateAction("恢复默认提示词") {
            MaterialAlertDialogBuilder(this).setTitle("恢复默认提示词？")
                .setMessage("恢复本页六项配置，保留已有模板。")
                .setNegativeButton("取消", null).setPositiveButton("恢复默认") { _, _ ->
                    finishEditing { if (persist(SettingsUpdate.Prompts(PromptPreset.capture(AppConfig())))) showPage(page) }
                }.showTracked()
        }
        sheet.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(materialToolbar("提示词模板", compact = true))
            addView(content)
        })
        sheet.setOnDismissListener { if (templateSheet === sheet) templateSheet = null }
        sheet.show()
    }

    private fun ballPage(config: InteractionConfig) {
        ballSize = config.ballSize; ballOpacity = config.ballOpacity; ballColor = config.ballColor
        val previewArea = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = card(SURFACE, dp(16).toFloat())
            ballPreview = View(this@SettingsScreen).also { addView(it) }
        }
        body.addView(previewArea, LinearLayout.LayoutParams(-1, dp(100)))
        slider("视觉直径", 18, 48, ballSize, "dp") { ballSize = it; updateBallPreview() }
        slider("不透明度", 15, 100, ballOpacity, "%") { ballOpacity = it; updateBallPreview() }
        section("球体颜色")
        colorPalette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(colorPalette, LinearLayout.LayoutParams(-1, -2))
        renderColorPalette()
        action("更多颜色") {
            val picker = ColorPicker(this, ballColor)
            MaterialAlertDialogBuilder(this).setTitle("选择球体颜色").setView(ScrollView(this).apply { addView(picker) })
                .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                    saveBallColor(picker.selectedColor)
                }.showTracked()
        }
        updateBallPreview()
        note("拖动预览，松手保存。颜色选择后立即保存。视觉大小不影响触摸范围。")
    }

    private fun gesturePage(config: InteractionConfig) {
        note("单击和双击会短暂等待，以识别连续点击。拖动只移动悬浮球。")
        section("空闲时")
        choice("singleAction", "单击", config.singleAction, AssistantActions.choices)
        choice("doubleAction", "双击", config.doubleAction, AssistantActions.choices)
        choice("tripleAction", "三击", config.tripleAction, AssistantActions.choices)
        choice("longAction", "长按", config.longAction, AssistantActions.choices)
        section("录音中")
        choice("recordingSingleAction", "单击", config.recordingSingleAction, AssistantActions.choices)
        choice("recordingDoubleAction", "双击", config.recordingDoubleAction, AssistantActions.choices)
        choice("recordingTripleAction", "三击", config.recordingTripleAction, AssistantActions.choices)
        choice("recordingLongAction", "长按", config.recordingLongAction, AssistantActions.choices)
        note("“取消并丢弃录音”不上传或处理。录音中选择“开始／结束录音并处理”会结束并处理。")
        action("恢复默认手势") {
            MaterialAlertDialogBuilder(this).setTitle("恢复默认快捷动作？")
                .setMessage("仅恢复空闲与录音中的八项动作。")
                .setNegativeButton("取消", null).setPositiveButton("恢复默认") { _, _ ->
                    val d = InteractionConfig()
                    if (persistInteraction { it.copy(singleAction = d.singleAction, doubleAction = d.doubleAction,
                        tripleAction = d.tripleAction, longAction = d.longAction,
                        recordingSingleAction = d.recordingSingleAction, recordingDoubleAction = d.recordingDoubleAction,
                        recordingTripleAction = d.recordingTripleAction, recordingLongAction = d.recordingLongAction) }) showPage(page)
                }.showTracked()
        }
    }

    private fun windowPage(config: AppConfig, interaction: InteractionConfig) {
        check("rememberWindowPosition", "分别记住截图窗与音频窗的位置", interaction.rememberWindowPosition)
        check("rememberWindowSize", "分别记住截图窗与音频窗的尺寸", interaction.rememberWindowSize)
        check("defaultPinned", "新会话窗口默认固定", interaction.defaultPinned)
        note("固定时，点窗口外部也会保留窗口；否则收起。收起不取消请求，结果仍会保存。")
        section("首问与截图上下文")
        check("autoAskImage", "截图后自动使用默认问题提问", interaction.autoAskImage)
        check("autoAskAudio", "结束录音后自动按默认设置处理", interaction.autoAskAudio)
        check("autoAskText", "选中文字后自动提问", interaction.autoAskText)
        note("关闭后先打开会话，等你点发送。取消录音始终直接丢弃片段。")
        check("autoUpdateImage", "截图追问默认更新当前画面", config.autoUpdateImage)
        note("发送时才采集新画面，需开启屏幕共享。会话中仍可逐次选择沿用原图或仅发文字。")
    }

    private fun mediaPage(config: InteractionConfig) {
        check("saveHistoryImages", "随历史长期保留截图", config.saveHistoryImages)
        note("默认关闭。当前会话仍可沿用截图；从历史打开后缺少原图时，可重新截图或只问文字。")
        note("仅影响新写入的历史，不补存旧截图；旧记录能否显示原图，取决于当时是否保存。")
        section("音频与对话记录")
        note("不长期保存原始音频，只保存转写、问题和回答。追问复用转写文字，不重复上传音频。")
        note("追问保存在原会话内。待发送、处理中、取消或失败的会话，也可从主页对话列表找到。")
    }

    private fun input(name: String, title: String, hintValue: String, initial: String = "", multiline: Boolean = false,
                      secret: Boolean = false, numeric: Boolean = false, decimal: Boolean = false) {
        val content = column().apply {
            setPadding(dp(16), dp(8), dp(8), dp(16))
            background = touchFeedback()
            isClickable = true; isFocusable = true
        }
        val valueView = label(if (secret) credentialStatus() else initial.ifBlank { "未设置" }).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(INK)
            maxLines = if (multiline) 3 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        valueViews[name] = valueView
        if (secret) credentialState = valueView
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val open = {
            finishEditing {
                val start = if (secret) "" else try {
                    SettingsUpdate.TextField.valueOf(name).read(store.load())
                } catch (_: Exception) {
                    toast("无法读取配置，请先修复 API 连接")
                    return@finishEditing
                }
                activeEdit = FieldEditState(name, start)
                lateinit var panel: SettingsEditPanel
                panel = SettingsEditPanel(this, title, hintValue, start, multiline, secret, numeric, decimal,
                    onDraft = { draft -> if (activeEdit?.name == name) activeEdit?.draft = draft },
                    onRequestClose = { finishEditing {} },
                    onSave = { draft, error -> saveText(name, draft, secret, valueView, error) })
                closeEditor = {
                    activeEdit = null; closeEditor = null
                    panel.close()
                }
                panel.show()
            }
        }
        content.addView(header, LinearLayout.LayoutParams(-1, -2))
        content.addView(valueView, LinearLayout.LayoutParams(-1, -2).apply { rightMargin = dp(8); topMargin = dp(2) })
        content.contentDescription = "编辑$title"
        content.setOnClickListener { open() }
        // Every long field gets its own full-width card, preserving readable previews.
        if (multiline) group = null
        addRow(content)
        if (multiline) group = null
    }

    private fun saveText(name: String, draft: String, secret: Boolean, valueView: TextView, error: (String) -> Unit) {
        val update = SettingsUpdate.Text(SettingsUpdate.TextField.valueOf(name), draft)
        val save: (Boolean) -> Unit = { replace ->
            if (persist(update.copy(replaceSampling = replace), error)) {
                if (secret) savedCredential = true
                valueView.text = if (secret) credentialStatus() else draft.ifBlank { "未设置" }
                if (name == "temperature") valueViews["topP"]?.text = try { store.load().topP?.toString() ?: "未设置" } catch (_: Exception) { "未设置" }
                if (name == "topP") valueViews["temperature"]?.text = try { store.load().temperature?.toString() ?: "未设置" } catch (_: Exception) { "未设置" }
                closeEditor?.invoke()
            }
        }
        try {
            val current = store.load()
            // Validate input before asking to replace the other sampling parameter.
            val candidate = update.copy(replaceSampling = true).applyTo(current)
            SettingsStore.validate(candidate)
            val conflict = draft.isNotBlank() && ((name == "temperature" && current.topP != null) || (name == "topP" && current.temperature != null))
            if (conflict) MaterialAlertDialogBuilder(this).setTitle("替换另一项采样参数？")
                .setMessage("Temperature 与 Top P 只设置一项。保存会清空另一项。")
                .setNegativeButton("继续编辑", null).setPositiveButton("替换并保存") { _, _ -> save(true) }.showTracked()
            else save(false)
        } catch (failure: IllegalArgumentException) { error(failure.message ?: "请检查输入") }
        catch (_: Exception) { error("无法读取配置，未保存") }
    }

    private fun credentialStatus() = if (savedCredential) "已保存" else "未设置"

    private fun check(name: String, title: String, initial: Boolean) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); setPadding(dp(16), dp(5), dp(12), dp(5))
        }
        row.addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        var restoring = false
        row.addView(MaterialSwitch(this).apply {
            isSaveEnabled = false; isChecked = initial; contentDescription = title
            setOnCheckedChangeListener { button, value ->
                if (restoring) return@setOnCheckedChangeListener
                val ok = when (name) {
                    "webSearch" -> persist(SettingsUpdate.Toggle(webSearch = value))
                    "autoUpdateImage" -> persist(SettingsUpdate.Toggle(autoUpdateImage = value))
                    else -> persistInteraction { when (name) {
                        "rememberWindowPosition" -> it.copy(rememberWindowPosition = value)
                        "rememberWindowSize" -> it.copy(rememberWindowSize = value)
                        "defaultPinned" -> it.copy(defaultPinned = value)
                        "autoAskImage" -> it.copy(autoAskImage = value)
                        "autoAskAudio" -> it.copy(autoAskAudio = value)
                        "autoAskText" -> it.copy(autoAskText = value)
                        "saveHistoryImages" -> it.copy(saveHistoryImages = value)
                        else -> error("Unknown toggle")
                    } }
                }
                if (!ok) { restoring = true; button.isChecked = !value; restoring = false }
            }
        })
        addRow(row)
    }

    private fun choice(name: String, title: String, initial: String, options: Map<String, String>) {
        selections[name] = initial
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); setPadding(dp(16), dp(10), dp(16), dp(10))
            background = touchFeedback(); isClickable = true; isFocusable = true
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 16f))
            addView(label(options[initial] ?: initial, 14f, true).apply { tag = name; setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        row.addView(android.widget.ImageView(this).apply {
            setImageDrawable(prototypeIcon(android.R.drawable.ic_media_next))
            imageTintList = ColorStateList.valueOf(MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(8) })
        row.setOnClickListener { finishEditing {
            val keys = options.keys.toList()
            MaterialAlertDialogBuilder(this).setTitle(title).setSingleChoiceItems(options.values.toTypedArray(), keys.indexOf(selections[name])) { dialog, index ->
                if (persistChoice(name, keys[index])) {
                    selections[name] = keys[index]
                    row.findViewWithTag<TextView>(name).text = options[keys[index]]
                    dialog.dismiss()
                }
            }.setNegativeButton("取消", null).showTracked()
        } }
        addRow(row)
    }

    private fun persistChoice(name: String, value: String): Boolean {
        if (name == "appearance" || name == "scheme") return try {
            if (name == "appearance") AppAppearance.save(this, value) else AppAppearance.saveScheme(this, value)
            activity.recreate(); true
        } catch (_: Exception) { toast("保存失败，请重试"); false }
        if (name in setOf("reasoning", "verbosity", "audioOutput"))
            return persist(SettingsUpdate.Choice(SettingsUpdate.ChoiceField.valueOf(name), value))
        return persistInteraction { when (name) {
            "singleAction" -> it.copy(singleAction = value)
            "doubleAction" -> it.copy(doubleAction = value)
            "tripleAction" -> it.copy(tripleAction = value)
            "longAction" -> it.copy(longAction = value)
            "recordingSingleAction" -> it.copy(recordingSingleAction = value)
            "recordingDoubleAction" -> it.copy(recordingDoubleAction = value)
            "recordingTripleAction" -> it.copy(recordingTripleAction = value)
            "recordingLongAction" -> it.copy(recordingLongAction = value)
            else -> error("Unknown choice")
        } }
    }

    private fun slider(title: String, minimum: Int, maximum: Int, initial: Int, unit: String, changed: (Int) -> Unit) {
        val wrap = column().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        val caption = label("$title：$initial $unit", 16f)
        wrap.addView(caption)
        var saved = initial
        var dragging = false
        wrap.addView(Slider(this).apply {
            valueFrom = minimum.toFloat(); valueTo = maximum.toFloat(); stepSize = 1f; value = initial.toFloat()
            contentDescription = title
            setLabelFormatter { "${it.toInt()} $unit" }
            fun saveValue(slider: Slider) {
                val v = slider.value.toInt()
                if (v == saved) return
                if (persistInteraction { if (unit == "dp") it.copy(ballSize = v) else it.copy(ballOpacity = v) }) saved = v
                else slider.value = saved.toFloat()
            }
            addOnChangeListener { slider, value, fromUser ->
                caption.text = "$title：${value.toInt()} $unit"; changed(value.toInt())
                if (fromUser && !dragging) saveValue(slider)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { dragging = true }
                override fun onStopTrackingTouch(slider: Slider) { dragging = false; saveValue(slider) }
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        addRow(wrap)
    }

    private fun renderColorPalette() {
        val palette = colorPalette ?: return
        palette.removeAllViews()
        val colors = listOf(
            0xFF72968E.toInt() to "松绿", 0xFF426BCA.toInt() to "蓝色", 0xFF8D62BD.toInt() to "紫色",
            0xFFCC5C74.toInt() to "玫红", 0xFFE09636.toInt() to "橙色", 0xFF34403A.toInt() to "墨色",
            0xFFF3E9D2.toInt() to "米白",
        )
        colors.chunked(4).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { (color, name) ->
                val chosen = color == ballColor
                val luminance = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = selectionBackground(); isClickable = true; isFocusable = true
                    contentDescription = "$name，${if (chosen) "已选中" else "点按选择"}"
                    addView(label(if (chosen) "✓" else "", 22f).apply {
                        gravity = Gravity.CENTER; setTextColor(if (luminance > 150) Color.BLACK else Color.WHITE)
                        background = card(color, dp(22).toFloat()).apply {
                            setStroke(dp(if (chosen) 3 else 1), if (chosen) INK else BORDER)
                        }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(label(name, 11f).apply {
                        gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    setOnClickListener { saveBallColor(color) }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(4 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            palette.addView(row)
        }
    }

    private fun updateBallPreview() {
        ballPreview?.apply {
            background = card(ballColor, dp(48).toFloat()); alpha = ballOpacity / 100f
            layoutParams = LinearLayout.LayoutParams(dp(ballSize), dp(ballSize))
            contentDescription = "悬浮球预览，直径 $ballSize dp，不透明度 $ballOpacity%"
        }
    }

    private fun persist(update: SettingsUpdate, onError: (String) -> Unit = ::toast): Boolean = try {
        val next = update.applyTo(store.load())
        SettingsStore.validate(next); store.save(next)
        toast("已保存"); true
    } catch (error: IllegalArgumentException) { onError(error.message ?: "设置无效"); false }
    catch (_: Exception) { onError("保存失败，请检查设备安全存储后重试"); false }

    private fun persistInteraction(update: (InteractionConfig) -> InteractionConfig): Boolean = try {
        interactionStore.save(update(interactionStore.load())); toast("已保存"); true
    } catch (_: Exception) { toast("保存失败，请重试"); false }

    private fun saveBallColor(color: Int) {
        if (persistInteraction { it.copy(ballColor = color) }) {
            ballColor = color; renderColorPalette(); updateBallPreview()
        }
    }

    private fun fetchModels(target: String? = null) {
        finishEditing { fetchSavedModels(target) }
    }

    private fun fetchSavedModels(target: String?) {
        if (modelRequest != null) return
        val config = try {
            check(readable)
            val stored = store.load()
            stored.also {
                SettingsStore.validate(it); require(it.apiKey.isNotBlank()) { "请先保存 API 密钥" }
            }
        } catch (error: IllegalArgumentException) { toast(error.message ?: "API 配置无效"); return }
        catch (_: Exception) { toast("无法读取连接配置，请先修复 API 连接"); return }
        val token = RequestCancellation(); modelRequest = token
        val currentGeneration = generation
        modelProgress = MaterialAlertDialogBuilder(this).setTitle("正在读取模型")
            .setMessage("仅请求当前 API 地址的 /models，不会发送媒体。")
            .setNegativeButton("取消") { _, _ -> cancelModelRequest() }.setOnCancelListener { cancelModelRequest() }.showTracked()
        Thread({
            try {
                val models = AiProvider().listModels(config, token)
                activity.runOnUiThread {
                    if (disposed || activity.isFinishing || activity.isDestroyed || generation != currentGeneration || modelRequest !== token) return@runOnUiThread
                    modelRequest = null; modelProgress?.dismiss(); modelProgress = null
                    if (models.isEmpty()) { toast("服务未返回可用模型，可手动填写模型 ID"); return@runOnUiThread }
                    if (target != null) {
                        MaterialAlertDialogBuilder(this).setTitle(if (target == "model") "选择图文模型 ID" else "选择转写模型 ID")
                            .setItems(models.toTypedArray()) { _, index -> finishEditing {
                                if (persist(SettingsUpdate.Text(SettingsUpdate.TextField.valueOf(target), models[index]))) showPage(page)
                            } }
                            .setNegativeButton("取消", null).showTracked()
                    } else {
                        val list = label(models.joinToString("\n"), 15f).apply { setTextIsSelectable(true); setPadding(dp(20), dp(8), dp(20), dp(8)) }
                        MaterialAlertDialogBuilder(this).setTitle("服务返回的模型 ID")
                            .setView(ScrollView(this).apply { addView(list) })
                            .setMessage("可长按复制。在“模型与生成参数”中也可直接从此列表选择；列表仅使用已保存连接。")
                            .setPositiveButton("完成", null).showTracked()
                    }
                }
            } catch (_: CancellationException) { /* Explicit cancellation has no user-facing error. */ }
            catch (error: Exception) {
                activity.runOnUiThread {
                    if (disposed || activity.isFinishing || activity.isDestroyed || generation != currentGeneration || modelRequest !== token) return@runOnUiThread
                    modelRequest = null; modelProgress?.dismiss(); modelProgress = null
                    status.text = if (error is ProviderException) error.message else "读取模型失败，请检查网络与服务兼容性。可继续手动填写模型 ID。"
                }
            }
        }, "alwayside-settings-models").start()
    }

    private fun cancelModelRequest() {
        modelRequest?.cancel(); modelRequest = null
        modelProgress?.dismiss(); modelProgress = null
    }

    private fun clearCredential() {
        MaterialAlertDialogBuilder(this).setTitle("移除本机 API Key？")
            .setMessage("立即移除已保存的加密密钥。其他已存配置和历史保留；之后需重新填写 Key 才能调用服务。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                try { store.clearCredential(); showPage(Page.API); toast("已移除本机密钥") }
                catch (_: Exception) { toast("移除失败，请检查本机存储") }
            }.showTracked()
    }

    private fun savePreset() {
        activeEdit = FieldEditState("presetName", "")
        lateinit var panel: SettingsEditPanel
        panel = SettingsEditPanel(this, "模板名称", "1–60 字，保存当前六项已存配置", "", false, false, false, false,
            onDraft = { activeEdit?.takeIf { it.name == "presetName" }?.draft = it },
            onRequestClose = { finishEditing {} },
            onSave = { draft, error ->
                val title = draft.trim()
                if (title.isBlank() || title.length > 60) {
                    error("名称需为 1 至 60 字")
                } else {
                    try {
                        val perform = {
                            try {
                                store.savePreset(title, PromptPreset.capture(store.load()))
                                closeEditor?.invoke(); toast("模板已保存")
                            } catch (_: Exception) { error("保存失败，请检查模板数量与提示词长度") }
                        }
                        if (store.presets().containsKey(title)) MaterialAlertDialogBuilder(this).setTitle("覆盖同名模板？")
                            .setMessage(title).setNegativeButton("取消", null)
                            .setPositiveButton("覆盖") { _, _ -> perform() }.showTracked()
                        else perform()
                    } catch (_: Exception) { error("无法读取模板") }
                }
            })
        closeEditor = { activeEdit = null; closeEditor = null; panel.close() }
        panel.show()
    }

    private fun loadPreset() {
        try {
            val presets = store.presets()
            if (presets.isEmpty()) { toast("尚未保存模板"); return }
            val names = presets.keys.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("选择提示词模板").setItems(names) { _, index ->
                val preset = presets.getValue(names[index])
                val preview = try { preset.mergeInto(store.load()) } catch (_: Exception) {
                    toast("无法读取当前配置，请先在 API 连接中修复密钥或重置配置")
                    return@setItems
                }
                val summary = listOf("助手回答方式" to preview.instructions, "截图默认问题" to preview.defaultQuestion,
                    "选中文字默认问题" to preview.textQuestion, "音频默认问题" to preview.audioQuestion,
                    "转写提示" to preview.transcriptionPrompt, "音频默认输出" to (audioOutputOptions[preview.audioOutput] ?: preview.audioOutput))
                    .joinToString("\n\n") { (title, value) -> "$title\n${value.ifBlank { "未设置" }}" }
                MaterialAlertDialogBuilder(this).setTitle("应用“${names[index]}”？")
                    .setMessage("确认以下六项提示配置后立即应用。")
                    .setView(ScrollView(this).apply { addView(label(summary, 14f).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }) })
                    .setNegativeButton("取消", null).setPositiveButton("应用模板") { _, _ ->
                        finishEditing {
                            if (persist(SettingsUpdate.Prompts(preset))) showPage(page)
                        }
                    }.showTracked()

            }.setNegativeButton("取消", null).showTracked()
        } catch (_: Exception) { toast("无法读取提示词模板") }
    }

    private val dangerColor: Int get() = if (Color.red(INK) > 160) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()

    private fun MaterialButton.destructive() { applyActionStyle(false); setTextColor(dangerColor) }

    private fun action(title: String, run: () -> Unit): MaterialButton {
        group = null
        return MaterialButton(this).also { button ->
            button.text = title; button.applyActionStyle(true)
            button.setOnClickListener { run() }
            body.addView(button, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
    }

    private fun section(text: String) {
        group = null
        body.addView(label(text, 14f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (body.childCount <= 1) dp(4) else dp(20); bottomMargin = dp(8); leftMargin = dp(16) })
    }

    private fun addRow(view: View) {
        val container = group ?: column().apply { setPadding(0, 0, 0, 0) }.also { content ->
            body.addView(MaterialCardView(this).apply {
                radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(SURFACE)
                addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { if (body.childCount > 1) topMargin = dp(8) })
            group = content
        }
        if (container.childCount > 0) container.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(16) }
        })
        container.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun note(text: String) {
        val currentGroup = group
        val caption = label(text, 12f, true).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        if (currentGroup != null) {
            caption.setPadding(dp(16), dp(8), dp(16), dp(16))
            currentGroup.addView(caption, LinearLayout.LayoutParams(-1, -2))
        } else {
            body.addView(caption, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(7); bottomMargin = dp(8); leftMargin = dp(12); rightMargin = dp(12)
            })
        }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun clearDraft() {
        closeEditor?.invoke(); activeEdit = null; closeEditor = null
        valueViews.clear(); selections.clear(); ballPreview = null; colorPalette = null; credentialState = null; group = null
    }

    private fun MaterialAlertDialogBuilder.showTracked(): AlertDialog = create().showTracked()

    private fun AlertDialog.showTracked(): AlertDialog {
        if (disposed) return this
        dialogs += this
        setOnDismissListener { dialogs.remove(this) }
        show()
        if (getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() in setOf("移除", "重置", "覆盖", "放弃并返回", "放弃并离开")) {
            getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(dangerColor)
        }
        return this
    }

    private fun dismissDialogs() {
        templateSheet?.dismiss(); templateSheet = null
        discardConfirmation = null
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        cancelModelRequest()
        dismissDialogs()
        clearDraft()
        body.removeAllViews()
        currentRoot = null
    }
}
