package com.alwayside.android

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

/** A single navigation owner; only the host renders a toolbar. Drafts stay in memory until Save. */
internal class ServiceSettingsUi(
    private val activity: Activity,
    private val onHeader: (String, String?) -> Unit,
    private val onEditMenu: ((() -> Unit)?) -> Unit = {},
) : ContextWrapper(activity) {
    private enum class Page { ROOT, PURPOSE, API, CARD, KEY }
    private data class KeyForm(
        val id: String? = null, val providerId: String = "", val providerName: String = "",
        val kind: ProviderKind? = null, val name: String = "", val secret: String = "",
        val baseUrl: String = "", val protocol: ApiProtocol = ApiProtocol.RESPONSES,
        val transcription: FileTranscriptionApi = FileTranscriptionApi.NONE,
        val apiVersion: String = "2026-10-21", val workspaceId: String = "",
    ) {
        override fun toString() = "KeyForm(credential redacted)"
    }
    private val store = SettingsStore(this)
    private var body: LinearLayout? = null
    private var group: LinearLayout? = null
    private var fieldEditor: SettingsEditPanel? = null
    private var fieldInitial = ""
    private var fieldDraft = ""
    private var page = Page.ROOT
    private val history = mutableListOf<Page>()
    private var initialized = false
    private var purpose = ModelPurpose.ANSWER
    private var card: ModelProfile? = null
    private var cardInitial: ModelProfile? = null
    private var modelDefaultPending = false
    private val modelPresets by lazy {
        runCatching {
            assets.open(ModelPresets.ASSET).bufferedReader(Charsets.UTF_8).use { ModelPresets.decode(WireJson.decode(it.readText())) }
        }.getOrDefault(ModelPresets.EMPTY)
    }
    private var key: KeyForm? = null
    private var keyInitial: KeyForm? = null
    private var expected: ServiceCatalog? = null
    private var connectionExpanded = false
    private var optionsExpanded = false
    private var disposed = false
    private var generation = 0
    private var request: RequestCancellation? = null
    private var progress: AlertDialog? = null
    private var closeGuard: AlertDialog? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private val choiceSheets = mutableSetOf<SettingsChoiceSheet>()
    private var status = ""
    private val verification = mutableMapOf<String, Pair<ServiceCatalog, String>>()

    fun render(container: LinearLayout, entry: String = "AI") {
        if (disposed) return
        body = container
        if (!initialized) {
            initialized = true
            when (entry.uppercase(Locale.ROOT)) {
                "ANSWER" -> { purpose = ModelPurpose.ANSWER; page = Page.PURPOSE }
                "TRANSCRIPTION" -> { purpose = ModelPurpose.TRANSCRIPTION; page = Page.PURPOSE }
                "API" -> { page = Page.API }
            }
        }
        redraw()
    }

    fun back(): Boolean {
        if (closeGuard != null) return true
        if (fieldEditor != null) { finishFieldEditing {}; return true }
        if (request != null) { cancelRequest(); return true }
        if (page == Page.ROOT || history.isEmpty() && page != Page.CARD && page != Page.KEY) return false
        finishEditing { leavePage() }
        return true
    }

    fun finishEditing(next: () -> Unit) {
        val dirty = when (page) {
            Page.CARD -> card != cardInitial
            Page.KEY -> key != keyInitial
            else -> false
        }
        if (!dirty) { next(); return }
        if (closeGuard != null) return
        closeGuard = MaterialAlertDialogBuilder(this).setTitle("放弃未保存的修改？")
            .setMessage("离开后，本页未保存的内容将丢弃。")
            .setNegativeButton("继续编辑") { _, _ -> closeGuard = null }
            .setPositiveButton("放弃并离开") { _, _ ->
                closeGuard = null
                if (page == Page.CARD) card = cardInitial else if (page == Page.KEY) key = keyInitial
                next()
            }.setOnCancelListener { closeGuard = null }.showTracked()
    }

    /** Leaving Settings must also guard a card draft temporarily parked behind API management. */
    fun requestLeave(next: () -> Unit) {
        if (card == cardInitial && key == keyInitial && fieldDraft == fieldInitial) { closeField(); next(); return }
        if (closeGuard != null) return
        closeGuard = MaterialAlertDialogBuilder(this).setTitle("放弃未保存的修改？")
            .setMessage("当前流程中还有未保存的服务配置或密钥，离开后将丢弃。")
            .setNegativeButton("继续编辑") { _, _ -> closeGuard = null }
            .setPositiveButton("放弃并离开") { _, _ ->
                closeGuard = null; closeField(); card = cardInitial; key = keyInitial; next()
            }.setOnCancelListener { closeGuard = null }.showTracked()
    }

    fun dispose() {
        disposed = true; cancelRequest(); closeField(); dismissDialogs()
        card = null; cardInitial = null; key = null; keyInitial = null; body = null
    }

    private fun catalog() = requireNotNull(store.load().serviceCatalog)
    private fun redraw() {
        if (disposed) return
        val target = body ?: return
        target.removeAllViews(); group = null
        try {
            val c = catalog()
            onHeader(when (page) {
                Page.ROOT -> "AI 服务"
                Page.PURPOSE -> purpose.settingsTitle
                Page.API -> "管理 API 服务"
                Page.CARD -> if (cardInitial?.let { initial -> c.profiles.any { it.id == initial.id } } == true) "编辑${purpose.configurationTitle}" else "添加${purpose.configurationTitle}"
                Page.KEY -> if (key?.id == null) "添加 Key" else "编辑 Key"
            }, if (page == Page.CARD) card?.let { providerName(c, it.providerId) } else null)
            onEditMenu(null)
            when (page) {
                Page.ROOT -> {
                    row(ModelPurpose.ANSWER.settingsTitle, selectedServiceSummary(c, ModelPurpose.ANSWER)) { purpose = ModelPurpose.ANSWER; navigate(Page.PURPOSE) }
                    row(ModelPurpose.TRANSCRIPTION.settingsTitle, selectedServiceSummary(c, ModelPurpose.TRANSCRIPTION)) { purpose = ModelPurpose.TRANSCRIPTION; navigate(Page.PURPOSE) }
                    row("管理 API 服务") { navigate(Page.API) }
                }
                Page.PURPOSE -> renderPurpose(c)
                Page.API -> renderApi(c)
                Page.CARD -> renderCard(c)
                Page.KEY -> renderKey(c)
            }
            if (status.isNotBlank()) note(status)
        } catch (_: Exception) { note("无法读取服务配置，请关闭设置后重试。") }
    }

    private fun navigate(next: Page) {
        hideKeyboard()
        cancelRequest(); status = ""; dismissDialogs()
        history += page; page = next; redraw(); scrollToTop()
    }

    private fun leavePage() {
        hideKeyboard()
        cancelRequest(); status = ""; dismissDialogs()
        if (page == Page.CARD) { card = null; cardInitial = null }
        if (page == Page.KEY) { key = null; keyInitial = null }
        page = if (history.isEmpty()) Page.ROOT else history.removeAt(history.lastIndex)
        // Key management can be opened over a card draft. Refresh the optimistic snapshot,
        // preserving the draft and resolving the selected Key's new internal endpoint.
        if (page == Page.CARD) {
            val c = catalog()
            expected = c
            card = card?.let { draft ->
                val credential = c.credentials.find { it.id == draft.credentialId }
                if (credential == null) draft.copy(credentialId = null, endpointId = null)
                else draft.copy(endpointId = credential.endpointIds.singleOrNull())
            }
        }
        redraw(); scrollToTop()
    }

    private fun scrollToTop() {
        var parent = body?.parent
        while (parent != null) {
            if (parent is android.widget.ScrollView) {
                val scroll = parent
                scroll.post { scroll.scrollTo(0, 0) }
                return
            }
            parent = parent.parent
        }
    }

    private fun renderPurpose(c: ServiceCatalog) {
        val cards = c.profiles.filter { it.purpose == purpose }
        section("服务配置", "添加${purpose.configurationTitle}", help = "点击服务名称即可选用，左侧圆框表示唯一选中项。首个服务保存后自动选中，后续新增不改变当前选择。行尾可编辑或删除；密钥在 API 管理中维护。") { addCard(c) }
        cards.forEach { profile ->
            val name = providerName(c, c.providerIdOf(profile))
            val selected = activeId(c, purpose) == profile.id
            val reason = c.readiness(profile)
            val radio = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                setUseMaterialThemeColors(true); isChecked = selected; isEnabled = reason == null
                isClickable = false; isFocusable = false; isSaveEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val serviceRow = settingsListRow(name, if (reason == null) "" else "需完善", leading = radio, showArrow = false) {
                if (activeId(catalog(), purpose) != profile.id) {
                    if (reason != null) toast(reason.replace("接入点", "API 连接"))
                    else save(c, "已选用$name") { it.activate(profile.id) }
                }
            }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = selected
                    }
                }
            }
            addRow(managementRow(serviceRow, "${purpose.configurationTitle}“$name”",
                edit = { openCard(c, profile) }, remove = { deleteCard(c, profile) }))
        }
        if (cards.isEmpty() && c.credentials.isNotEmpty()) note("尚未添加服务")
        if (c.credentials.isEmpty()) apiHelp(if (store.requiresServiceSetup()) "请重新添加 API 密钥" else "请先添加 API 密钥")
    }

    private fun addCard(c: ServiceCatalog) {
        val providers = c.providers.filter { provider -> c.credentials.any { it.providerId == provider.id } }
        if (providers.isEmpty()) {
            apiHelpDialog("尚未添加 Key。请先在 API 服务中添加 Key，再为此用途添加服务配置。")
            return
        }
        val existing = c.profiles.filter { it.purpose == purpose }.map(c::providerIdOf).toSet()
        val disabled = providers.indices.filter { providers[it].id in existing }.toSet()
        choose("选择服务商", providers.map { it.name }, disabledIndices = disabled) { index ->
            val provider = providers[index]
            val draft = ModelProfile(id(), provider.name, purpose, providerId = provider.id,
                transcriptionPrompt = if (purpose == ModelPurpose.TRANSCRIPTION) store.load().transcriptionPrompt else "")
            openCard(c, draft, initializeModel = true)
        }
    }

    private fun openCard(c: ServiceCatalog, profile: ModelProfile, initializeModel: Boolean = false) {
        card = profile; cardInitial = profile; expected = c; optionsExpanded = false
        modelDefaultPending = initializeModel
        navigate(Page.CARD)
    }

    private fun renderCard(c: ServiceCatalog) {
        val draft = card ?: return
        val providerId = draft.providerId
        val keys = c.credentials.filter { it.providerId == providerId }
        val usable = keys.filter { k ->
            !k.unreadable && k.value.isNotBlank() && c.endpoints.any { e ->
                e.id in k.endpointIds && (purpose == ModelPurpose.ANSWER || e.transcriptionApi == FileTranscriptionApi.OPENAI_COMPATIBLE)
            }
        }
        var p = if (draft.credentialId == null && usable.size == 1) {
            val chosen = usable.single()
            draft.copy(credentialId = chosen.id, endpointId = chosen.endpointIds.single()).also { card = it }
        } else draft
        if (modelDefaultPending && usable.any { it.id == p.credentialId }) {
            val provider = c.providers.find { it.id == providerId }
            val endpoint = c.endpoints.find { it.id == p.endpointId }
            if (provider != null && endpoint != null) {
                modelDefaultPending = false
                p = modelPresets.initializeNewProfile(p, provider.kind, endpoint).also { card = it }
            }
        }
        section("服务配置", help = "本页只引用这家服务商已有的密钥。首项保存后自动选中，后续新增不切换；在用途列表点击服务行可选用，不修改另一种用途。\n\n新建配置首次选定可用密钥时填入内置默认模型；编辑、换密钥或升级不覆盖已有选择。点击模型可离线选择推荐项、手动填写，或主动从服务获取。暂无推荐不代表服务不支持；转写仅 OpenAI 提供内置推荐。推荐和服务声明不等于账号可用或实际验证，Qwen 的地址、密钥和模型地域须匹配。")
        row("API 密钥", c.credentials.find { it.id == p.credentialId }?.name ?: "请选择") {
            if (usable.isEmpty()) apiHelpDialog(if (keys.isEmpty()) "此服务商还没有 Key。" else "此服务商没有可用于${purpose.title}的 Key，请在 API 服务中检查转写支持或替换不可用 Key。")
            else choose("选择 API 密钥", usable.map { it.name }, checkedIndex = usable.indexOfFirst { it.id == p.credentialId }) { index ->
                val chosen = usable[index]
                card = requireNotNull(card).copy(credentialId = chosen.id, endpointId = chosen.endpointIds.single())
                redraw()
            }
        }
        if (usable.isEmpty()) apiHelp(if (keys.isEmpty()) "此服务商尚无 Key。" else "暂无支持${purpose.title}的可用 Key。")
        if (usable.none { it.id == p.credentialId }) {
            if (usable.isNotEmpty()) note("请先选择密钥")
            return
        }
        row("模型", p.model.ifBlank { "请选择" }) { chooseModel(c) }
        section("用途参数", help = if (purpose == ModelPurpose.ANSWER)
            "未设置的参数使用服务默认，支持范围随服务、接口和模型变化。\n\n输出上限：留空或 16–131072。Temperature：0–2，旧版 Claude 为 0–1；Top P：0–1，两种采样参数只设置一项。推理、详细程度及搜索需模型支持。"
            else "转写提示用于专有名词和背景。语言留空时自动识别，也可填写 zh、en 等服务支持的语言代码。这些设置仅属于当前转写服务。")
        if (purpose == ModelPurpose.ANSWER) renderOptions(p) else {
            val customOptions = p.transcriptionPrompt.isNotBlank() || p.language.isNotBlank()
            row("转写参数", if (optionsExpanded) "收起" else if (customOptions) "已自定义" else "默认") {
                optionsExpanded = !optionsExpanded; redraw()
            }
            if (optionsExpanded) {
                input("转写提示", p.transcriptionPrompt, "留空使用服务默认。", multiline = true) { card = requireNotNull(card).copy(transcriptionPrompt = it) }
                input("语言", p.language, "留空自动识别，或填写 zh、en 等语言代码。") { card = requireNotNull(card).copy(language = it.trim()) }
            }
        }
        action("保存", true) { saveCard() }
        val persisted = c.profiles.find { it.id == p.id }
        if (persisted != null) {
            section("连接测试", help = "测试只使用已保存设置，可能产生费用。模型列表和服务声明不能替代实际验证；修改密钥、模型或参数后需重新测试。")
            row("测试连接") {
                if (card != persisted) toast("请先保存服务配置，再测试") else chooseDiagnostic(c, persisted)
            }
            verification[p.id]?.takeIf { it.first == c }?.second?.let(::note)
        }
    }

    private fun deleteCard(c: ServiceCatalog, p: ModelProfile) {
        val name = providerName(c, c.providerIdOf(p))
        confirmDelete("删除“$name”的${p.purpose.configurationTitle}？", "将移除${p.purpose.configurationTitle}${if (activeId(c, p.purpose) == p.id) "并停用此用途" else ""}。不会自动选用其他配置；API 密钥保留。") {
            if (save(c, "已删除服务配置") { it.removeProfile(p.id) }) verification.remove(p.id)
        }
    }

    private fun renderOptions(p: ModelProfile) {
        val o = p.options
        val config = optionConfig(catalog(), p)
        val verbosity = config == null || ProviderCapabilities.verbosity(config)
        val sampling = config == null || ProviderCapabilities.sampling(config)
        val search = config == null || ProviderCapabilities.search(config)
        input("最大输出 tokens", o.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), "留空使用服务默认；16–131072。", numeric = true) { value ->
            // Keep an invalid numeric draft visible; save reports the validation error.
            card = requireNotNull(card).copy(options = requireNotNull(card).options.copy(maxOutputTokens = if (value.isBlank()) 0 else value.toIntOrNull() ?: -1))
        }
        row("推理程度", o.reasoning.ifBlank { "服务默认" }) {
            val latest = card ?: return@row
            val target = optionConfig(catalog(), latest)
            val defaultsAccepted = target != null && runCatching { ModelOptionCompatibility.validate(GenerationOptions().applyTo(target)) }.isSuccess
            val values = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max").filter { effort ->
                effort.isEmpty() || !defaultsAccepted || runCatching {
                    ModelOptionCompatibility.validate(GenerationOptions(reasoning = effort).applyTo(requireNotNull(target)))
                }.isSuccess
            }
            choose("推理程度", values.map { it.ifBlank { "服务默认" } }, checkedIndex = values.indexOf(latest.options.reasoning)) { index -> setOptions { it.copy(reasoning = values[index]) } }
        }
        if (verbosity || o.verbosity.isNotBlank()) row("回答详细程度", o.verbosity.ifBlank { "服务默认" }) {
            val values = if (verbosity) listOf("", "low", "medium", "high") else listOf("")
            choose("回答详细程度", values.map { it.ifBlank { "服务默认" } }, checkedIndex = values.indexOf(o.verbosity)) { index -> setOptions { it.copy(verbosity = values[index]) } }
        }
        if (sampling || o.temperature != null) input("temperature", o.temperature?.toString().orEmpty(), "留空使用服务默认；0–2，与 top_p 只设置一项。", decimal = true) { value ->
            card = requireNotNull(card).copy(options = requireNotNull(card).options.copy(temperature = if (value.isBlank()) null else value.toDoubleOrNull() ?: Double.NaN))
        }
        if (sampling || o.topP != null) input("top_p", o.topP?.toString().orEmpty(), "留空使用服务默认；0–1，与 temperature 只设置一项。", decimal = true) { value ->
            card = requireNotNull(card).copy(options = requireNotNull(card).options.copy(topP = if (value.isBlank()) null else value.toDoubleOrNull() ?: Double.NaN))
        }
        row("联网搜索", if (o.webSearch) "开启" else "关闭") {
            choose("联网搜索", listOf("关闭", "开启"), checkedIndex = if (o.webSearch) 1 else 0,
                disabledIndices = if (search) emptySet() else setOf(1)) { index -> setOptions { it.copy(webSearch = index == 1) } }
        }
    }

    private fun setOptions(transform: (GenerationOptions) -> GenerationOptions) {
        card = requireNotNull(card).let { it.copy(options = transform(it.options)) }; redraw()
    }

    private fun saveCard() {
        val c = expected ?: return
        val p = card ?: return
        try {
            require(catalog() == c) { "服务配置已改变，请返回列表后重新编辑" }
            require(p.credentialId != null && p.endpointId != null) { "请选择可用于此用途的 API 密钥" }
            require(p.model.isNotBlank()) { "请填写模型 ID" }
            compatibleProfile(c, cardInitial ?: p, p, done = {
                cardInitial = card; leavePage()
            })
        } catch (ex: Exception) { toast(ex.message ?: "服务配置无效") }
    }

    private fun renderApi(c: ServiceCatalog) {
        section("API 密钥", "添加 API 密钥", help = "先选择服务商，再添加密钥并命名。同一家服务商可以保存多个密钥，每个密钥保存对应地址和接口信息；名称不能重复。点击条目编辑 Key，行尾可删除。") { openKey(c) }
        c.providers.filter { provider -> c.credentials.any { it.providerId == provider.id } }.forEach { provider ->
            section(provider.name)
            c.credentials.filter { it.providerId == provider.id }.forEach { credential ->
                val keyRow = settingsListRow(credential.name, if (credential.unreadable) "需更新密钥" else "", showArrow = false) {
                    openKey(c, credential)
                }
                addRow(managementRow(keyRow, "Key“${credential.name}”",
                    edit = { openKey(c, credential) }, remove = { deleteKey(c, credential) }, showEdit = false))
            }
        }
        if (c.credentials.isEmpty()) {
            note(if (store.requiresServiceSetup()) "请重新添加 API 密钥" else "尚未添加 API 密钥")
        }
    }

    private fun openKey(c: ServiceCatalog, credential: ServiceCredential? = null) {
        if (credential == null) {
            chooseKeyProvider(c, newKey = true)
            return
        }
        val provider = c.providers.find { it.id == credential.providerId }
        val endpoint = c.endpoints.find { it.id in credential.endpointIds }
        key = KeyForm(credential.id, credential.providerId,
            provider?.name.orEmpty(), provider?.kind, credential.name, "", endpoint?.baseUrl.orEmpty(),
            endpoint?.answerProtocol ?: ApiProtocol.RESPONSES, endpoint?.transcriptionApi ?: FileTranscriptionApi.NONE,
            endpoint?.apiVersion ?: "2026-10-21", endpoint?.workspaceId.orEmpty())
        keyInitial = key; expected = c; connectionExpanded = false
        navigate(Page.KEY)
    }

    private fun renderKey(c: ServiceCatalog) {
        val draft = key ?: return
        section("API 密钥", help = "选择服务商后填写密钥和名称。已有密钥不回显，留空保留原值；更换到其他站点时需重新填写。已有条目的所属服务商固定。")
        if (draft.id == null) row("服务商", draft.kind?.let { draft.providerName.ifBlank { "自定义服务" } } ?: "请选择") { chooseKeyProvider(c) }
        else valueRow("服务商", draft.providerName)
        if (draft.kind == null) return
        input("Key", draft.secret, if (draft.id == null) "请输入此服务的 API Key。" else "已有 Key 不回显；留空保留原值，更换地址来源时请重新输入。", secret = true) { key = requireNotNull(key).copy(secret = it) }
        input("名称", draft.name, "同一服务商下的 Key 名称不可重复。") { key = requireNotNull(key).copy(name = it) }
        if (draft.kind == ProviderKind.CUSTOM && draft.id == null && c.providers.none { it.id == draft.providerId }) input("自定义服务名称", draft.providerName, "必填，用于服务商分组与服务配置。") { key = requireNotNull(key).copy(providerName = it) }
        section("连接信息", help = "填写 HTTPS 基础地址，不附加具体请求路由。协议决定请求格式；文件转写支持需服务实际提供对应接口，不能由聊天兼容性推断。\n\nClaude API 版本使用 YYYY-MM-DD；Workspace ID 可选，留空不发送。")
        row("高级设置", if (connectionExpanded) "收起" else "展开") { connectionExpanded = !connectionExpanded; redraw() }
        if (connectionExpanded) {
            input("API URL", draft.baseUrl, "HTTPS 基础地址；每个 Key 独立保存地址与协议。") { key = requireNotNull(key).copy(baseUrl = it.trim().trimEnd('/')) }
            row("回答协议", draft.protocol.title) {
                val protocols = when (draft.kind) {
                    ProviderKind.DEEPSEEK -> listOf(ApiProtocol.RESPONSES)
                    ProviderKind.CLAUDE -> listOf(ApiProtocol.CLAUDE)
                    ProviderKind.GEMINI -> listOf(ApiProtocol.GEMINI, ApiProtocol.CHAT_COMPLETIONS)
                    ProviderKind.CUSTOM -> ApiProtocol.entries.toList()
                    else -> listOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)
                }
                choose("回答协议", protocols.map { it.title }, checkedIndex = protocols.indexOf(draft.protocol)) { index ->
                    val selected = protocols[index]
                    val current = requireNotNull(key)
                    val officialGemini = runCatching { java.net.URI(current.baseUrl).host == "generativelanguage.googleapis.com" }.getOrDefault(false)
                    key = current.copy(protocol = selected, baseUrl = if (officialGemini)
                        "https://generativelanguage.googleapis.com/v1beta" + (if (selected == ApiProtocol.CHAT_COMPLETIONS) "/openai" else "") else current.baseUrl)
                    redraw()
                }
            }
            row("转写支持", if (draft.transcription == FileTranscriptionApi.NONE) "不支持文件转写" else "OpenAI 兼容文件转写") {
                choose("文件转写支持", listOf("不支持文件转写", "OpenAI 兼容文件转写"), checkedIndex = if (draft.transcription == FileTranscriptionApi.NONE) 0 else 1) { index ->
                    key = requireNotNull(key).copy(transcription = if (index == 1) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE); redraw()
                }
            }
            if (draft.protocol == ApiProtocol.CLAUDE) {
                input("Claude API 版本", draft.apiVersion, "格式 YYYY-MM-DD。") { key = requireNotNull(key).copy(apiVersion = it.trim()) }
                input("Claude Workspace ID", draft.workspaceId, "可选，留空不发送。") { key = requireNotNull(key).copy(workspaceId = it.trim()) }
            }
        }
        action(if (guidedApiParent() != null) "保存并返回${purpose.title}" else "保存", true) { saveKey() }
    }

    private fun deleteKey(c: ServiceCatalog, credential: ServiceCredential) {
        val refs = c.profiles.filter { it.credentialId == credential.id }
        val impact = if (refs.isEmpty()) "没有服务配置引用此 Key。" else "受影响用途：\n" + refs.joinToString("\n") {
            "${it.purpose.title} / ${providerName(c, c.providerIdOf(it))}${if (activeId(c, it.purpose) == it.id) "（当前选中，将停用）" else ""}"
        }
        confirmDelete("删除 Key“${credential.name}”？", "$impact\n引用此 Key 的服务配置将保留供修复，不会自动切换其他 Key 或配置。") {
            save(c, "已删除 Key") { it.removeCredential(credential.id) }
        }
    }

    private fun chooseKeyProvider(c: ServiceCatalog, newKey: Boolean = false) {
        val builtins = ProviderKind.entries.filter { it != ProviderKind.CUSTOM }.map { kind ->
            ServiceProvider(kind.name.lowercase(Locale.ROOT), kind.title, kind)
        }
        val providers = builtins + c.providers.filter { it.kind == ProviderKind.CUSTOM } + ServiceProvider(id(), "自定义服务", ProviderKind.CUSTOM)
        val checkedIndex = if (newKey) -1 else providers.indexOfFirst { it.id == key?.providerId }
        choose("选择服务商", providers.map { it.name }, checkedIndex) { index ->
            val provider = providers[index]
            val current = if (newKey) KeyForm() else requireNotNull(key)
            if (current.providerId == provider.id) return@choose
            val existingCustom = c.providers.any { it.id == provider.id }
            val previousEndpoint = if (existingCustom) c.credentials.firstOrNull { it.providerId == provider.id }
                ?.endpointIds?.singleOrNull()?.let { endpoint -> c.endpoints.find { it.id == endpoint } } else null
            val change = {
                key = current.copy(providerId = provider.id, secret = "",
                    providerName = if (provider.kind == ProviderKind.CUSTOM && !existingCustom) "" else provider.name,
                    kind = provider.kind, baseUrl = previousEndpoint?.baseUrl ?: if (provider.kind == ProviderKind.CUSTOM) "" else provider.kind.baseUrl,
                    protocol = previousEndpoint?.answerProtocol ?: provider.kind.protocol,
                    transcription = previousEndpoint?.transcriptionApi ?: if (provider.kind == ProviderKind.OPENAI) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE,
                    apiVersion = previousEndpoint?.apiVersion ?: "2026-10-21", workspaceId = previousEndpoint?.workspaceId.orEmpty())
                connectionExpanded = provider.kind == ProviderKind.CUSTOM
                if (newKey) {
                    keyInitial = key; expected = c
                    navigate(Page.KEY)
                } else redraw()
            }
            if (current.secret.isNotEmpty()) MaterialAlertDialogBuilder(this).setTitle("更换服务商？")
                .setMessage("将清空刚才输入的 Key，并重新填写所选服务商的密钥。")
                .setNegativeButton("取消", null).setPositiveButton("更换") { _, _ -> change() }.showTracked()
            else change()
        }
    }

    private fun saveKey() {
        val draft = key ?: return
        val c = expected ?: return
        try {
            val kind = requireNotNull(draft.kind) { "请选择服务商" }
            require(draft.name.trim().isNotBlank()) { "请填写 Key 名称" }
            require(draft.providerName.trim().isNotBlank()) { "请填写自定义服务名称" }
            if (draft.baseUrl.isBlank()) {
                connectionExpanded = true; redraw(); toast("请在连接信息中填写 API URL"); return
            }
            val value = ApiKeyDraft(draft.id, draft.providerId, draft.providerName.trim(), kind,
                draft.name.trim(), draft.secret.trim(), draft.baseUrl.trim().trimEnd('/'), draft.protocol,
                draft.transcription, draft.apiVersion, draft.workspaceId)
            if (save(c) { it.upsertKey(value) }) {
                val returnToOrigin = guidedApiParent() != null
                key = keyInitial; leavePage()
                if (returnToOrigin && page == Page.API) leavePage()
            }
        } catch (ex: Exception) { toast(ex.message ?: "Key 配置无效") }
    }

    private fun chooseModel(c: ServiceCatalog) {
        val draft = card ?: return
        val provider = c.providers.find { it.id == c.providerIdOf(draft) }
        val endpoint = c.endpoints.find { it.id == draft.endpointId }
        val recommendations = if (provider != null && endpoint != null)
            modelPresets.recommendations(provider.kind, draft.purpose, endpoint) else ModelRecommendations()
        val models = recommendations.models.toMutableList()
        if (draft.model.isNotBlank() && models.none { it.id == draft.model })
            models += ModelPreset(draft.model, draft.model, "当前")
        val labels = models.map { model -> buildString {
            append(model.name)
            if (model.id == recommendations.defaultModelId) append(" · 默认")
            if (model.tag.isNotBlank()) append(" · ${model.tag}")
            if (model.name != model.id) append("\n${model.id}")
        } } + listOf("手动填写", "从服务获取")
        choose("选择模型", labels, checkedIndex = models.indexOfFirst { it.id == draft.model }) { index ->
            when {
                index < models.size -> { card = requireNotNull(card).copy(model = models[index].id); redraw() }
                index == models.size -> editInput("模型", requireNotNull(card).model) {
                    card = requireNotNull(card).copy(model = it.trim())
                }
                else -> fetchDraftModels(c)
            }
        }
    }

    private fun fetchDraftModels(c: ServiceCatalog) {
        val draft = card ?: return
        val credential = c.credentials.find { it.id == draft.credentialId }
        val endpoint = c.endpoints.find { it.id == draft.endpointId }
        if (credential == null || endpoint == null || credential.unreadable || credential.value.isBlank()) {
            apiHelpDialog("请先选择可用的 API 密钥。"); return
        }
        val snapshot = c.copy(profiles = c.profiles.filterNot { it.id == draft.id } + draft)
        val config = snapshot.project(store.load(), draft.id)
        runRequest(c, "获取模型列表", "正在读取所选 API 密钥可用的模型列表…", { token -> AiProvider().listModelCatalog(config, token) }) { models ->
            if (models.isEmpty()) toast("服务未返回模型，可手动填写模型 ID") else
                choose("选择模型", models.map(::modelCatalogLabel), checkedIndex = models.indexOfFirst { it.id == card?.model }) { index ->
                    card = requireNotNull(card).copy(model = models[index].id); redraw()
                }
        }
    }

    private fun save(c: ServiceCatalog, message: String = "已保存", error: (String) -> Unit = ::toast, transform: (ServiceCatalog) -> ServiceCatalog): Boolean = try {
        val next = transform(c)
        if (next != c) {
            next.validate(); store.updateCatalog(c) { next }
            cancelRequest(); status = ""; toast(message)
        }
        redraw(); true
    } catch (ex: IllegalArgumentException) { error(ex.message ?: "配置无效"); false }
    catch (_: Exception) { error("保存失败或配置已改变，请返回列表后重试"); false }

    private fun input(title: String, value: String, helper: String, secret: Boolean = false,
                      multiline: Boolean = false, numeric: Boolean = false, decimal: Boolean = false,
                      changed: (String) -> Unit) {
        val displayed = if (secret) {
            if (value.isNotBlank()) "已输入" else if (expected?.credentials?.find { it.id == key?.id }?.unreadable == true) "需更新" else if (key?.id != null) "已保存" else "未设置"
        } else value.ifBlank { "未设置" }
        row(title, displayed) {
            editInput(title, value, secret, multiline, numeric, decimal, changed)
        }
    }

    private fun editInput(title: String, value: String, secret: Boolean = false,
                          multiline: Boolean = false, numeric: Boolean = false, decimal: Boolean = false,
                          changed: (String) -> Unit) {
        fieldInitial = value; fieldDraft = value
        fieldEditor = SettingsEditPanel(this, title, "", value, multiline, secret, numeric, decimal,
            onDraft = { fieldDraft = it }, onRequestClose = { finishFieldEditing {} },
            onSave = { text, error ->
                val trimmed = text.trim()
                when {
                    numeric && trimmed.isNotEmpty() && trimmed.toIntOrNull() == null -> error("请填写整数或留空")
                    decimal && trimmed.isNotEmpty() && trimmed.toDoubleOrNull()?.isFinite() != true -> error("请填写有效数值或留空")
                    else -> { changed(text); closeField(); redraw() }
                }
            }, confirmLabel = "确定").also { it.show() }
    }

    private fun finishFieldEditing(next: () -> Unit) {
        if (fieldDraft == fieldInitial) { closeField(); next(); return }
        if (closeGuard != null) return
        closeGuard = MaterialAlertDialogBuilder(this).setTitle("放弃未确认的修改？")
            .setNegativeButton("继续编辑") { _, _ -> closeGuard = null }
            .setPositiveButton("放弃") { _, _ -> closeGuard = null; closeField(); next() }
            .setOnCancelListener { closeGuard = null }.showTracked()
    }

    private fun closeField() {
        fieldEditor?.close(); fieldEditor = null; fieldInitial = ""; fieldDraft = ""
    }

    private fun section(title: String, description: String? = null, help: String? = null, add: () -> Unit = {}) {
        group = null
        body?.let { target ->
            target.addView(settingsSectionHeader(title, help, onHelp = {
                MaterialAlertDialogBuilder(this).setTitle(title).setMessage(help)
                    .setPositiveButton("知道了", null).showTracked()
            }, addDescription = description, onAdd = add), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (target.childCount == 0) dp(4) else dp(16); bottomMargin = dp(8)
            })
        }
    }

    private fun addRow(view: View) {
        val target = body ?: return
        val container = group ?: column().apply { setPadding(0, 0, 0, 0) }.also { content ->
            target.addView(settingsListGroup(content), LinearLayout.LayoutParams(-1, -2).apply {
                if (target.childCount > 0) topMargin = dp(8)
            })
            group = content
        }
        if (container.childCount > 0) container.addView(View(this).apply {
            setBackgroundColor(BORDER)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(16) })
        container.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(title: String, detail: String = "", click: () -> Unit) = addRow(settingsListRow(title, detail, onClick = click))
    private fun managementRow(content: View, name: String, edit: () -> Unit, remove: () -> Unit, showEdit: Boolean = true) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        content.setPadding(content.paddingLeft, content.paddingTop, 0, content.paddingBottom)
        addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        if (showEdit) addView(iconButton(R.drawable.ic_line_edit, "编辑$name", edit))
        addView(iconButton(R.drawable.ic_line_trash, "删除$name", remove).apply {
            iconTint = ColorStateList.valueOf(MUTED)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
    }
    private fun valueRow(title: String, value: String) = addRow(settingsListRow(title, value))
    private fun note(value: String) {
        group = null
        body?.addView(label(value, 12f, true).apply { setPadding(dp(12), dp(8), dp(12), dp(12)) })
    }
    private fun action(title: String, primary: Boolean = false, click: () -> Unit) {
        group = null; body?.action(title, primary, click)
    }
    private fun apiHelp(message: String) { note(message); action("管理 API 服务") { navigate(Page.API) } }
    private fun guidedApiParent(): Page? {
        val parent = if (page == Page.KEY && history.lastOrNull() == Page.API) history.dropLast(1).lastOrNull()
            else if (page == Page.API) history.lastOrNull() else null
        return parent?.takeIf { it == Page.CARD || it == Page.PURPOSE }
    }
    private fun apiHelpDialog(message: String) {
        MaterialAlertDialogBuilder(this).setTitle("需要配置 API 服务").setMessage(message)
            .setNegativeButton("继续编辑", null).setPositiveButton("管理 API 服务") { _, _ -> navigate(Page.API) }.showTracked()
    }
    private fun choose(
        title: String, values: List<String>, checkedIndex: Int = -1,
        disabledIndices: Set<Int> = emptySet(), selected: (Int) -> Unit,
    ) {
        if (disposed) return
        if (values.isEmpty()) { toast("暂无可选项"); return }
        lateinit var sheet: SettingsChoiceSheet
        sheet = SettingsChoiceSheet(this, title, values, checkedIndex, disabledIndices = disabledIndices) { index ->
            if (disposed || index in disabledIndices) false else {
                // Close the picker before opening the next editor or navigating.
                sheet.dismiss()
                selected(index)
                true
            }
        }
        choiceSheets += sheet
        sheet.setOnDismissListener { choiceSheets.remove(sheet) }
        sheet.show()
    }
    private fun confirmDelete(title: String, message: String, remove: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> remove() }.showTracked()
    }
    private fun activeId(c: ServiceCatalog, p: ModelPurpose) = if (p == ModelPurpose.ANSWER) c.activeAnswerId else c.activeTranscriptionId
    private fun selectedServiceSummary(c: ServiceCatalog, p: ModelPurpose): String {
        val profile = c.profiles.find { it.id == activeId(c, p) && it.purpose == p } ?: return "未配置"
        val credential = c.credentials.find { it.id == profile.credentialId } ?: return "未配置"
        return "${providerName(c, c.providerIdOf(profile))} · ${credential.name} · ${profile.model}"
    }
    private fun providerName(c: ServiceCatalog, id: String) = c.providers.find { it.id == id }?.name
        ?: ProviderKind.entries.find { it.name.lowercase(Locale.ROOT) == id }?.title ?: "服务商已移除"
    private fun hideKeyboard() {
        activity.currentFocus?.let { focused ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }
    private fun id() = UUID.randomUUID().toString()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun MaterialAlertDialogBuilder.showTracked(): AlertDialog = create().showTracked()
    private fun AlertDialog.showTracked(): AlertDialog = also { dialog ->
        if (!disposed) { dialogs += dialog; dialog.setOnDismissListener { dialogs.remove(dialog) }; dialog.show() }
    }
    private fun dismissDialogs() {
        choiceSheets.toList().forEach { it.dismiss() }; choiceSheets.clear()
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); closeGuard = null
    }

    /** Only remove locally known unsupported values, preserving unknown model options. */
    private fun compatibleProfile(c: ServiceCatalog, old: ModelProfile, next: ModelProfile, done: () -> Unit = {}, error: (String) -> Unit = ::toast) {
        val config = optionConfig(c, next)
        var options = next.options
        val cleared = mutableListOf<String>()
        if (next.purpose == ModelPurpose.ANSWER && config != null) {
            if (options.webSearch && !ProviderCapabilities.search(config)) { options = options.copy(webSearch = false); cleared += "联网搜索" }
            if (options.verbosity.isNotBlank() && !ProviderCapabilities.verbosity(config)) { options = options.copy(verbosity = ""); cleared += "回答详细程度" }
            if ((options.temperature != null || options.topP != null) && !ProviderCapabilities.sampling(config)) { options = options.copy(temperature = null, topP = null); cleared += "temperature / top_p" }
            // Adapter checks also cover known model families (for example Claude token limits
            // and Gemini thinking levels). Find the smallest set of explicit values to clear.
            // A failed default request usually means an invalid model ID, not bad options.
            fun supported(candidate: GenerationOptions) = runCatching {
                val projected = candidate.applyTo(config)
                ProviderCapabilities.validate(projected, false)
                ModelOptionCompatibility.validate(projected)
            }.isSuccess
            if (!supported(options) && supported(GenerationOptions())) {
                val values = mutableListOf<Pair<String, (GenerationOptions) -> GenerationOptions>>()
                if (options.maxOutputTokens != 0) values += "最大输出 tokens" to { it: GenerationOptions -> it.copy(maxOutputTokens = 0) }
                if (options.reasoning.isNotBlank()) values += "推理程度" to { it: GenerationOptions -> it.copy(reasoning = "") }
                if (options.verbosity.isNotBlank()) values += "回答详细程度" to { it: GenerationOptions -> it.copy(verbosity = "") }
                if (options.temperature != null) values += "temperature" to { it: GenerationOptions -> it.copy(temperature = null) }
                if (options.topP != null) values += "top_p" to { it: GenerationOptions -> it.copy(topP = null) }
                if (options.webSearch) values += "联网搜索" to { it: GenerationOptions -> it.copy(webSearch = false) }
                val masks = (1 until (1 shl values.size)).sortedBy { Integer.bitCount(it) }
                for (mask in masks) {
                    var candidate = options
                    values.forEachIndexed { index, item -> if (mask and (1 shl index) != 0) candidate = item.second(candidate) }
                    if (supported(candidate)) {
                        values.forEachIndexed { index, item -> if (mask and (1 shl index) != 0) cleared += item.first }
                        options = candidate
                        break
                    }
                }
            }
        }
        val final = next.copy(options = options)
        val perform = { if (save(c, error = error) { it.upsertCard(final) }) done() }
        if (cleared.isEmpty()) perform() else MaterialAlertDialogBuilder(this).setTitle("调整不兼容参数？")
            .setMessage("本次更换已知不支持以下参数，将清除：${cleared.joinToString()}。其他参数保留。")
            .setNegativeButton("取消", null).setPositiveButton("确认并保存") { _, _ -> perform() }.showTracked()
    }

    private fun optionConfig(c: ServiceCatalog, p: ModelProfile): AppConfig? {
        val e = c.endpoints.find { it.id == p.endpointId } ?: return null
        val provider = c.providers.find { it.id == e.providerId } ?: return null
        return AppConfig(providerKind = provider.kind, apiProtocol = e.answerProtocol, baseUrl = e.baseUrl,
            apiVersion = e.apiVersion, model = p.model, maxOutputTokens = p.options.maxOutputTokens,
            reasoning = p.options.reasoning, verbosity = p.options.verbosity, temperature = p.options.temperature,
            topP = p.options.topP, webSearch = p.options.webSearch)
    }


    private fun modelCatalogLabel(model: RemoteModel): String = buildString {
        append(model.id)
        append("\n服务声明输入：")
        val inputs = model.inputModalities
        append(when {
            inputs == null -> "未知（未提供）"
            inputs.isEmpty() -> "无"
            else -> inputs.joinToString("、") { modality -> when (modality.lowercase()) {
                "text" -> "文字"
                "image" -> "图片"
                "audio" -> "音频"
                "video" -> "视频"
                else -> modality.take(64)
            } }
        })
        model.maxOutputTokens?.let { append("\n输出上限：$it tokens") }
        model.reasoningLevels?.let { levels ->
            append("\n推理档位：")
            append(if (levels.isEmpty()) "无（服务声明）" else levels.joinToString("、") { it.take(64) })
        }
    }

    private fun requestConfig(c: ServiceCatalog, p: ModelProfile): AppConfig? = try {
        require(catalog() == c) { "配置已改变，请重新打开服务配置" }
        // Model listing is allowed before a model ID has been supplied.
        val endpoint = c.endpoints.find { it.id == p.endpointId }
        val key = c.credentials.find { it.id == p.credentialId }
        require(endpoint != null && key != null && !key.unreadable && key.value.isNotBlank() && endpoint.id in key.endpointIds) { "请先选择已保存的 API 密钥" }
        c.project(store.load(), p.id)
    } catch (ex: Exception) { toast(ex.message ?: "配置不可用"); null }

    private fun chooseDiagnostic(c: ServiceCatalog, p: ModelProfile) {
        if (p.purpose == ModelPurpose.TRANSCRIPTION) {
            MaterialAlertDialogBuilder(this).setTitle("文件转写接口测试")
                .setMessage("将上传本机生成的 1 秒静音 WAV 到“${p.name}”。不录音、不读取文件。服务可能收费；该测试仅检查接口兼容性，不能证明语音识别正确。")
                .setNegativeButton("取消", null).setPositiveButton("上传测试音频") { _, _ -> diagnostic(c, p, "audio") }.showTracked()
        } else choose("测试此配置", listOf("文字：发送固定示例", "图片：发送本机红色色块", "搜索：实际执行联网搜索")) { index ->
            val kind = listOf("text", "image", "search")[index]
            val description = when (kind) {
                "image" -> "将发送本机生成的白底红色色块，不读取屏幕。"
                "search" -> "将要求此模型实际执行联网搜索。"
                else -> "将发送固定问题，要求回复 ALWAY_SIDE_OK。"
            }
            MaterialAlertDialogBuilder(this).setTitle("运行${listOf("文字", "图片", "搜索")[index]}测试？")
                .setMessage("配置：${p.name}\n$description\n仅测试此配置，不改变当前启用项。服务可能收费。")
                .setNegativeButton("取消", null).setPositiveButton("开始测试") { _, _ -> diagnostic(c, p, kind) }.showTracked()
        }
    }

    private fun diagnostic(c: ServiceCatalog, p: ModelProfile, kind: String) {
        c.readiness(p)?.let { toast(it); return }
        val original = requestConfig(c, p) ?: return
        // transcribe resolves its purpose binding. Override only the immutable test snapshot,
        // never the persisted active binding, so testing a draft cannot use another profile.
        val config = original.copy(webSearch = kind == "search", instructions = "请按测试问题直接回答。", continuationMessages = emptyList(),
            serviceCatalog = if (kind == "audio") c.copy(activeTranscriptionId = p.id) else c)
        if (kind != "audio") try { ProviderCapabilities.validate(config, kind == "image") } catch (ex: Exception) { toast(ex.message ?: "此配置不支持测试"); return }
        runRequest(c, "测试“${p.name}”", "正在调用本次保存的配置…", { token ->
            val provider = AiProvider()
            val result = if (kind == "audio") {
                val file = File.createTempFile("alwayside-transcription-test-", ".wav", cacheDir)
                try { file.writeBytes(TranscriptionProbe.audio()); provider.probeTranscription(config, file, token) } finally { file.delete() }
            } else provider.respond(config, emptyList(), when (kind) {
                "image" -> "图片中央色块是什么颜色？只回复中文颜色名称。"
                "search" -> "请实际使用联网搜索工具，查找今天的一则科技新闻，并给出日期与来源链接。"
                else -> "请只回复 ALWAY_SIDE_OK"
            }, if (kind == "image") diagnosticImage() else null, token) {}
            val verdict = when (kind) {
                "audio" -> "接口已完成静音音频请求；语音识别准确性未验证。"
                "search" -> if (provider.lastSearchUsed) "已验证：服务返回了实际搜索执行信息。" else "搜索未验证：服务未返回可核实的搜索执行信息。"
                "image" -> if (result.contains("红") || result.contains("red", true)) "已验证：正确识别示例红色色块。" else "图片识别未通过：未正确识别示例色块。"
                else -> if (result.trim() == "ALWAY_SIDE_OK") "已验证：正确返回指定文字。" else "文字请求完成，但未遵循测试要求。"
            }
            "$verdict\n\n${result.take(1600)}"
        }) { result ->
            verification[p.id] = c to result.lineSequence().first()
            status = ""; redraw()
            MaterialAlertDialogBuilder(this).setTitle("测试结果：${p.name}")
                .setMessage("$result\n\n结果仅适用于本次 API 连接、Key、模型和参数，修改后需重新测试。")
                .setPositiveButton("完成", null).showTracked()
        }
    }

    private fun <T> runRequest(c: ServiceCatalog, title: String, message: String, work: (RequestCancellation) -> T, done: (T) -> Unit) {
        cancelRequest()
        val token = RequestCancellation(); request = token
        val expectedGeneration = generation
        progress = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消") { _, _ -> cancelRequest() }.setOnCancelListener { cancelRequest() }.showTracked()
        Thread({
            try {
                val result = work(token)
                activity.runOnUiThread {
                    if (!currentRequest(token, expectedGeneration, c)) return@runOnUiThread
                    request = null; progress?.dismiss(); progress = null; done(result)
                }
            } catch (_: CancellationException) { /* Navigation and explicit cancellation discard results. */ }
            catch (ex: Exception) {
                activity.runOnUiThread {
                    if (!currentRequest(token, expectedGeneration, c)) return@runOnUiThread
                    request = null; progress?.dismiss(); progress = null
                    status = if (ex is ProviderException) ex.message.orEmpty() else "请求失败，请检查网络、API 连接与服务兼容性。"
                    redraw()
                }
            }
        }, "alwayside-service-settings").start()
    }

    private fun currentRequest(token: RequestCancellation, expected: Int, c: ServiceCatalog): Boolean {
        if (disposed || activity.isFinishing || activity.isDestroyed || request !== token || generation != expected) return false
        if (runCatching { catalog() == c }.getOrDefault(false)) return true
        cancelRequest(); status = "服务、Key 或配置已改变，本次结果已丢弃。请重新读取或测试。"; redraw(); return false
    }

    private fun cancelRequest() {
        generation++; request?.cancel(); request = null; progress?.dismiss(); progress = null
    }


    private fun diagnosticImage(): String {
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawRect(40f, 20f, 120f, 80f, Paint().apply { color = Color.RED }) }
            val bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
