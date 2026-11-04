package com.alwayside.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CancellationException

/** Category pages intentionally keep their draft only in the current view, never saved instance state. */
internal class SettingsScreen(
    private val activity: Activity,
    private val onContent: (View) -> Unit,
    private val onHistory: () -> Unit,
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
    private val fields = linkedMapOf<String, EditText>()
    private val checks = linkedMapOf<String, CheckBox>()
    private val selections = linkedMapOf<String, String>()
    private val audioOutputOptions = linkedMapOf("transcript" to "仅原始转写", "translate" to "原文 + 翻译", "interpret" to "原文 + 翻译与解读")
    private var page = Page.HOME
    private var saveControl: android.widget.ImageButton? = null
    private var dirty = false
        set(value) {
            field = value
            saveControl?.isEnabled = value
        }
    private var rendering = false
    private var readable = true
    private var ballSize = 28
    private var ballOpacity = 55
    private var ballColor = InteractionConfig().ballColor
    private var colorPalette: LinearLayout? = null
    private var savedCredential = false
    private var credentialState: TextView? = null
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

    /** The host owns leaving settings; a category always consumes back itself. */
    fun back(): Boolean {
        if (isHome || disposed) return false
        if (!dirty) {
            showPage(Page.HOME)
        } else {
            AlertDialog.Builder(this).setTitle("放弃此页未保存的修改？")
                .setMessage("返回分类后，此页草稿会丢弃。密钥草稿不会带到其他页面。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃并返回") { _, _ -> showPage(Page.HOME) }.showTracked()
        }
        return true
    }

    fun requestLeave(leave: () -> Unit) {
        if (disposed) return
        if (!dirty) {
            leave()
            return
        }
        AlertDialog.Builder(this).setTitle("放弃此页未保存的修改？")
            .setMessage("离开设置后，此页草稿会丢弃。密钥草稿不会带到其他页面。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃并离开") { _, _ ->
                dirty = false
                leave()
            }.showTracked()
    }

    private fun showPage(next: Page) {
        if (disposed) return
        cancelModelRequest()
        dismissDialogs()
        clearDraft()
        page = next; generation++; rendering = true; dirty = false
        body = column().apply { setPadding(dp(22), dp(12), dp(22), dp(24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if(next == Page.HOME)22 else 8), dp(12), dp(10), dp(10))
            if(next != Page.HOME) addView(iconButton(android.R.drawable.ic_media_previous, "返回设置分类") { back() })
            addView(LinearLayout(this@SettingsScreen).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (next == Page.HOME) "AlwaySide" else next.title, if (next == Page.HOME) 20f else 18f).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                if (next == Page.HOME) addView(label("留在当下，随手问一句。", 11f, true).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if(next != Page.HOME) leftMargin = dp(8) })
            if (next != Page.HOME) {
                saveControl = iconButton(android.R.drawable.ic_menu_save, "保存此分类") { savePage() }.apply {
                    applyIconActionStyle(primary = true)
                    isEnabled = dirty
                }
                addView(saveControl)
            }
        }
        root.addView(header)
        if (next == Page.HOME) root.addView(homeTabs()) else root.addView(divider())
        status = label("", 11f, true).apply {
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
        rendering = false; dirty = false
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun homeTabs() = appTabs(AppPage.SETTINGS, onHistory, {})

    private fun category(value: Page) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(0, dp(14), 0, dp(14)); background = touchFeedback()
            isClickable = true; isFocusable = true; contentDescription = "${value.title}，${value.description}，进入详情"
            addView(LinearLayout(this@SettingsScreen).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(value.title, 14f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(label(value.description, 11f, true).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            addView(android.widget.ImageView(this@SettingsScreen).apply {
                setImageDrawable(prototypeIcon(android.R.drawable.ic_media_next))
                imageTintList = ColorStateList.valueOf(MUTED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            setOnClickListener { showPage(value) }
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        body.addView(divider())
    }

    private fun apiPage(config: AppConfig) {
        savedCredential = readable && config.apiKey.isNotBlank()
        input("baseUrl", "服务地址（HTTPS Base URL）", "示例：https://api.openai.com/v1", config.baseUrl)
        note("以 /v1 结尾。密钥会发送到此地址，请确认服务可信；不要包含账号、查询参数或 # 片段。")
        input("apiKey", "API 密钥", "请输入密钥", secret = true)
        note("留空保留已存密钥。密钥仅在本机加密保存，不自动填充；本页禁止截图。")
        body.action("查看可用模型") { fetchModels() }
        note("仅请求模型列表，不上传屏幕、音频或聊天。列出的模型不一定支持所有功能。")
        body.action("移除密钥") { clearCredential() }.destructive()
        if (!readable) body.action("重置连接配置") {
            AlertDialog.Builder(this).setTitle("重置无法读取的配置？")
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
        note("保存后立即应用。默认跟随系统。")
    }

    private fun modelPage(config: AppConfig) {
        input("model", "图文模型 ID", "请输入模型 ID", config.model)
        body.action("从列表选择图文模型") { fetchModels("model") }
        input("transcriptionModel", "转写模型 ID", "请输入转写模型 ID", config.transcriptionModel)
        body.action("从列表选择转写模型") { fetchModels("transcriptionModel") }
        note("列表使用已保存的连接；请先保存 API 连接。请按服务说明选择对应用途的模型。")
        choice("reasoning", "推理程度", config.reasoning, linkedMapOf("" to "不设置（模型默认）", "none" to "none", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high", "xhigh" to "xhigh", "max" to "max"))
        choice("verbosity", "回答详细程度", config.verbosity, linkedMapOf("" to "不设置（模型默认）", "low" to "low · 简短", "medium" to "medium · 适中", "high" to "high · 详细"))
        input("maxOutputTokens", "回答长度上限（tokens）", "请输入上限（选填）", config.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), numeric = true)
        note("可填 16–131072，实际上限由模型决定。留空或 0 使用模型默认。")
        check("webSearch", "请求联网搜索", config.webSearch)
        note("需服务和模型支持，可能增加费用；引用以实际返回为准。")
        input("temperature", "随机程度 temperature（0–2）", "请输入数值（选填）", config.temperature?.toString().orEmpty(), decimal = true)
        note("值低更稳定，值高更灵活。")
        input("topP", "候选范围 top_p（0–1）", "请输入数值（选填）", config.topP?.toString().orEmpty(), decimal = true)
        note("值越小，候选用词范围越窄。")
        note("一般留空即可；需要时只调整其中一项，部分模型不支持。不支持时会显示服务错误。")
    }

    private fun promptPage(config: AppConfig) {
        input("instructions", "助手回答方式", "请输入希望助手遵循的要求", config.instructions, multiline = true)
        input("defaultQuestion", "截图默认问题", "请输入截图后要问的问题", config.defaultQuestion, multiline = true)
        input("textQuestion", "选中文字默认问题", "请输入选中文字后要问的问题", config.textQuestion, multiline = true)
        input("audioQuestion", "音频默认问题", "请输入转写后要问的问题", config.audioQuestion, multiline = true)
        input("transcriptionPrompt", "转写提示", "示例：专有名词、背景或语言", config.transcriptionPrompt, multiline = true)
        note("帮助识别原语言和专有名词，不用于要求翻译或解读。")
        choice("audioOutput", "音频默认输出", config.audioOutput, audioOutputOptions)
        note("原文与 AI 回答分别保存。“仅原始转写”跳过首次 AI 请求，之后仍可追问。")
        section("提示词模板")
        note("模板保存本页全部提示配置，不包含密钥或模型；使用后保存此分类生效，旧模板保留未包含的当前配置。")
        body.action("保存模板") { savePreset() }
        body.action("使用模板") { loadPreset() }
        body.action("恢复默认") {
            AlertDialog.Builder(this).setTitle("恢复默认提示词？").setMessage("仅修改此页草稿，保存后生效。已保存的模板保留。")
                .setNegativeButton("取消", null).setPositiveButton("恢复草稿") { _, _ ->
                    val d = AppConfig()
                    fields.getValue("instructions").setText(d.instructions)
                    fields.getValue("defaultQuestion").setText(d.defaultQuestion)
                    fields.getValue("audioQuestion").setText(d.audioQuestion)
                    fields.getValue("textQuestion").setText(d.textQuestion)
                    fields.getValue("transcriptionPrompt").setText(d.transcriptionPrompt)
                    selections["audioOutput"] = d.audioOutput
                    // Refresh only this selector's visible value without discarding edited fields.
                    setChoice("audioOutput", d.audioOutput, "音频默认输出", audioOutputOptions)
                    dirty = true; status.text = "已恢复此页草稿，尚未保存"
                }.showTracked()
        }
    }

    private fun ballPage(config: InteractionConfig) {
        ballSize = config.ballSize; ballOpacity = config.ballOpacity; ballColor = config.ballColor
        section("外观预览")
        val previewArea = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = card(SURFACE, dp(8).toFloat())
            ballPreview = View(this@SettingsScreen).also { addView(it) }
        }
        body.addView(previewArea, LinearLayout.LayoutParams(-1, dp(100)))
        slider("视觉直径", 18, 48, ballSize, "dp") { ballSize = it; updateBallPreview() }
        slider("不透明度", 15, 100, ballOpacity, "%") { ballOpacity = it; updateBallPreview() }
        section("球体颜色")
        colorPalette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(colorPalette, LinearLayout.LayoutParams(-1, -2))
        renderColorPalette()
        body.action("更多颜色") {
            val picker = ColorPicker(this, ballColor)
            AlertDialog.Builder(this).setTitle("选择球体颜色").setView(ScrollView(this).apply { addView(picker) })
                .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                    ballColor = picker.selectedColor; markDirty(); renderColorPalette(); updateBallPreview()
                }.showTracked()
        }
        updateBallPreview()
        note("保存后应用到悬浮球。视觉大小不影响触摸范围；录音时会增强可见性。")
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
        body.action("恢复默认手势") {
            val defaults = InteractionConfig()
            mapOf("singleAction" to defaults.singleAction, "doubleAction" to defaults.doubleAction,
                "tripleAction" to defaults.tripleAction, "longAction" to defaults.longAction,
                "recordingSingleAction" to defaults.recordingSingleAction, "recordingDoubleAction" to defaults.recordingDoubleAction,
                "recordingTripleAction" to defaults.recordingTripleAction, "recordingLongAction" to defaults.recordingLongAction).forEach { (key, value) ->
                val title = when { key.contains("Single") || key == "singleAction" -> "单击"; key.contains("Double") || key == "doubleAction" -> "双击"; key.contains("Triple") || key == "tripleAction" -> "三击"; else -> "长按" }
                setChoice(key, value, title, AssistantActions.choices)
            }
            markDirty(); status.text = "已恢复默认手势，保存后生效"
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
        val titleView = label(title, 13f)
        val emptyState = if (name in setOf("instructions", "defaultQuestion", "audioQuestion", "textQuestion",
                "transcriptionPrompt", "maxOutputTokens", "temperature", "topP")) "选填" else "未填写"
        val stateView = label(if (secret) credentialStatus() else if (initial.isBlank()) emptyState else "已填写", 11f, true)
        if (secret) credentialState = stateView
        val field = EditText(this).apply {
            id = View.generateViewId(); hint = placeholder(hintValue); textSize = 15f
            applyInputStyle()
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            isSaveEnabled = false; isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (secret) {
                transformationMethod = PasswordTransformationMethod.getInstance()
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                setAutofillHints(null)
            }
            setSingleLine(!multiline); if (multiline) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
            contentDescription = title; setText(initial)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    stateView.text = if (secret) credentialStatus() else if (s.isNullOrBlank()) emptyState else "已填写"
                    if (!rendering) markDirty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        titleView.labelFor = field.id; fields[name] = field
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(stateView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(7) })
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
    }

    private fun placeholder(value: String) = SpannableString(value).apply {
        setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun credentialStatus() = if (savedCredential) "已保存" else "未设置"

    private fun markDirty() {
        if (rendering) return
        dirty = true
        status.text = "有未保存的修改，请点右上角保存"
    }

    private fun check(name: String, title: String, initial: Boolean) {
        val view = CheckBox(this).apply {
            text = title; textSize = 13f; setTextColor(INK); minHeight = dp(44)
            buttonTintList = ColorStateList.valueOf(ACCENT); setPadding(0, dp(4), 0, dp(4))
            isSaveEnabled = false; isChecked = initial
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        checks[name] = view; body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun choice(name: String, title: String, initial: String, options: Map<String, String>) {
        selections[name] = initial
        val button = body.action("$title：${options[initial] ?: initial}") {}
        button.applyActionStyle()
        button.textSize = 13f; button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        button.setPadding(dp(14), dp(9), dp(12), dp(9)); button.minHeight = dp(48)
        button.background = selectionBackground()
        button.setCompoundDrawablesWithIntrinsicBounds(null, null,
            prototypeIcon(android.R.drawable.ic_media_next).apply { setTint(INK) }, null)
        button.compoundDrawablePadding = dp(12)
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply { height = -2 }
        button.tag = name
        button.setOnClickListener {
            val keys = options.keys.toList()
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(options.values.toTypedArray(), keys.indexOf(selections[name])) { dialog, index ->
                setChoice(name, keys[index], title, options)
                markDirty(); dialog.dismiss()
            }.setNegativeButton("取消", null).showTracked()
        }
    }

    private fun setChoice(name: String, value: String, title: String, options: Map<String, String>) {
        selections[name] = value
        body.findViewWithTag<TextView>(name)?.text = "$title：${options[value] ?: value}"
    }

    private fun slider(title: String, minimum: Int, maximum: Int, initial: Int, unit: String, changed: (Int) -> Unit) {
        val caption = label("$title：$initial $unit", 12f).apply { setPadding(0, dp(14), 0, 0) }
        body.addView(caption)
        body.addView(SeekBar(this).apply {
            this.max = maximum - minimum; progress = initial - minimum;
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT); contentDescription = "$title，范围 $minimum 至 $maximum $unit"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = minimum + progress; caption.text = "$title：$value $unit"; changed(value)
                    if (fromUser) markDirty()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun renderColorPalette() {
        val palette = colorPalette ?: return
        palette.removeAllViews()
        val colors = (listOf(ballColor to "当前颜色") + listOf(
            0xFF72968E.toInt() to "松绿", 0xFF426BCA.toInt() to "蓝色", 0xFF8D62BD.toInt() to "紫色",
            0xFFCC5C74.toInt() to "玫红", 0xFFE09636.toInt() to "橙色", 0xFF34403A.toInt() to "墨色",
            0xFFF3E9D2.toInt() to "米白",
        )).distinctBy { it.first }
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
                    setOnClickListener { ballColor = color; markDirty(); renderColorPalette(); updateBallPreview() }
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

    private fun savePage() {
        try {
            when (page) {
                Page.API, Page.MODELS, Page.PROMPTS -> {
                    if (!readable) { toast("请先移除无法读取的密钥，仍失败时重置配置"); return }
                    val old = store.load()
                    val config = when (page) {
                        Page.API -> old.copy(baseUrl = value("baseUrl").trim(), apiKey = value("apiKey").trim().ifBlank { old.apiKey })
                        Page.MODELS -> old.copy(model = value("model").trim(), transcriptionModel = value("transcriptionModel").trim(),
                            maxOutputTokens = if (value("maxOutputTokens").isBlank()) 0 else value("maxOutputTokens").toIntOrNull() ?: throw IllegalArgumentException("最大输出 tokens 请填写整数"),
                            reasoning = selected("reasoning"), verbosity = selected("verbosity"), webSearch = checked("webSearch"),
                            temperature = optionalNumber("temperature"), topP = optionalNumber("topP"))
                        else -> old.copy(instructions = value("instructions"), defaultQuestion = value("defaultQuestion"), audioQuestion = value("audioQuestion"),
                            transcriptionPrompt = value("transcriptionPrompt"), audioOutput = selected("audioOutput"), textQuestion = value("textQuestion"))
                    }
                    SettingsStore.validate(config); store.save(config)
                    savedCredential = config.apiKey.isNotBlank()
                    fields["apiKey"]?.text?.clear()
                    credentialState?.text = credentialStatus()
                }
                Page.APPEARANCE -> AppAppearance.save(this, selected("appearance"))
                Page.BALL -> interactionStore.save(interactionStore.load().copy(
                    ballSize = ballSize, ballOpacity = ballOpacity, ballColor = ballColor))
                Page.GESTURES -> interactionStore.save(interactionStore.load().copy(
                    singleAction = selected("singleAction"), doubleAction = selected("doubleAction"), tripleAction = selected("tripleAction"), longAction = selected("longAction"),
                    recordingSingleAction = selected("recordingSingleAction"), recordingDoubleAction = selected("recordingDoubleAction"),
                    recordingTripleAction = selected("recordingTripleAction"), recordingLongAction = selected("recordingLongAction")))
                Page.WINDOW -> {
                    if (!readable) { toast("请先在 API 连接修复无法读取的配置"); return }
                    store.save(store.load().copy(autoUpdateImage = checked("autoUpdateImage")))
                    interactionStore.save(interactionStore.load().copy(rememberWindowPosition = checked("rememberWindowPosition"),
                        rememberWindowSize = checked("rememberWindowSize"), defaultPinned = checked("defaultPinned"),
                        autoAskImage = checked("autoAskImage"), autoAskAudio = checked("autoAskAudio"), autoAskText = checked("autoAskText")))
                }
                Page.MEDIA -> interactionStore.save(interactionStore.load().copy(saveHistoryImages = checked("saveHistoryImages")))
                Page.HOME -> return
            }
            dirty = false; status.text = "此分类已保存"; toast("已保存此分类")
            if (page == Page.APPEARANCE) activity.recreate()
        } catch (error: IllegalArgumentException) { toast(error.message ?: "设置内容无效") }
        catch (_: Exception) { status.text = "保存失败，请检查设备安全存储及可用空间。请确认本页内容后重试。" }
    }

    private fun fetchModels(target: String? = null) {
        if (modelRequest != null) return
        val config = try {
            check(readable)
            val stored = store.load()
            (if (page == Page.API) stored.copy(baseUrl = value("baseUrl").trim(), apiKey = value("apiKey").trim().ifBlank { stored.apiKey }) else stored).also {
                SettingsStore.validate(it); require(it.apiKey.isNotBlank()) { "请先输入 API Key，或保存 API 连接" }
            }
        } catch (error: IllegalArgumentException) { toast(error.message ?: "API 配置无效"); return }
        catch (_: Exception) { toast("无法读取连接配置，请先修复 API 连接"); return }
        val token = RequestCancellation(); modelRequest = token
        val currentGeneration = generation
        modelProgress = AlertDialog.Builder(this).setTitle("正在读取模型")
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
                        AlertDialog.Builder(this).setTitle(if (target == "model") "选择图文模型 ID" else "选择转写模型 ID")
                            .setItems(models.toTypedArray()) { _, index -> fields[target]?.setText(models[index]) }
                            .setNegativeButton("取消", null).showTracked()
                    } else {
                        val list = label(models.joinToString("\n"), 15f).apply { setTextIsSelectable(true); setPadding(dp(20), dp(8), dp(20), dp(8)) }
                        AlertDialog.Builder(this).setTitle("服务返回的模型 ID")
                            .setView(ScrollView(this).apply { addView(list) })
                            .setMessage("可长按复制。在“模型与生成参数”中也可直接从此列表选择；获取列表不保存连接草稿。")
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
        AlertDialog.Builder(this).setTitle("移除本机 API Key？")
            .setMessage("立即移除已保存的加密密钥及本页连接草稿。其他已存配置和历史保留；之后需重新填写 Key 才能调用服务。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                try { store.clearCredential(); showPage(Page.API); toast("已移除本机密钥") }
                catch (_: Exception) { toast("移除失败，请检查本机存储") }
            }.showTracked()
    }

    private fun savePreset() {
        val name = EditText(this).apply {
            hint = placeholder("请输入模板名称（最多 60 字）"); isSaveEnabled = false; isSaveFromParentEnabled = false; textSize = 15f
            applyInputStyle()
        }
        val dialog = AlertDialog.Builder(this).setTitle("保存提示词模板").setView(name)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isBlank() || title.length > 60) { toast("名称需为 1 至 60 字"); return@setOnClickListener }
                try {
                    val perform = {
                        try { store.savePreset(title, PromptPreset.capture(promptDraft()))
                            dialog.dismiss(); toast("模板已保存") }
                        catch (_: Exception) { toast("保存失败：最多 20 个模板，单项提示词最多 32000 字") }
                    }
                    if (store.presets().containsKey(title)) AlertDialog.Builder(this).setTitle("覆盖同名模板？").setMessage(title)
                        .setNegativeButton("取消", null).setPositiveButton("覆盖") { _, _ -> perform() }.showTracked()
                    else perform()
                } catch (_: Exception) { toast("无法读取模板") }
            }
        }
        dialog.showTracked()
    }

    private fun loadPreset() {
        try {
            val presets = store.presets()
            if (presets.isEmpty()) { toast("尚未保存模板"); return }
            val names = presets.keys.toTypedArray()
            AlertDialog.Builder(this).setTitle("选择提示词模板").setItems(names) { _, index ->
                val preset = presets.getValue(names[index])
                val merged = preset.mergeInto(promptDraft())
                fields.getValue("instructions").setText(merged.instructions)
                fields.getValue("defaultQuestion").setText(merged.defaultQuestion)
                fields.getValue("audioQuestion").setText(merged.audioQuestion)
                fields.getValue("textQuestion").setText(merged.textQuestion)
                fields.getValue("transcriptionPrompt").setText(merged.transcriptionPrompt)
                setChoice("audioOutput", merged.audioOutput, "音频默认输出", audioOutputOptions)
                markDirty(); status.text = "已使用“${names[index]}”，保存后生效"
            }.setNegativeButton("取消", null).showTracked()
        } catch (_: Exception) { toast("无法读取提示词模板") }
    }

    private fun promptDraft() = AppConfig(instructions = value("instructions"), defaultQuestion = value("defaultQuestion"),
        audioQuestion = value("audioQuestion"), textQuestion = value("textQuestion"),
        transcriptionPrompt = value("transcriptionPrompt"), audioOutput = selected("audioOutput"))

    private val dangerColor: Int get() = if (Color.red(INK) > 160) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()

    private fun Button.destructive() {
        applyActionStyle()
        setTextColor(dangerColor)
        background = card(BACKGROUND, dp(8).toFloat()).apply { setStroke(dp(1).coerceAtLeast(1), dangerColor) }
    }

    private fun section(text: String) {
        body.addView(label(text, 14f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(4) })
    }
    private fun note(text: String) {
        body.addView(label(text, 11f, true).apply { setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(5) })
    }
    private fun value(name: String) = fields.getValue(name).text.toString()
    private fun checked(name: String) = checks.getValue(name).isChecked
    private fun selected(name: String) = selections.getValue(name)
    private fun optionalNumber(name: String): Double? = value(name).trim().takeIf { it.isNotEmpty() }?.let {
        it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw IllegalArgumentException("$name 请填写有效数值或留空")
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    private fun clearDraft() {
        rendering = true
        fields.values.forEach { it.text.clear() }
        fields.clear(); checks.clear(); selections.clear(); ballPreview = null; colorPalette = null; credentialState = null; saveControl = null
        rendering = false
        dirty = false
    }

    private fun AlertDialog.Builder.showTracked(): AlertDialog = create().showTracked()

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
