package com.alwayside.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CancellationException

/** Category pages intentionally keep their draft only in the current view, never saved instance state. */
class SettingsActivity : Activity() {
    private enum class Page(val title: String, val description: String, val icon: Int) {
        HOME("设置", "", android.R.drawable.ic_menu_preferences),
        API("API 连接", "服务地址、加密密钥与可用模型", android.R.drawable.ic_menu_manage),
        MODELS("模型与生成参数", "图文与转写模型、推理、搜索及采样", android.R.drawable.ic_menu_sort_by_size),
        PROMPTS("提示词", "截图与选中文字问题、转写提示与音频输出", android.R.drawable.ic_menu_edit),
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
    private var page = Page.HOME
    private var dirty = false
    private var rendering = false
    private var readable = true
    private var ballSize = 28
    private var ballOpacity = 55
    private var ballPreview: View? = null
    private var modelRequest: RequestCancellation? = null
    private var modelProgress: AlertDialog? = null
    private var generation = 0
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SettingsStore(this)
        interactionStore = InteractionPreferences(this)
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        showPage(Page.HOME)
    }

    // API 33+ navigation is handled by the OnBackInvokedCallback registered in onCreate.
    @Deprecated("Legacy API 29–32 bridge; API 33+ uses OnBackInvokedCallback")
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) navigateBack() else super.onBackPressed()
    }

    private fun navigateBack() {
        if (page == Page.HOME) { finish(); return }
        if (!dirty) { showPage(Page.HOME); return }
        AlertDialog.Builder(this).setTitle("放弃此页未保存的修改？")
            .setMessage("返回分类后，此页草稿会丢弃。密钥草稿不会带到其他页面。")
            .setNegativeButton("继续编辑", null).setPositiveButton("放弃并返回") { _, _ -> showPage(Page.HOME) }.show()
    }

    private fun showPage(next: Page) {
        cancelModelRequest()
        fields["apiKey"]?.text?.clear()
        fields.clear(); checks.clear(); selections.clear(); ballPreview = null
        page = next; generation++; rendering = true; dirty = false
        body = column().apply { setPadding(dp(22), dp(12), dp(22), dp(24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if(next == Page.HOME)22 else 8), dp(12), dp(10), dp(10))
            if(next != Page.HOME) addView(iconButton(android.R.drawable.ic_media_previous, "返回设置分类") { navigateBack() })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (next == Page.HOME) "AlwaySide" else next.title, if (next == Page.HOME) 20f else 18f).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                if (next == Page.HOME) addView(label("留在当下，随手问一句。", 11f, true).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if(next != Page.HOME) leftMargin = dp(8) })
            if (next != Page.HOME) addView(iconButton(android.R.drawable.ic_menu_save, "保存此分类") { savePage() })
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
            Page.BALL -> ballPage(interaction)
            Page.GESTURES -> gesturePage(interaction)
            Page.WINDOW -> windowPage(config, interaction)
            Page.MEDIA -> mediaPage(interaction)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true; isSaveEnabled = false; clipToPadding = false; addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        rendering = false; dirty = false
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun homeTabs() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(22), 0, dp(22), 0)
            listOf("对话记录", "设置").forEachIndexed { index, title ->
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 13f).apply {
                        gravity = Gravity.CENTER; minHeight = dp(44); setTextColor(if (index == 1) ACCENT else MUTED)
                        isSelected = index == 1; isClickable = true; isFocusable = true; background = touchFeedback()
                        setOnClickListener {
                            if (index == 0) {
                                startActivity(Intent(this@SettingsActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                finish()
                            }
                        }
                    }, LinearLayout.LayoutParams(-1, dp(44)))
                    addView(View(this@SettingsActivity).apply { setBackgroundColor(if (index == 1) ACCENT else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(2)))
                }, LinearLayout.LayoutParams(dp(if (index == 0) 64 else 44), -2).apply { rightMargin = dp(20) })
            }
        })
        addView(divider())
    }

    private fun category(value: Page) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(0, dp(14), 0, dp(14)); background = touchFeedback()
            isClickable = true; isFocusable = true; contentDescription = "${value.title}，${value.description}，进入详情"
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(value.title, 14f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(label(value.description, 11f, true).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            addView(android.widget.ImageView(this@SettingsActivity).apply {
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
        input("baseUrl", "HTTPS Base URL", "https://api.openai.com/v1", config.baseUrl)
        note("地址须以 /v1 结尾，不含账号、查询参数或片段。密钥会发送到此地址，请确认服务可信。")
        input("apiKey", "API Key", if (readable && config.apiKey.isNotBlank()) "已有加密密钥；留空保留原值" else "输入服务提供的 API Key", secret = true)
        note("Key 仅由 Android KeyStore 加密保存在本机。本页禁止截图，密钥不自动填充、不保存界面状态。留空保留已存密钥；移除请用下方专用操作。")
        body.action("读取可用模型列表") { fetchModels() }
        note("仅点击时发送经认证的 GET /models，不会上传屏幕、音频或聊天。服务列出模型不代表它支持所有参数。")
        body.action("移除已保存的 API Key") { clearCredential() }
        if (!readable) body.action("重置连接与模型配置") {
            AlertDialog.Builder(this).setTitle("重置无法读取的配置？")
                .setMessage("将清除已存 API 连接、模型、提示词及加密密钥并恢复默认值。交互设置、预设和历史保留。不会发送请求。")
                .setNegativeButton("取消", null).setPositiveButton("重置") { _, _ ->
                    try { store.resetConfiguration(); showPage(Page.API); toast("已重置，请重新填写密钥") }
                    catch (_: Exception) { toast("重置失败，请检查本机存储") }
                }.show()
        }
    }

    private fun modelPage(config: AppConfig) {
        input("model", "图文模型 ID", "手动输入服务支持的模型 ID", config.model)
        body.action("从列表选择图文模型") { fetchModels("model") }
        input("transcriptionModel", "转写模型 ID", "手动输入转写模型 ID", config.transcriptionModel)
        body.action("从列表选择转写模型") { fetchModels("transcriptionModel") }
        note("列表使用已保存的 API 地址与密钥；请先保存 API 连接。模型用途与能力由服务决定，列表不作兼容性筛选。")
        choice("reasoning", "推理程度", config.reasoning, linkedMapOf("" to "不设置（模型默认）", "none" to "none", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high", "xhigh" to "xhigh", "max" to "max"))
        choice("verbosity", "回答详细程度", config.verbosity, linkedMapOf("" to "不设置（模型默认）", "low" to "low · 简短", "medium" to "medium · 适中", "high" to "high · 详细"))
        input("maxOutputTokens", "最大输出 tokens（可留空）", "留空或 0：不发送限制", config.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), numeric = true)
        note("填写时范围为 16–131072，受具体模型上限约束。留空不发送 max_output_tokens。")
        check("webSearch", "请求联网搜索", config.webSearch)
        note("请求 Responses API 的 web_search 工具，需要服务和模型支持，可能增加费用。回答与引用以服务实际返回为准。")
        input("temperature", "temperature（可留空，0–2）", "例如 0.7", config.temperature?.toString().orEmpty(), decimal = true)
        input("topP", "top_p（可留空，0–1）", "例如 0.9", config.topP?.toString().orEmpty(), decimal = true)
        note("temperature 与 top_p 只能设置一项。未设置的可选参数不会发送；不支持的参数会显示 API 错误，不会悄悄删除后重试。")
    }

    private fun promptPage(config: AppConfig) {
        input("instructions", "Instructions · 助手回答方式", "告诉助手如何回答", config.instructions, multiline = true)
        input("defaultQuestion", "截图默认问题", "自动截图问答时使用", config.defaultQuestion, multiline = true)
        input("textQuestion", "选中文字默认问题", "从系统选中文字入口打开时使用", config.textQuestion, multiline = true)
        input("audioQuestion", "音频默认问题", "转写后翻译或解读时使用", config.audioQuestion, multiline = true)
        input("transcriptionPrompt", "转写提示（可留空）", "例如专有名词、背景或语言提示", config.transcriptionPrompt, multiline = true)
        note("转写提示发送给转写模型，用于保留原语言文字；它不等同于翻译或解读指令。")
        choice("audioOutput", "音频默认输出", config.audioOutput, linkedMapOf("transcript" to "仅原始转写", "translate" to "原文 + 翻译", "interpret" to "原文 + 翻译与解读"))
        note("音频原始转写与 AI 输出分别保存、展示。选择“仅原始转写”时跳过初始翻译/解读请求，仍可继续追问。")
        section("提示词预设")
        note("命名预设包含 Instructions、截图默认问题和音频默认问题三项，不包含密钥、模型、选中文字默认问题、转写提示或音频输出选项。加载后需保存此分类。")
        body.action("保存三项为命名预设") { savePreset() }
        body.action("加载三项提示词预设") { loadPreset() }
        body.action("恢复本页默认值（先预览）") {
            AlertDialog.Builder(this).setTitle("恢复默认提示词？").setMessage("仅修改此页草稿，保存此分类后生效。命名预设保留。")
                .setNegativeButton("取消", null).setPositiveButton("恢复草稿") { _, _ ->
                    val d = AppConfig()
                    fields.getValue("instructions").setText(d.instructions)
                    fields.getValue("defaultQuestion").setText(d.defaultQuestion)
                    fields.getValue("audioQuestion").setText(d.audioQuestion)
                    fields.getValue("textQuestion").setText(d.textQuestion)
                    fields.getValue("transcriptionPrompt").setText(d.transcriptionPrompt)
                    selections["audioOutput"] = d.audioOutput
                    // Refresh only this selector's visible value without discarding edited fields.
                    body.findViewWithTag<TextView>("audioOutput")?.text = "音频默认输出：原文 + 翻译与解读"
                    dirty = true; status.text = "已恢复此页草稿，尚未保存"
                }.show()
        }
    }

    private fun ballPage(config: InteractionConfig) {
        ballSize = config.ballSize; ballOpacity = config.ballOpacity
        section("外观预览")
        val previewArea = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = card(SURFACE, dp(8).toFloat())
            ballPreview = View(this@SettingsActivity).also { addView(it) }
        }
        body.addView(previewArea, LinearLayout.LayoutParams(-1, dp(100)))
        slider("视觉直径", 18, 48, ballSize, "dp") { ballSize = it; updateBallPreview() }
        slider("不透明度", 15, 100, ballOpacity, "%") { ballOpacity = it; updateBallPreview() }
        input("ballColor", "球体颜色（#RRGGBB）", "#72968E", String.format(Locale.ROOT, "#%06X", config.ballColor and 0xFFFFFF))
        fields.getValue("ballColor").addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateBallPreview() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateBallPreview()
        note("预览即时变化；保存后应用到正在运行的悬浮球。默认 28 dp、55% 不透明度；触摸范围保留足够大小，录音状态会增强可见性。")
    }

    private fun gesturePage(config: InteractionConfig) {
        note("单击、双击、三击、长按与拖动互斥。为了识别三击，单击与双击会有短暂等待。拖动只移动悬浮球。")
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
        note("“取消并丢弃录音”立即丢弃当前片段，不上传、不转写、不提问。“开始／结束录音并处理”在录音中表示结束并处理。设置始终可从应用主页进入。")
    }

    private fun windowPage(config: AppConfig, interaction: InteractionConfig) {
        check("rememberWindowPosition", "分别记住截图窗与音频窗的位置", interaction.rememberWindowPosition)
        check("rememberWindowSize", "分别记住截图窗与音频窗的尺寸", interaction.rememberWindowSize)
        check("defaultPinned", "新会话窗口默认固定", interaction.defaultPinned)
        note("固定后点外部保留窗口，仍可拖动和调整尺寸；取消固定后点外部收起。收起不取消请求，结果继续写入对话记录。未记住的尺寸/位置下次恢复默认。")
        section("首问与截图上下文")
        check("autoAskImage", "截图后自动使用默认问题提问", interaction.autoAskImage)
        check("autoAskAudio", "结束录音后自动按默认设置处理", interaction.autoAskAudio)
        check("autoAskText", "选中文字后自动提问", interaction.autoAskText)
        note("关闭自动提问时先打开会话，等待你明确发送；取消录音始终直接丢弃片段。")
        check("autoUpdateImage", "截图追问默认更新当前画面", config.autoUpdateImage)
        note("仅在主动发送时采集新画面，需要有效的屏幕共享。截图会话中仍可逐次选择沿用原图、更新画面或仅文字。")
    }

    private fun mediaPage(config: InteractionConfig) {
        check("saveHistoryImages", "随历史长期保留截图", config.saveHistoryImages)
        note("默认关闭。当前截图会话仍可在内存中沿用原图；不保存图片时，从历史恢复后会提示缺少原图，可重新取图或只用文字追问。")
        note("此开关决定新写入历史的截图策略，不追溯补存旧截图。关闭后已有历史截图是否可用，以该会话实际保存的证据为准。")
        section("音频与对话记录")
        note("原始音频只用于本次处理，不进入长期历史；历史保存原始转写、问题和 AI 回答。后续音频追问复用转写文字，不重复上传音频。")
        note("每次初始查询形成一条会话，追问追加到原会话。待提问、进行中、取消或失败的会话也可从应用主页的对话列表找到。")
    }

    private fun input(name: String, title: String, hintValue: String, initial: String = "", multiline: Boolean = false,
                      secret: Boolean = false, numeric: Boolean = false, decimal: Boolean = false) {
        val titleView = label(title, 12f)
        val field = EditText(this).apply {
            id = View.generateViewId(); hint = hintValue; setTextColor(INK); setHintTextColor(MUTED)
            textSize = 13f; minHeight = dp(44)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = card(SURFACE, dp(8).toFloat()).apply { setStroke(dp(1), BORDER) }
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
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!rendering) dirty = true }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        titleView.labelFor = field.id; fields[name] = field
        body.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(6) })
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
    }

    private fun check(name: String, title: String, initial: Boolean) {
        val view = CheckBox(this).apply {
            text = title; textSize = 13f; setTextColor(INK); minHeight = dp(44)
            buttonTintList = ColorStateList.valueOf(ACCENT); setPadding(0, dp(4), 0, dp(4))
            isSaveEnabled = false; isChecked = initial
            setOnCheckedChangeListener { _, _ -> if (!rendering) dirty = true }
        }
        checks[name] = view; body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun choice(name: String, title: String, initial: String, options: Map<String, String>) {
        selections[name] = initial
        val button = body.action("$title：${options[initial] ?: initial}") {}
        button.textSize = 13f; button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        button.setPadding(dp(10), 0, dp(10), 0); button.minHeight = dp(44)
        button.background = card(SURFACE, dp(8).toFloat()).apply { setStroke(dp(1), BORDER) }
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44) }
        button.tag = name
        button.setOnClickListener {
            val keys = options.keys.toList()
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(options.values.toTypedArray(), keys.indexOf(selections[name])) { dialog, index ->
                selections[name] = keys[index]; button.text = "$title：${options.getValue(keys[index])}"
                dirty = true; dialog.dismiss()
            }.setNegativeButton("取消", null).show()
        }
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
                    if (fromUser && !rendering) dirty = true
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun updateBallPreview() {
        val color = fields["ballColor"]?.text?.toString()?.trim()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
            ?.let { Color.parseColor(it) } ?: InteractionConfig().ballColor
        ballPreview?.apply {
            background = card(color, dp(48).toFloat()); alpha = ballOpacity / 100f
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
                    fields["apiKey"]?.apply { text.clear(); hint = if (config.apiKey.isBlank()) "输入服务提供的 API Key" else "已有加密密钥；留空保留原值" }
                }
                Page.BALL -> {
                    val hex = value("ballColor").trim()
                    require(hex.matches(Regex("#[0-9a-fA-F]{6}"))) { "颜色请填写 #RRGGBB，例如 #72968E" }
                    interactionStore.save(interactionStore.load().copy(ballSize = ballSize, ballOpacity = ballOpacity, ballColor = Color.parseColor(hex)))
                }
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
            dirty = false; status.text = "此分类已保存；之后的操作使用新配置。"; toast("已保存此分类")
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
            .setNegativeButton("取消") { _, _ -> cancelModelRequest() }.setOnCancelListener { cancelModelRequest() }.show()
        Thread({
            try {
                val models = AiProvider().listModels(config, token)
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != currentGeneration || modelRequest !== token) return@runOnUiThread
                    modelRequest = null; modelProgress?.dismiss(); modelProgress = null
                    if (models.isEmpty()) { toast("服务未返回可用模型，可手动填写模型 ID"); return@runOnUiThread }
                    if (target != null) {
                        AlertDialog.Builder(this).setTitle(if (target == "model") "选择图文模型 ID" else "选择转写模型 ID")
                            .setItems(models.toTypedArray()) { _, index -> fields[target]?.setText(models[index]) }
                            .setNegativeButton("取消", null).show()
                    } else {
                        val list = label(models.joinToString("\n"), 15f).apply { setTextIsSelectable(true); setPadding(dp(20), dp(8), dp(20), dp(8)) }
                        AlertDialog.Builder(this).setTitle("服务返回的模型 ID")
                            .setView(ScrollView(this).apply { addView(list) })
                            .setMessage("可长按复制。在“模型与生成参数”中也可直接从此列表选择；获取列表不保存连接草稿。")
                            .setPositiveButton("完成", null).show()
                    }
                }
            } catch (_: CancellationException) { /* Explicit cancellation has no user-facing error. */ }
            catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != currentGeneration || modelRequest !== token) return@runOnUiThread
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
                    if (store.presets().containsKey(title)) AlertDialog.Builder(this).setTitle("覆盖同名预设？").setMessage(title)
                        .setNegativeButton("取消", null).setPositiveButton("覆盖") { _, _ -> perform() }.show()
                    else perform()
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
                status.text = "已加载“${names[index]}”，保存此分类后生效"
            }.setNegativeButton("取消", null).show()
        } catch (_: Exception) { toast("无法读取提示词预设") }
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

    override fun onDestroy() {
        cancelModelRequest(); fields["apiKey"]?.text?.clear(); fields.clear()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        super.onDestroy()
    }
}
