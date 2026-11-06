package com.alwayside.android

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.google.android.material.appbar.MaterialToolbar

/** Category pages intentionally keep their draft only in the current view, never saved instance state. */
internal class SettingsScreen(
    private val activity: Activity,
    private val onContent: (View) -> Unit,
    private val onBack: () -> Unit,
    initialPage: String? = null,
    private val onTutorial: () -> Unit = {},
    private val onPreparation: () -> Unit = {},
    private val onHelp: () -> Unit = {},
    private val onSlideTutorial: () -> Unit = {},
) : ContextWrapper(activity) {
    private enum class Page(val title: String, val description: String, val icon: Int) {
        HOME("设置", "", android.R.drawable.ic_menu_preferences),
        AI("AI 服务", "问答与转写服务配置、API 管理", android.R.drawable.ic_menu_manage),
        PROMPTS("提示词", "默认问题与音频输出", android.R.drawable.ic_menu_edit),
        APPEARANCE("应用外观", "跟随系统、浅色或深色主题", android.R.drawable.ic_menu_gallery),
        BALL("悬浮球外观", "大小、颜色与透明度预览", android.R.drawable.ic_menu_view),
        GESTURES("快捷动作", "功能与手势绑定", android.R.drawable.ic_menu_directions),
        WINDOW("结果窗口", "窗口行为与自动提问", android.R.drawable.ic_menu_crop),
        MEDIA("历史与媒体", "历史上限、录音与媒体保留", android.R.drawable.ic_menu_recent_history),
        LABORATORY("实验室", "试用中的功能", android.R.drawable.ic_menu_manage),
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
    private var generation = 0
    private var currentRoot: View? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private val choiceSheets = mutableSetOf<SettingsChoiceSheet>()
    private var disposed = false
    private var serviceUi: ServiceSettingsUi? = null
    private var serviceToolbar: MaterialToolbar? = null
    private val serviceEntry = initialPage?.takeIf { it in setOf("API", "ANSWER", "TRANSCRIPTION") } ?: "AI"
    private var renderedConnectionId: String? = null
    private var editorConnectionId: String? = null
    private var editorTranscriptionId: String? = null

    init {
        store = SettingsStore(this)
        interactionStore = InteractionPreferences(this)
        showPage(if (initialPage in setOf("API", "ANSWER", "TRANSCRIPTION", "MODELS")) Page.AI
            else Page.entries.firstOrNull { it.name == initialPage } ?: Page.HOME)
    }

    val isHome: Boolean get() = page == Page.HOME

    fun pageName(): String = page.name

    /** Reattach the existing view so navigating the host does not recreate a draft. */
    fun show() {
        if (!disposed) currentRoot?.let(onContent)
    }

    /** An active editor consumes back before category navigation. */
    fun consumeEditorBack(): Boolean {
        if (page == Page.AI && serviceUi?.back() == true) return true
        if(activeEdit==null)return false
        finishEditing {};return true
    }

    fun back(): Boolean {
        if (disposed) return false
        if (page == Page.AI && serviceUi?.back() == true) return true
        if (activeEdit != null) { finishEditing {}; return true }
        if (isHome) return false
        showPage(Page.HOME)
        return true
    }

    fun requestLeave(leave: () -> Unit) {
        if (!disposed) {
            val ui = serviceUi
            if (page == Page.AI && ui != null) ui.requestLeave(leave) else finishEditing(leave)
        }
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
        if (next != Page.AI) { serviceUi?.dispose(); serviceUi = null }
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
        serviceToolbar = header.takeIf { next == Page.AI }
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
        if (!readable) status.text = "无法读取配置。请到 AI 服务中的 API 管理检查。"
        when (next) {
            Page.HOME -> Page.entries.filter { it != Page.HOME }.forEach { category(it) }
            Page.AI -> {
                val ui = serviceUi ?: ServiceSettingsUi(activity, onHeader = { title, subtitle ->
                    serviceToolbar?.apply {
                        this.title = title; this.subtitle = subtitle
                        navigationContentDescription = "返回上一级"
                    }
                }, onEditMenu = { edit ->
                    serviceToolbar?.apply {
                        menu.removeItem(4302)
                        if (edit != null) addIconAction(4302, android.R.drawable.ic_menu_manage, "编辑服务", edit)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    }
                }).also { serviceUi = it }
                ui.render(body, serviceEntry)
            }
            Page.PROMPTS -> promptPage(config)
            Page.APPEARANCE -> appearancePage()
            Page.BALL -> ballPage(interaction)
            Page.GESTURES -> gesturePage(interaction)
            Page.WINDOW -> windowPage(config, interaction)
            Page.MEDIA -> mediaPage(interaction)
            Page.LABORATORY -> laboratoryPage(interaction)
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
        addRow(settingsListRow(value.title, value.description) {
            if (value == Page.HELP) onHelp() else showPage(value)
        })
    }

    private fun appearancePage() {
        choice("appearance", "应用主题", AppAppearance.mode(this),
            linkedMapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"))
        choice("scheme", "配色方案", AppAppearance.scheme(this), linkedMapOf("black" to "黑色", "blue" to "蓝色"))
        note("选择后立即应用。")
    }

    private fun promptPage(config: AppConfig) {
        input("instructions", "助手回答方式", "设置语气、格式及希望助手遵循的要求", config.instructions, multiline = true)
        input("defaultQuestion", "截图问题", "截图后自动填入的问题", config.defaultQuestion, multiline = true)
        input("textQuestion", "选中文字问题", "选中文字后自动填入的问题", config.textQuestion, multiline = true)
        input("audioQuestion", "音频问题", "用于“原文 + 按音频问题回答”，可要求翻译、总结或提取要点", config.audioQuestion, multiline = true)
        input("transcriptionPrompt", "新转写配置的默认提示", "示例：专有名词、背景或语言；不修改已有转写配置", config.transcriptionPrompt, multiline = true)
        choice("audioOutput", "音频默认输出", config.audioOutput, audioOutputOptions,
            help = "仅原始转写：只转写，不生成回答。\n原文 + 中文翻译：将转写原文翻译成中文。\n原文 + 按音频问题回答：根据转写原文和「音频问题」回答。\n\n转写提示在音频转写配置中独立保存。此处的默认提示仅用于之后新建的转写配置，不修改已有配置。")
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
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(-1, -2))
        GestureSettingsUi(this, content, config, load = interactionStore::load,
            save = ::persistInteraction, showDialog = { it.showTracked() },
            showChoiceSheet = { it.showTracked() }).render()
    }

    private fun laboratoryPage(config: InteractionConfig) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(-1, -2))
        LaboratorySettingsUi(this, content, config, load = interactionStore::load,
            save = ::persistInteraction, showDialog = { it.showTracked() },
            showChoiceSheet = { it.showTracked() },
            onTutorial = onSlideTutorial).render()
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
        section("音频播放")
        check("backgroundPlayback", "后台播放", config.backgroundPlayback,
            help = "开启后，离开聊天、切换应用或锁屏仍继续播放，可通过系统媒体控件暂停或停止。\n\n关闭时，离开当前聊天即停止播放。强行停止应用或重启手机会结束播放。")
        section("录音", "时长和上限策略从下次录音生效；0 表示不限时长。\n\n自动处理还需开启菜单中的「使用默认提问方式」；手动结束的行为在「结果窗口」设置。")
        input("recordingMaxMinutes", "录音时长上限", "单位：分钟；1～${RecordingLimits.MAX_MINUTES}，0 表示不限时长", config.recordingMaxMinutes.toString(), numeric = true)
        choice("recordingLimitAction", "达到上限后", config.recordingLimitAction.value, RecordingLimitAction.choices,
            optionDescriptions = mapOf(
                RecordingLimitAction.DISCARD.value to "停止并丢弃，不自动发送。",
                RecordingLimitAction.PROMPT.value to "停止并等待确认；停止助手或应用退出后丢失。",
                RecordingLimitAction.PROCESS.value to "停止后按音频默认输出处理，可能产生费用。",
            ))
        section("历史记录", "普通历史默认保留 500 条，超出上限时删除最早的普通历史及其媒体。收藏不计入上限，不会因上限被删除。调低上限时会先显示将删除的条数，确认后生效。\n\n问题、回答与转写随会话保存。追问保存在原会话内，复用转写文字，不重复上传音频。未发送、取消或失败的会话也可从主页找到。")
        check("saveHistoryImages", "长期保留截图", config.saveHistoryImages,
            help = "保存首张原图和当前追问图片，可从历史查看并继续使用。\n\n仅影响之后保存的截图，不补存旧图。图片存于本机，删除会话时一并删除。")
        check("saveHistoryAudio", "长期保留音频", config.saveHistoryAudio,
            help = "完成录音后存于本机，可从历史回听。取消或丢弃的不保存，待确认录音需确认后保存。\n\n仅影响之后完成的录音，不补存旧音频；关闭不删除旧音频。\n\n删除会话时一并删除音频，总容量上限 512 MiB。")
        try {
            input("historyLimit", "普通历史上限", "单位：条；请输入大于 0 的整数", HistoryStore(this).historyLimit().toString(), numeric = true)
        } catch (_: Exception) { note("无法读取历史上限，请重新打开此页。") }
    }

    private fun input(name: String, title: String, hintValue: String, initial: String = "", multiline: Boolean = false,
                      secret: Boolean = false, numeric: Boolean = false, decimal: Boolean = false) {
        val content = column().apply {
            setPadding(0, 0, 0, 0)
            isClickable = true; isFocusable = true
        }
        val valueView = label(if (secret) credentialStatus() else inputDisplay(name, initial)).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        valueViews[name] = valueView
        if (secret) credentialState = valueView
        val open = {
            finishEditing {
                val start = try {
                    when (name) {
                        "recordingMaxMinutes" -> interactionStore.load().recordingMaxMinutes.toString()
                        "historyLimit" -> HistoryStore(this).historyLimit().toString()
                        else -> {
                            val current = store.load()
                            require(current.connectionId == renderedConnectionId) { "当前连接已改变，请重新打开设置页" }
                            editorConnectionId = current.connectionId
                            editorTranscriptionId = current.transcriptionConnectionId
                            if (secret) "" else SettingsUpdate.TextField.valueOf(name).read(current)
                        }
                    }
                } catch (_: Exception) {
                    toast(if (name == "historyLimit") "无法读取历史上限，请重试" else "无法读取配置，请先检查 API 管理")
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
        content.contentDescription = "编辑$title，${valueView.text}"
        content.setOnClickListener { open() }
        if (multiline) {
            group = null
            content.addView(label(title, 13f, true).apply { textSize = 13f }, LinearLayout.LayoutParams(-1, -2))
            valueView.apply {
                background = outlinedCard(SURFACE, 8)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minimumHeight = dp(48)
            }
            content.addView(valueView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            body.addView(content, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        } else {
            val row = settingsListRow(title, inlineValue = true, showArrow = false, onClick = open)
            val text = row.getChildAt(0) as LinearLayout
            text.addView(valueView.apply {
                textSize = 14f; setTextColor(MUTED); gravity = Gravity.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.4f).toInt()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addRow(row)
        }
    }

    private fun inputDisplay(name: String, value: String): String = when (name) {
        "recordingMaxMinutes" -> if (value == "0") "不限时长" else "$value 分钟"
        "historyLimit" -> "$value 条"
        else -> value.ifBlank { "未设置" }
    }

    private fun saveText(name: String, draft: String, secret: Boolean, valueView: TextView, error: (String) -> Unit) {
        if (name == "historyLimit") {
            val limit = draft.trim().toIntOrNull()
            if (limit == null || limit < 1) {
                error("请输入 1～${Int.MAX_VALUE} 的整数")
                return
            }
            val history = HistoryStore(this)
            val save = {
                try {
                    history.setHistoryLimit(limit)
                    valueView.text = inputDisplay(name, limit.toString())
                    closeEditor?.invoke()
                    toast("已保存")
                } catch (_: Exception) { error("保存失败，请重试") }
            }
            try {
                if (limit < history.historyLimit()) {
                    val removed = (history.list().count { !it.isFavorite } - limit).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(this).setTitle("调低普通历史上限？")
                        .setMessage("将删除 $removed 条最早的普通历史，收藏不受影响。")
                        .setNegativeButton("继续编辑", null)
                        .setPositiveButton(if (removed > 0) "删除并保存" else "保存") { _, _ -> save() }.showTracked()
                } else save()
            } catch (_: Exception) { error("无法读取历史，未保存") }
            return
        }
        if (name == "recordingMaxMinutes") {
            val minutes = draft.trim().toIntOrNull()
            if (minutes == null || minutes !in 0..RecordingLimits.MAX_MINUTES) {
                error("请输入 0～${RecordingLimits.MAX_MINUTES} 的整数，0 表示不限时长")
                return
            }
            if (persistInteraction { it.copy(recordingMaxMinutes = minutes) }) {
                valueView.text = inputDisplay(name, minutes.toString())
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

    private fun check(name: String, title: String, initial: Boolean, help: String? = null) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54); setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        row.addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        if (help != null) row.addView(settingsHelp(title, help))
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
                        "backgroundPlayback" -> it.copy(backgroundPlayback = value)
                        else -> error("Unknown toggle")
                    } }
                }
                if (!ok) { restoring = true; button.isChecked = !value; restoring = false }
            }
        })
        addRow(row)
    }

    private fun choice(name: String, title: String, initial: String, options: Map<String, String>,
                       optionDescriptions: Map<String, String> = emptyMap(), help: String? = null,
                       onSelected: ((String) -> Unit)? = null) {
        selections[name] = initial
        val row = settingsListRow(title, options[initial] ?: initial, showArrow = help == null, inlineValue = true) {}
        val valueView = ((row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)
        if (help != null) row.addView(settingsHelp(title, help))
        row.setOnClickListener { finishEditing {
            val keys = options.keys.toList()
            val descriptions = keys.mapIndexedNotNull { index, key -> optionDescriptions[key]?.let { index to it } }.toMap()
            val sheet = SettingsChoiceSheet(this, title, keys.map(options::getValue), keys.indexOf(selections[name]), descriptions) { index ->
                if (disposed) return@SettingsChoiceSheet false
                if (persistChoice(name, keys[index])) {
                    selections[name] = keys[index]
                    valueView.text = options[keys[index]]
                    onSelected?.invoke(keys[index])
                    true
                } else false
            }
            sheet.showTracked()
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
            status.text = ""
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
            MaterialAlertDialogBuilder(this).setTitle("选择提示词模板").setSingleChoiceItems(names, -1) { dialog, index ->
                dialog.dismiss()
                val preset = presets.getValue(names[index])
                val preview = try { preset.mergeInto(store.load()) } catch (_: Exception) {
                    toast("无法读取当前配置，请先在 API 管理中检查密钥")
                    return@setSingleChoiceItems
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
        val heading = settingsSectionHeader(text, help, onHelp = {
            MaterialAlertDialogBuilder(this).setTitle(text).setMessage(help)
                .setPositiveButton("知道了", null).showTracked()
        })
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = if (body.childCount <= 1) 0 else dp(12)
            bottomMargin = dp(4)
        })
    }

    private fun addRow(view: View) {
        val container = group ?: column().apply { setPadding(0, 0, 0, 0) }.also { content ->
            body.addView(settingsListGroup(content), LinearLayout.LayoutParams(-1, -2))
            group = content
        }
        if (container.childCount > 0) container.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(16) }
        })
        container.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun settingsHelp(title: String, help: String) = iconButton(R.drawable.ic_line_info, "查看${title}说明") {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(help)
            .setPositiveButton("知道了", null).showTracked()
    }.apply { iconTint = ColorStateList.valueOf(MUTED) }

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
        if (getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() in setOf("移除", "删除", "删除并保存", "重置", "覆盖", "放弃并返回", "放弃并离开")) {
            getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(dangerColor)
        }
        return this
    }

    private fun SettingsChoiceSheet.showTracked() {
        if (disposed) return
        choiceSheets += this
        setOnDismissListener { choiceSheets.remove(this) }
        show()
    }

    private fun dismissDialogs() {
        templateSheet?.dismiss(); templateSheet = null
        choiceSheets.toList().forEach { it.dismiss() }; choiceSheets.clear()
        discardConfirmation = null
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        serviceUi?.dispose(); serviceUi = null
        generation++
        dismissDialogs()
        clearDraft()
        body.removeAllViews()
        currentRoot = null
    }
}
