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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
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
    private val onTutorial: () -> Unit = {},
    private val onPreparation: () -> Unit = {},
    private val onHelp: () -> Unit = {},
) : ContextWrapper(activity) {
    private enum class Page(val title: String, val description: String, val icon: Int) {
        HOME("设置", "", android.R.drawable.ic_menu_preferences),
        API("API 连接", "服务地址与密钥", android.R.drawable.ic_menu_manage),
        MODELS("模型与生成参数", "模型、搜索与生成参数", android.R.drawable.ic_menu_sort_by_size),
        PROMPTS("提示词", "默认问题与音频输出", android.R.drawable.ic_menu_edit),
        APPEARANCE("应用外观", "跟随系统、浅色或深色主题", android.R.drawable.ic_menu_gallery),
        BALL("悬浮球外观", "大小、颜色与透明度预览", android.R.drawable.ic_menu_view),
        GESTURES("快捷动作", "空闲与录音中的手势", android.R.drawable.ic_menu_directions),
        WINDOW("结果窗口", "窗口行为与自动提问", android.R.drawable.ic_menu_crop),
        MEDIA("历史与媒体", "录音上限与媒体保留", android.R.drawable.ic_menu_recent_history),
        HELP("帮助与引导", "新手引导与使用准备", android.R.drawable.ic_menu_help),
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
    private val audioOutputOptions = linkedMapOf("transcript" to "仅原始转写", "translate" to "原文 + 中文翻译", "interpret" to "原文 + 按音频问题回答")
    private var page = Page.HOME
    private var readable = true
    private var ballSize = 36
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
    private var renderedConnectionId: String? = null
    private var editorConnectionId: String? = null
    private var editorTranscriptionId: String? = null
    private var diagnosticRequest: RequestCancellation? = null
    private var diagnosticProgress: AlertDialog? = null

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
    fun consumeEditorBack(): Boolean {
        if(activeEdit==null)return false
        finishEditing {};return true
    }

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
        cancelDiagnostic()
        dismissDialogs()
        clearDraft()
        page = next; generation++; group = null
        body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
        }
        // The host handles both toolbar and system Back, including direct entries from preparation.
        val header = materialToolbar(next.title, onBack).apply {
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
        val config = if(next == Page.HELP) AppConfig().also { readable = true }
            else try { store.load().also { readable = true } } catch (_: Exception) { readable = false; AppConfig() }
        val interaction = interactionStore.load()
        renderedConnectionId = config.connectionId
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
            Page.HELP -> {
                note("通过内置示例了解悬浮球、截图问答和追问。全程模拟，不联网，不调用 API，也不需要系统权限。")
                action("查看新手引导") { finishEditing(onTutorial) }.apply { applyOnboardingPrimaryStyle() }
                action("准备实际使用") { finishEditing(onPreparation) }.apply { applyOnboardingPrimaryStyle() }
            }
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
            setOnClickListener { if(value==Page.HELP)onHelp() else showPage(value) }
        }
        addRow(row)
    }

    private fun apiPage(config: AppConfig) {
        savedCredential = readable && config.apiKey.isNotBlank()
        section("已保存连接", "每个连接分别保存服务地址、协议、模型、生成参数与密钥。切换连接不会把另一家服务的密钥带过来。")
        if (readable) {
            ProviderConnections.snapshot(config).forEach { profile ->
                selectionRow(if (profile.id == config.connectionId) "${profile.name} · 当前使用" else profile.name,
                    "${profile.providerKind.title} · ${profile.model.ifBlank { "未选择模型" }}") {
                    finishEditing {
                        if (profile.id != config.connectionId && mutateConnection(successMessage = "图文问答已切换到 ${profile.name}") {
                            ProviderConnections.select(it, profile.id)
                        }) showPage(Page.API)
                    }
                }
            }
            val controls = LinearLayout(this).apply {
                gravity = Gravity.END
                addView(iconButton(R.drawable.ic_line_plus, "添加服务连接") { finishEditing { addConnection() } })
                addView(iconButton(R.drawable.ic_line_trash, "删除当前连接") { finishEditing { deleteConnection(config) } })
            }
            addRow(controls)
        }
        section("连接信息", "密钥会发送到此地址，请只连接可信服务。\n\n密钥加密保存在本机；编辑时输入新密钥。\n\n查看模型列表会联网，但不上传聊天或媒体。")
        note(ProviderCapabilities.label(config))
        input("connectionName", "连接名称", "为此连接取一个容易区分的名称", config.connectionName)
        input("baseUrl", "服务地址", "示例：https://api.openai.com/v1", config.baseUrl)
        note(when (config.providerKind) {
            ProviderKind.QWEN -> "百炼地址需与 API Key 所属地域、工作空间一致。北京默认地址为 https://dashscope.aliyuncs.com/compatible-mode/v1；国际与其他地域请复制控制台对应的基础地址。更换域名会清空当前密钥。"
            ProviderKind.GEMINI -> "原生接口使用 https://generativelanguage.googleapis.com/v1beta；Chat 兼容接口使用其 /openai 地址。"
            ProviderKind.CLAUDE -> "Claude 官方基础地址：https://api.anthropic.com/v1。"
            ProviderKind.DEEPSEEK -> "DeepSeek 官方基础地址：https://api.deepseek.com。"
            else -> "填写 HTTPS 基础地址，通常以 /v1 结尾；不要附加 /responses 或 /chat/completions。"
        })
        input("apiKey", "API 密钥", "请输入密钥", secret = true)
        section("接口协议", "协议决定应用如何与服务交换问题、图片和回答。相同的服务可能提供多种协议；按服务文档选择，不会自动尝试其他接口。")
        selectionRow("当前协议", "${config.apiProtocol.title}\n${protocolDescription(config.apiProtocol)}") { finishEditing { selectProtocol(config) } }
        if (config.apiProtocol == ApiProtocol.CLAUDE) {
            input("apiVersion", "Claude API 版本", "YYYY-MM-DD，例如 2026-10-21", config.apiVersion)
            input("workspaceId", "Claude Workspace ID（可选）", "留空使用密钥默认工作空间", config.workspaceId)
        }
        action("查看可用模型") { fetchModels() }
        action("测试当前连接") { finishEditing { chooseDiagnostic() } }
        note("模型列表只证明列表接口可访问。文字、图片、搜索需分别测试；测试使用本机生成的示例，不读取屏幕，也不写入聊天历史。")
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

    private fun selectionRow(title: String, value: String, run: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(dp(16), dp(12), dp(16), dp(12))
            background = touchFeedback(); isClickable = true; isFocusable = true
            contentDescription = "$title，$value"
            addView(column().apply {
                addView(label(title, 16f))
                addView(label(value, 14f, true).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ImageView(this@SettingsScreen).apply {
                setImageDrawable(prototypeIcon(android.R.drawable.ic_media_next))
                imageTintList = ColorStateList.valueOf(MUTED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            setOnClickListener { run() }
        }
        addRow(row)
    }

    private fun addConnection() {
        val providers = ProviderKind.entries
        MaterialAlertDialogBuilder(this).setTitle("添加服务连接")
            .setItems(providers.map { it.title }.toTypedArray()) { _, index ->
                if (mutateConnection { ProviderConnections.add(it, providers[index]) }) showPage(Page.API)
            }.setNegativeButton("取消", null).showTracked()
    }

    private fun deleteConnection(config: AppConfig) {
        MaterialAlertDialogBuilder(this).setTitle("删除“${config.connectionName}”？")
            .setMessage("删除这个连接的地址、模型、生成参数及本机密钥；使用它的转写绑定会解除。历史记录保留。至少需要保留一个连接。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                if (mutateConnection { ProviderConnections.remove(it, config.connectionId) }) showPage(Page.API)
            }.showTracked()
    }

    private fun protocolDescription(protocol: ApiProtocol): String = when (protocol) {
        ApiProtocol.RESPONSES -> "以统一输入发送文字与图片，接收回答、工具执行结果和引用。"
        ApiProtocol.CHAT_COMPLETIONS -> "使用对话消息与图片内容，适合提供 OpenAI 兼容接口的服务；扩展功能按供应商支持。"
        ApiProtocol.GEMINI -> "使用 Google 的原生文字、图片内容与系统说明，联网搜索使用 Google Search。"
        ApiProtocol.CLAUDE -> "使用 Anthropic 的系统说明与内容块，图片、搜索引用和暂停续接遵循 Claude 格式。"
    }

    private fun selectProtocol(config: AppConfig) {
        val protocols = when (config.providerKind) {
            ProviderKind.DEEPSEEK -> listOf(ApiProtocol.RESPONSES)
            ProviderKind.GEMINI -> listOf(ApiProtocol.GEMINI, ApiProtocol.CHAT_COMPLETIONS)
            ProviderKind.CLAUDE -> listOf(ApiProtocol.CLAUDE)
            else -> listOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)
        }
        MaterialAlertDialogBuilder(this).setTitle("选择接口协议")
            .setSingleChoiceItems(protocols.map { "${it.title}\n${protocolDescription(it)}" }.toTypedArray(), protocols.indexOf(config.apiProtocol)) { dialog, index ->
                val selected = protocols[index]
                if (selected == config.apiProtocol) { dialog.dismiss(); return@setSingleChoiceItems }
                dialog.dismiss()
                MaterialAlertDialogBuilder(this).setTitle("切换到 ${selected.title}？")
                    .setMessage("保留此连接的密钥和模型，清空推理、详细程度、采样、输出上限与搜索设置。Gemini 官方地址会随协议调整；自定义地址需按服务说明自行核对。")
                    .setNegativeButton("取消", null).setPositiveButton("切换") { _, _ ->
                        if (mutateConnection { current ->
                            val officialGemini = current.providerKind == ProviderKind.GEMINI && current.baseUrl.trimEnd('/') in setOf(
                                "https://generativelanguage.googleapis.com/v1beta", "https://generativelanguage.googleapis.com/v1beta/openai")
                            current.copy(apiProtocol = selected, credentialRevision = current.credentialRevision + 1,
                                baseUrl = if (officialGemini) "https://generativelanguage.googleapis.com/v1beta" + (if (selected == ApiProtocol.CHAT_COMPLETIONS) "/openai" else "") else current.baseUrl,
                                maxOutputTokens = 0, reasoning = "", verbosity = "", temperature = null, topP = null, webSearch = false,
                                continuationMessages = emptyList())
                        }) showPage(Page.API)
                    }.showTracked()
            }.setNegativeButton("取消", null).showTracked()
    }

    private fun selectTranscriber(config: AppConfig) {
        val profiles = ProviderConnections.snapshot(config).filter { it.apiProtocol in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS) }
        val ids = listOf<String?>(null) + profiles.map { it.id }
        val labels = listOf("不绑定（暂停音频转写）") + profiles.map { "${it.name} · ${it.providerKind.title}\n${it.transcriptionModel.ifBlank { "未选择转写模型" }}" }
        MaterialAlertDialogBuilder(this).setTitle("音频转写连接")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(config.transcriptionConnectionId)) { dialog, index ->
                if (mutateConnection { it.copy(transcriptionConnectionId = ids[index]) }) {
                    dialog.dismiss(); showPage(Page.MODELS)
                }
            }.setNegativeButton("取消", null).showTracked()
    }

    /** Reuse each codec's local validation so model-specific option lists cannot drift from requests. */
    private fun reasoningOptions(config: AppConfig): Map<String, String> {
        val options = linkedMapOf("" to "不设置（模型默认）")
        val adapter: ChatAdapter = when (config.apiProtocol) {
            ApiProtocol.RESPONSES -> ResponsesAdapter
            ApiProtocol.CHAT_COMPLETIONS -> ChatCompletionsAdapter
            ApiProtocol.GEMINI -> GeminiAdapter
            ApiProtocol.CLAUDE -> ClaudeMessagesAdapter()
        }
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max").forEach { value ->
            val candidate = config.copy(reasoning = value, maxOutputTokens = 0, verbosity = "", temperature = null, topP = null, webSearch = false, continuationMessages = emptyList())
            if (runCatching { adapter.request(candidate, emptyList(), "参数检查", null) }.isSuccess) options[value] = value
        }
        return options
    }

    private fun modelPage(config: AppConfig) {
        note(ProviderCapabilities.label(config))
        section("模型", "列表使用已保存的连接。请按服务说明选择图文和转写模型，列表中的模型不一定支持两种用途。")
        input("model", "图文模型", "请输入模型 ID", config.model)
        action("从列表选择图文模型") { fetchModels("model") }
        val transcriber = runCatching { ProviderConnections.transcription(config) }.getOrNull()
        selectionRow("音频转写连接", transcriber?.connectionName ?: "未选择") { finishEditing { selectTranscriber(config) } }
        note("转写使用单独绑定的文件转写接口；音频转成文字后，由当前图文连接回答。Gemini 和 Claude 原生接口不接收此文件转写请求。")
        if (transcriber != null) {
            input("transcriptionModel", "转写模型", "请输入所选转写连接支持的模型 ID", transcriber.transcriptionModel)
            action("从列表选择转写模型") { fetchModels("transcriptionModel") }
        }
        section("生成参数", "留空或不设置时使用模型默认值，部分参数需服务支持。\n\n输出上限受模型限制；问答窗口可单独切换联网搜索。")
        choice("reasoning", "推理程度", config.reasoning, reasoningOptions(config))
        note(when (config.apiProtocol) {
            ApiProtocol.CLAUDE -> "Claude 的 effort 控制总体推理投入；none 表示关闭思考，部分新模型不允许关闭。4.6 不支持 xhigh，4.5 系列也不通用支持 effort；不确定时选不设置。"
            ApiProtocol.GEMINI -> "Gemini 可用思考档位随模型变化；none 与 minimal 不等价，Pro 模型可能无法关闭思考。未知模型请选择不设置。"
            else -> if (config.providerKind == ProviderKind.QWEN) "千问的推理开关与 effort 按模型提供；旧混合模型通常仅支持 none 关闭。未知模型请选择不设置。" else "推理档位需模型明确支持；不确定时选不设置。"
        })
        if (ProviderCapabilities.verbosity(config) || config.verbosity.isNotBlank()) {
            choice("verbosity", "回答详细程度", config.verbosity, if (ProviderCapabilities.verbosity(config)) linkedMapOf("" to "不设置（模型默认）", "low" to "low · 简短", "medium" to "medium · 适中", "high" to "high · 详细") else linkedMapOf("" to "不设置（清除不支持的值）"))
        }
        input("maxOutputTokens", "输出上限（tokens）", "16–131072；留空使用模型默认，Claude 留空使用 8192", config.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), numeric = true)
        if (ProviderCapabilities.search(config) || config.webSearch) check("webSearch", "默认联网搜索", config.webSearch)
        note(if (ProviderCapabilities.search(config)) "联网搜索需服务与模型支持，可能增加费用。请求完成不一定表示实际执行了搜索。" else "当前协议或模型不支持联网搜索；已保存的开关可在此关闭。")
        action("分别测试文字、图片与搜索") { finishEditing { chooseDiagnostic() } }
        section("采样参数", "一般留空；需要时只调整一项。\n\nTemperature 越低越稳定；Top P 越小，用词范围越窄。部分模型不支持。")
        if (ProviderCapabilities.sampling(config) || config.temperature != null || config.topP != null) {
            input("temperature", "Temperature", if (ProviderCapabilities.sampling(config)) "0–2；Claude 为 0–1，留空使用默认值" else "当前版本不支持，请清空", config.temperature?.toString().orEmpty(), decimal = true)
            input("topP", "Top P", if (ProviderCapabilities.sampling(config)) "0–1，留空使用默认值" else "当前版本不支持，请清空", config.topP?.toString().orEmpty(), decimal = true)
        } else note("当前 Claude API 版本已移除采样参数。")
    }

    private fun promptPage(config: AppConfig) {
        section("回复规则")
        input("instructions", "助手回答方式", "设置语气、格式及希望助手遵循的要求", config.instructions, multiline = true)
        section("快捷提问")
        input("defaultQuestion", "截图问题", "截图后自动填入的问题", config.defaultQuestion, multiline = true)
        input("textQuestion", "选中文字问题", "选中文字后自动填入的问题", config.textQuestion, multiline = true)
        input("audioQuestion", "音频问题", "用于“原文 + 按音频问题回答”，可要求翻译、总结或提取要点", config.audioQuestion, multiline = true)
        section("音频处理", "转写提示帮助识别语言和专有名词。\n\n仅转写和翻译不使用「音频问题」；按问题回答时才使用。原文可展开查看并继续追问。")
        input("transcriptionPrompt", "转写提示", "示例：专有名词、背景或语言", config.transcriptionPrompt, multiline = true)
        lateinit var outputNote: TextView
        choice("audioOutput", "音频默认输出", config.audioOutput, audioOutputOptions) { value ->
            outputNote.text = audioOutputDescription(value)
        }
        outputNote = note(audioOutputDescription(config.audioOutput))
    }

    private fun audioOutputDescription(value: String): String = when (value) {
        "transcript" -> "只转写，不生成回答。"
        "translate" -> "将转写原文翻译成中文。"
        else -> "根据转写原文和「音频问题」回答。"
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
        section("大小与透明度", "滑块松手保存，颜色确认即保存。\n\n视觉大小不影响触摸范围。")
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
    }

    private fun gesturePage(config: InteractionConfig) {
        section("空闲时", "单击和双击会短暂等待，以识别连续点击。拖动只移动悬浮球。")
        choice("singleAction", "单击", config.singleAction, AssistantActions.choices)
        choice("doubleAction", "双击", config.doubleAction, AssistantActions.choices)
        choice("tripleAction", "三击", config.tripleAction, AssistantActions.choices)
        choice("longAction", "长按", config.longAction, AssistantActions.choices)
        section("录音中", "取消录音会直接丢弃，不上传。\n\n「开始／结束录音并处理」会结束录音，再按自动处理设置执行。")
        choice("recordingSingleAction", "单击", config.recordingSingleAction, AssistantActions.choices)
        choice("recordingDoubleAction", "双击", config.recordingDoubleAction, AssistantActions.choices)
        choice("recordingTripleAction", "三击", config.recordingTripleAction, AssistantActions.choices)
        choice("recordingLongAction", "长按", config.recordingLongAction, AssistantActions.choices)
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
        section("窗口行为", "收起的输入框可点「继续提问」展开，下次呼出时应用设置。\n\n固定后点窗口外仍保留窗口；收起窗口不会取消请求。")
        check("collapseFollowUpByDefault", "小窗口追问输入框默认收起", interaction.collapseFollowUpByDefault)
        check("rememberWindowPosition", "分别记住截图窗与音频窗的位置", interaction.rememberWindowPosition)
        check("rememberWindowSize", "分别记住截图窗与音频窗的尺寸", interaction.rememberWindowSize)
        check("defaultPinned", "新会话窗口默认固定", interaction.defaultPinned)
        section("自动提问", "还需开启悬浮球菜单中的「使用默认提问方式」。\n\n关闭总开关或对应选项后，先填写问题再发送。取消录音始终丢弃。")
        check("autoAskImage", "截图后自动使用默认问题提问", interaction.autoAskImage)
        check("autoAskAudio", "手动结束录音后自动按默认设置处理", interaction.autoAskAudio)
        check("autoAskText", "选中文字后自动提问", interaction.autoAskText)
        section("截图追问", "发送时采集新画面，需开启屏幕共享。\n\n会话中可改为沿用原图或仅发文字。")
        check("autoUpdateImage", "截图追问默认更新当前画面", config.autoUpdateImage)
    }

    private fun mediaPage(config: InteractionConfig) {
        section("录音", "时长和上限策略从下次录音生效；0 表示不限时长。\n\n自动处理还需开启菜单中的「使用默认提问方式」；手动结束的行为在「结果窗口」设置。")
        input("recordingMaxMinutes", "录音时长上限（分钟）", "1～${RecordingLimits.MAX_MINUTES} 分钟；0 表示不限时长", config.recordingMaxMinutes.toString(), numeric = true)
        choice("recordingLimitAction", "达到上限后", config.recordingLimitAction.value, RecordingLimitAction.choices,
            optionDescriptions = mapOf(
                RecordingLimitAction.DISCARD.value to "停止并丢弃，不自动发送。",
                RecordingLimitAction.PROMPT.value to "停止并等待确认；停止助手或应用退出后丢失。",
                RecordingLimitAction.PROCESS.value to "停止后按音频默认输出处理，可能产生费用。",
            ))
        section("历史截图", "保存首张原图和当前追问图片，可从历史查看并继续使用。\n\n仅影响之后保存的截图，不补存旧图。图片存于本机，删除会话时一并删除。")
        check("saveHistoryImages", "随历史长期保留截图", config.saveHistoryImages)
        section("历史音频", "完成录音后存于本机，可从历史回听。取消或丢弃的不保存，待确认录音需确认后保存。\n\n仅影响之后完成的录音，不补存旧音频；关闭不删除旧音频。\n\n删除会话时一并删除音频，总容量上限 512 MiB。")
        check("saveHistoryAudio", "随历史长期保留音频", config.saveHistoryAudio)
        section("对话记录", "追问保存在原会话内，复用转写文字，不重复上传音频。\n\n未发送、取消或失败的会话也可从主页找到。")
        note("问题、回答与转写随会话保存。")
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
                val start = try {
                    if (name == "recordingMaxMinutes") interactionStore.load().recordingMaxMinutes.toString()
                    else {
                        val current = store.load()
                        require(current.connectionId == renderedConnectionId) { "当前连接已改变，请重新打开设置页" }
                        editorConnectionId = current.connectionId
                        editorTranscriptionId = current.transcriptionConnectionId
                        if (secret) "" else SettingsUpdate.TextField.valueOf(name).read(current)
                    }
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
                    activeEdit = null; closeEditor = null; editorConnectionId = null; editorTranscriptionId = null
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
        if (name == "recordingMaxMinutes") {
            val minutes = draft.trim().toIntOrNull()
            if (minutes == null || minutes !in 0..RecordingLimits.MAX_MINUTES) {
                error("请输入 0～${RecordingLimits.MAX_MINUTES} 的整数，0 表示不限时长")
                return
            }
            if (persistInteraction { it.copy(recordingMaxMinutes = minutes) }) {
                valueView.text = minutes.toString()
                closeEditor?.invoke()
            } else error("保存失败，请重试")
            return
        }
        val update = SettingsUpdate.Text(SettingsUpdate.TextField.valueOf(name), draft)
        val save: (Boolean) -> Unit = { replace ->
            if (persist(update.copy(replaceSampling = replace), error)) {
                if (secret) savedCredential = true
                valueView.text = if (secret) credentialStatus() else draft.ifBlank { "未设置" }
                if (name == "temperature") valueViews["topP"]?.text = try { store.load().topP?.toString() ?: "未设置" } catch (_: Exception) { "未设置" }
                if (name == "topP") valueViews["temperature"]?.text = try { store.load().temperature?.toString() ?: "未设置" } catch (_: Exception) { "未设置" }
                closeEditor?.invoke()
                showPage(page)
            }
        }
        try {
            val current = store.load()
            require(current.connectionId == editorConnectionId) { "当前连接已改变，请关闭编辑后重试" }
            if (name == "transcriptionModel") require(current.transcriptionConnectionId == editorTranscriptionId) { "转写连接已改变，请关闭编辑后重试" }
            // Validate input before asking to replace the other sampling parameter.
            val candidate = update.copy(replaceSampling = true).applyTo(current)
            SettingsStore.validate(candidate)
            if (name in setOf("temperature", "topP") && draft.isNotBlank())
                require(ProviderCapabilities.sampling(candidate)) { "当前 API 版本不支持采样参数，请清空" }
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
                    "webSearch" -> if (value && !runCatching { ProviderCapabilities.search(store.load()) }.getOrDefault(false)) {
                        toast("当前协议或模型不支持联网搜索"); false
                    } else persist(SettingsUpdate.Toggle(webSearch = value))
                    "autoUpdateImage" -> persist(SettingsUpdate.Toggle(autoUpdateImage = value))
                    else -> persistInteraction { when (name) {
                        "collapseFollowUpByDefault" -> it.copy(collapseFollowUpByDefault = value)
                        "rememberWindowPosition" -> it.copy(rememberWindowPosition = value)
                        "rememberWindowSize" -> it.copy(rememberWindowSize = value)
                        "defaultPinned" -> it.copy(defaultPinned = value)
                        "autoAskImage" -> it.copy(autoAskImage = value)
                        "autoAskAudio" -> it.copy(autoAskAudio = value)
                        "autoAskText" -> it.copy(autoAskText = value)
                        "saveHistoryImages" -> it.copy(saveHistoryImages = value)
                        "saveHistoryAudio" -> it.copy(saveHistoryAudio = value)
                        else -> error("Unknown toggle")
                    } }
                }
                if (!ok) { restoring = true; button.isChecked = !value; restoring = false }
            }
        })
        addRow(row)
    }

    private fun choice(name: String, title: String, initial: String, options: Map<String, String>,
                       optionDescriptions: Map<String, String> = emptyMap(), onSelected: ((String) -> Unit)? = null) {
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
            val labels = keys.map { key ->
                optionDescriptions[key]?.let { "${options[key]}\n$it" } ?: options.getValue(key)
            }
            MaterialAlertDialogBuilder(this).setTitle(title).setSingleChoiceItems(labels.toTypedArray(), keys.indexOf(selections[name])) { dialog, index ->
                if (persistChoice(name, keys[index])) {
                    selections[name] = keys[index]
                    row.findViewWithTag<TextView>(name).text = options[keys[index]]
                    onSelected?.invoke(keys[index])
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
            "recordingLimitAction" -> it.copy(recordingLimitAction = RecordingLimitAction.from(value))
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

    private fun persist(update: SettingsUpdate, onError: (String) -> Unit = ::toast): Boolean = mutateConnection(onError) { current ->
        if (update is SettingsUpdate.Text && update.field == SettingsUpdate.TextField.transcriptionModel)
            require(current.transcriptionConnectionId == editorTranscriptionId) { "转写连接已改变，请重新打开编辑" }
        update.applyTo(current)
    }

    private fun mutateConnection(onError: (String) -> Unit = ::toast, successMessage: String = "已保存", update: (AppConfig) -> AppConfig): Boolean = try {
        val current = store.load()
        require(current.connectionId == (editorConnectionId ?: renderedConnectionId)) { "当前连接已改变，请重新打开设置页" }
        val next = update(current)
        if (next != current) {
            SettingsStore.validate(next); store.save(next)
            cancelDiagnostic(); cancelModelRequest(); status.text = ""
            toast(successMessage)
        }
        true
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
            require(stored.connectionId == renderedConnectionId) { "当前连接已改变，请重新打开设置页" }
            (if (target == "transcriptionModel") ProviderConnections.transcription(stored) else stored).also {
                SettingsStore.validate(it); require(it.apiKey.isNotBlank()) { "请先保存 API 密钥" }
            }
        } catch (error: IllegalArgumentException) { toast(error.message ?: "API 配置无效"); return }
        catch (_: Exception) { toast("无法读取连接配置，请先修复 API 连接"); return }
        val token = RequestCancellation(); modelRequest = token
        val currentGeneration = generation
        val ownerId = renderedConnectionId
        modelProgress = MaterialAlertDialogBuilder(this).setTitle("正在读取模型")
            .setMessage("${config.connectionName}\n仅请求此连接的模型列表，不会发送聊天或媒体，也不代表模型支持图片或搜索。")
            .setNegativeButton("取消") { _, _ -> cancelModelRequest() }.setOnCancelListener { cancelModelRequest() }.showTracked()
        Thread({
            try {
                val models = AiProvider().listModels(config, token)
                activity.runOnUiThread {
                    if (disposed || activity.isFinishing || activity.isDestroyed || generation != currentGeneration || modelRequest !== token) return@runOnUiThread
                    val saved = runCatching { store.load() }.getOrNull()
                    if (saved == null || saved.connectionId != ownerId || !profileStillCurrent(config, saved, target == "transcriptionModel")) {
                        cancelModelRequest(); status.text = "连接或模型设置已改变，请重新读取模型列表"; return@runOnUiThread
                    }
                    modelRequest = null; modelProgress?.dismiss(); modelProgress = null
                    if (models.isEmpty()) { toast("服务未返回可用模型，可手动填写模型 ID"); return@runOnUiThread }
                    if (target != null) {
                        MaterialAlertDialogBuilder(this).setTitle(if (target == "model") "选择图文模型 ID" else "选择转写模型 ID")
                            .setItems(models.toTypedArray()) { _, index -> finishEditing {
                                if (mutateConnection { latest ->
                                    require(profileStillCurrent(config, latest, target == "transcriptionModel")) { "读取列表后连接已改变，请重新读取" }
                                    SettingsUpdate.Text(SettingsUpdate.TextField.valueOf(target), models[index]).applyTo(latest)
                                }) showPage(page)
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

    private fun profileStillCurrent(request: AppConfig, saved: AppConfig, transcription: Boolean = false): Boolean {
        val selected = if (transcription) runCatching { ProviderConnections.transcription(saved) }.getOrNull() else saved
        return selected != null && ConnectionProfile.capture(request) == ConnectionProfile.capture(selected)
    }

    private fun chooseDiagnostic() {
        if (!readable) { toast("请先修复连接配置"); return }
        MaterialAlertDialogBuilder(this).setTitle("测试当前连接")
            .setItems(arrayOf("文字：发送一句固定示例", "图片：发送本机生成的红色色块", "搜索：请求查询当前信息")) { _, index ->
                val kind = listOf("text", "image", "search")[index]
                val description = when (kind) {
                    "image" -> "会将本机生成的白底红色色块图片发送给当前模型，不读取屏幕。"
                    "search" -> "会发送一次联网搜索请求；只有收到服务的搜索执行信息才会显示已验证。"
                    else -> "会发送一句固定测试问题，检查当前模型能否完整回答。"
                }
                MaterialAlertDialogBuilder(this).setTitle("运行${if (kind == "image") "图片" else if (kind == "search") "搜索" else "文字"}测试？")
                    .setMessage("$description\n\n使用当前已保存的连接与模型，可能产生调用费用；不写入聊天历史。")
                    .setNegativeButton("取消", null).setPositiveButton("开始测试") { _, _ -> runDiagnostic(kind) }.showTracked()
            }.setNegativeButton("取消", null).showTracked()
    }

    private fun runDiagnostic(kind: String) {
        cancelDiagnostic()
        val saved = try {
            store.load().also {
                require(it.connectionId == renderedConnectionId) { "当前连接已改变，请重新打开设置页" }
                SettingsStore.validate(it)
                require(it.apiKey.isNotBlank() && it.model.isNotBlank()) { "请先保存当前连接的密钥和图文模型" }
                if (kind == "search") require(ProviderCapabilities.search(it)) { "当前协议或模型不支持搜索测试" }
            }
        } catch (error: Exception) { toast(error.message ?: "无法读取连接"); return }
        val config = saved.copy(webSearch = kind == "search", instructions = "请按测试问题直接回答。", continuationMessages = emptyList())
        val title = when (kind) { "image" -> "图片测试"; "search" -> "搜索测试"; else -> "文字测试" }
        val currentGeneration = generation
        val token = RequestCancellation(); diagnosticRequest = token
        diagnosticProgress = MaterialAlertDialogBuilder(this).setTitle(title)
            .setMessage("${ProviderCapabilities.label(saved)}\n正在调用服务…")
            .setNegativeButton("取消") { _, _ -> cancelDiagnostic() }.setOnCancelListener { cancelDiagnostic() }.showTracked()
        Thread({
            try {
                val prompt = when (kind) {
                    "image" -> "图片中央色块是什么颜色？只回复中文颜色名称。"
                    "search" -> "请实际使用联网搜索工具，查找今天的一则科技新闻，并给出新闻日期与来源链接。"
                    else -> "请只回复 ALWAY_SIDE_OK"
                }
                val provider = AiProvider()
                val result = provider.respond(config, emptyList(), prompt, if (kind == "image") diagnosticImage() else null, token) {}
                val verdict = when (kind) {
                    "search" -> if (provider.lastSearchUsed) "已验证：服务返回了实际搜索执行信息。" else "请求已完成，搜索尚未验证：服务没有返回可核实的执行信息。模型自行声称搜索或提供链接不作为验证依据。"
                    "image" -> if (result.contains("红") || result.contains("red", ignoreCase = true)) "已验证：模型正确识别本机示例图片的红色色块。" else "请求已完成，图片识别未通过：回答未识别示例中的红色色块。"
                    else -> if (result.trim() == "ALWAY_SIDE_OK") "已验证：当前模型完整返回了指定文字。" else "文字请求已完成，但回答未遵循固定测试要求；可检查下方返回内容。"
                }
                activity.runOnUiThread {
                    if (!diagnosticCurrent(token, currentGeneration, saved)) return@runOnUiThread
                    diagnosticRequest = null; diagnosticProgress?.dismiss(); diagnosticProgress = null
                    status.text = "$title · ${ProviderCapabilities.label(saved)}\n$verdict"
                    MaterialAlertDialogBuilder(this).setTitle("${title}结果")
                        .setMessage("${ProviderCapabilities.label(saved)}\n$verdict\n\n${result.take(1600)}\n\n结果仅适用于本次连接、模型与参数；修改配置后需要重新测试。")
                        .setPositiveButton("完成", null).showTracked()
                }
            } catch (_: CancellationException) { /* Page changes and explicit cancellation discard results. */ }
            catch (error: Exception) {
                activity.runOnUiThread {
                    if (!diagnosticCurrent(token, currentGeneration, saved)) return@runOnUiThread
                    diagnosticRequest = null; diagnosticProgress?.dismiss(); diagnosticProgress = null
                    status.text = "${title}未通过 · ${ProviderCapabilities.label(saved)}\n${if (error is ProviderPausedException) "搜索暂停，未完成验证；可在聊天中使用继续生成。" else if (error is ProviderException) error.message else "测试失败，请检查连接、模型与参数。"}"
                }
            }
        }, "alwayside-connection-diagnostic").start()
    }

    private fun diagnosticCurrent(token: RequestCancellation, expectedGeneration: Int, config: AppConfig): Boolean {
        if (disposed || activity.isFinishing || activity.isDestroyed || generation != expectedGeneration || diagnosticRequest !== token) return false
        val saved = runCatching { store.load() }.getOrNull()
        if (saved == null || !profileStillCurrent(config, saved)) {
            cancelDiagnostic(); status.text = "连接、模型或参数已改变，本次测试结果已丢弃"; return false
        }
        return true
    }

    private fun diagnosticImage(): String {
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawRect(40f, 20f, 120f, 80f, Paint().apply { color = Color.RED })
            }
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    private fun cancelDiagnostic() {
        diagnosticRequest?.cancel(); diagnosticRequest = null
        diagnosticProgress?.dismiss(); diagnosticProgress = null
    }

    private fun clearCredential() {
        MaterialAlertDialogBuilder(this).setTitle("移除本机 API Key？")
            .setMessage("立即移除已保存的加密密钥。其他已存配置和历史保留；之后需重新填写 Key 才能调用服务。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                try {
                    if (readable) require(store.load().connectionId == renderedConnectionId) { "当前连接已改变" }
                    cancelDiagnostic(); cancelModelRequest()
                    store.clearCredential(); showPage(Page.API); toast("已移除本机密钥")
                }
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

    private fun section(text: String, help: String? = null) {
        group = null
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(label(text, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (help != null) addView(iconButton(R.drawable.ic_line_info, "查看${text}说明") {
                MaterialAlertDialogBuilder(this@SettingsScreen).setTitle(text).setMessage(help)
                    .setPositiveButton("知道了", null).showTracked()
            }.apply { iconTint = ColorStateList.valueOf(MUTED) })
        }
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = if (body.childCount <= 1) dp(4) else dp(16)
            bottomMargin = dp(8)
        })
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

    private fun note(text: String): TextView {
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
        return caption
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
        if (getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() in setOf("移除", "删除", "重置", "覆盖", "放弃并返回", "放弃并离开")) {
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
        cancelDiagnostic()
        dismissDialogs()
        clearDraft()
        body.removeAllViews()
        currentRoot = null
    }
}
