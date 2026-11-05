package com.alwayside.android

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException

/** Purpose bindings and service resources have separate navigation and explicit activation. */
internal class ServiceSettingsUi(
    private val activity: Activity,
    private val onChanged: () -> Unit = {},
) : ContextWrapper(activity) {
    private val store = SettingsStore(this)
    private var body: LinearLayout? = null
    private var mode: Boolean? = null
    private var page = ""
    private var purpose: ModelPurpose? = null
    private var selectedId: String? = null
    private var providerId: String? = null
    private var disposed = false
    private var generation = 0
    private var request: RequestCancellation? = null
    private var progress: AlertDialog? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private var editor: SettingsEditPanel? = null
    private var editInitial = ""
    private var editDraft = ""
    private var closeGuard: AlertDialog? = null
    private var status = ""
    private val verification = mutableMapOf<String, Pair<ServiceCatalog, String>>()

    fun render(container: LinearLayout, services: Boolean) {
        if (disposed) return
        if (mode != services) {
            cancelRequest(); closeEditor(); dismissDialogs()
            page = ""; selectedId = null; providerId = null; purpose = null; status = ""
            mode = services
        }
        body = container
        redraw()
    }

    fun back(): Boolean {
        if (editor != null) { finishEditing {}; return true }
        if (request != null) { cancelRequest(); return true }
        if (page.isEmpty()) return false
        finishEditing {
            cancelRequest(); dismissDialogs(); status = ""
            when (page) {
                "profile" -> { page = "purpose"; selectedId = null }
                "endpoint", "credential" -> { page = "provider"; selectedId = providerId }
                else -> { page = ""; selectedId = null; purpose = null }
            }
            redraw()
        }
        return true
    }

    fun finishEditing(next: () -> Unit) {
        if (editor == null) { next(); return }
        if (editDraft == editInitial) { closeEditor(); next(); return }
        if (closeGuard != null) return
        closeGuard = MaterialAlertDialogBuilder(this).setTitle("放弃未保存的修改？")
            .setMessage("本字段的修改尚未保存。")
            .setNegativeButton("继续编辑") { _, _ -> closeGuard = null }
            .setPositiveButton("放弃并离开") { _, _ -> closeGuard = null; closeEditor(); next() }
            .setOnCancelListener { closeGuard = null }.showTracked()
    }

    fun dispose() {
        disposed = true; cancelRequest(); closeEditor(); dismissDialogs(); body = null
    }

    private fun catalog() = requireNotNull(store.load().serviceCatalog)
    private fun redraw() {
        if (disposed) return
        val target = body ?: return
        target.removeAllViews()
        try {
            val c = catalog()
            if (page.isNotEmpty()) {
                target.addView(iconButton(android.R.drawable.ic_menu_revert, "返回上层") { back() })
            }
            when (page) {
                "purpose" -> renderPurpose(c, requireNotNull(purpose))
                "profile" -> c.profiles.find { it.id == selectedId }?.let { renderProfile(c, it) } ?: missing()
                "provider" -> c.providers.find { it.id == selectedId }?.let { renderProvider(c, it) } ?: missing()
                "endpoint" -> c.endpoints.find { it.id == selectedId }?.let { renderEndpoint(c, it) } ?: missing()
                "credential" -> c.credentials.find { it.id == selectedId }?.let { renderCredential(c, it) } ?: missing()
                else -> if (mode == true) renderServices(c) else renderOverview(c)
            }
            if (status.isNotBlank()) note(status)
        } catch (_: Exception) {
            note("无法读取服务配置，请检查本机安全存储后重新打开设置。")
            body?.action("重置无法读取的配置") {
                MaterialAlertDialogBuilder(this).setTitle("重置服务与模型配置？")
                    .setMessage("将清除服务商、接入点、密钥、模型配置及提示词。历史、快捷动作和模板保留。此操作无法撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("重置") { _, _ ->
                        try { store.resetConfiguration(); page = ""; redraw() }
                        catch (_: Exception) { toast("重置失败，请检查本机存储") }
                    }.showTracked()
            }
        }
    }

    private fun renderOverview(c: ServiceCatalog) {
        title("模型与服务")
        for (p in ModelPurpose.entries) {
            val active = c.profiles.find { it.id == activeId(c, p) }
            row(p.title, active?.let { "当前：${it.name}\n${summary(c, it)}" } ?: "未启用 · 点击管理配置") {
                navigate("purpose", purpose = p)
            }
        }
        note("图文问答与音频转写分别选择配置。点击只查看详情；保存草稿不会启用。能力未知的模型可手动填写并主动测试。")
    }

    private fun renderPurpose(c: ServiceCatalog, p: ModelPurpose) {
        heading(p.title, "添加${p.title}配置") {
            val profile = ModelProfile(id(), "新${p.title}配置", p,
                transcriptionPrompt = if (p == ModelPurpose.TRANSCRIPTION) store.load().transcriptionPrompt else "")
            if (save(c) { it.copy(profiles = it.profiles + profile) }) navigate("profile", profile.id, p)
        }
        val active = activeId(c, p)
        c.profiles.filter { it.purpose == p }.forEach { profile ->
            row(profile.name, "${if (profile.id == active) "当前已启用" else "未启用"} · ${c.readiness(profile) ?: verificationStatus(c, profile)}\n${summary(c, profile)}") {
                navigate("profile", profile.id, p)
            }
        }
        if (c.profiles.none { it.purpose == p }) note("尚无配置。添加后选择接入点、命名 Key 和模型，再明确启用。")
        if (active != null) body?.action("停用${p.title}") {
            save(c) { if (p == ModelPurpose.ANSWER) it.copy(activeAnswerId = null) else it.copy(activeTranscriptionId = null) }
        }
    }

    private fun renderProfile(c: ServiceCatalog, p: ModelProfile) {
        title(p.name)
        note("${p.purpose.title}配置 · ${if (activeId(c, p.purpose) == p.id) "当前已启用" else "未启用"}\n${c.readiness(p) ?: verificationStatus(c, p)}")
        field("配置名称", p.name) { text(c, "配置名称", p.name) { value -> updateProfile(c, p.copy(name = name(value))) } }
        val endpoint = c.endpoints.find { it.id == p.endpointId }
        field("服务接入点", endpoint?.let { "${providerName(c, it.providerId)} / ${it.name}" } ?: "未选择") {
            val endpoints = c.endpoints.filter { p.purpose == ModelPurpose.ANSWER || it.transcriptionApi == FileTranscriptionApi.OPENAI_COMPATIBLE }
            choose("选择${p.purpose.title}接入点", endpoints.map { "${providerName(c, it.providerId)} / ${it.name}" }) { index ->
                val chosen = endpoints[index]
                val key = c.credentials.find { it.id == p.credentialId }
                val keep = key != null && key.providerId == chosen.providerId && chosen.id in key.endpointIds
                compatibleProfile(c, p, p.copy(endpointId = chosen.id, credentialId = if (keep) p.credentialId else null))
            }
        }
        val key = c.credentials.find { it.id == p.credentialId }
        field("命名 Key", key?.let { "${it.name} · ${if (it.unreadable) "无法解密，请替换" else "••••••"}" } ?: "未选择") {
            if (endpoint == null) toast("请先选择接入点") else {
                val keys = c.credentials.filter { it.providerId == endpoint.providerId && endpoint.id in it.endpointIds }
                choose("选择此接入点已授权的 Key", keys.map { it.name + if (it.unreadable) "（无法解密）" else "" }) { index ->
                    updateProfile(c, p.copy(credentialId = keys[index].id))
                }
            }
        }
        field("模型 ID", p.model.ifBlank { "手动填写" }) {
            text(c, "模型 ID", p.model, "可手动填写服务支持的模型 ID；未知能力不会被当作已验证。", saveText = { value, done, error ->
                compatibleProfile(c, p, p.copy(model = value.trim()), done, error)
            })
        }
        body?.addView(iconButton(android.R.drawable.ic_menu_rotate, "读取此接入点与 Key 的模型列表") { finishEditing { fetchModels(c, p) } })
        note("模型列表只表示本 Key 可见的 ID，不证明模型支持图片、搜索或转写。更换 Key 会保留本配置参数。")
        if (p.purpose == ModelPurpose.ANSWER) renderOptions(c, p) else {
            field("转写提示", p.transcriptionPrompt.ifBlank { "不设置" }) {
                text(c, "转写提示", p.transcriptionPrompt, multiline = true) { updateProfile(c, p.copy(transcriptionPrompt = it)) }
            }
            field("语言", p.language.ifBlank { "自动识别" }) {
                text(c, "语言", p.language, "留空自动识别，或填写服务接受的语言代码，如 zh、en。") { updateProfile(c, p.copy(language = it.trim())) }
            }
        }
        body?.action("设为${p.purpose.title}", true) {
            if (activeId(c, p.purpose) != p.id && save(c, "已将“${p.name}”设为${p.purpose.title}") { it.activate(p.id) }) redraw()
        }?.apply { isEnabled = activeId(c, p.purpose) != p.id }
        body?.action("测试此配置") { finishEditing { chooseDiagnostic(c, p) } }
        body?.addView(iconButton(R.drawable.ic_line_trash, "删除此模型配置") {
            confirmDelete("删除“${p.name}”？", if (activeId(c, p.purpose) == p.id) "将停用${p.purpose.title}，不会自动选择其他配置。" else "此配置尚未启用。服务与命名 Key 会保留。") {
                if (save(c) { it.removeProfile(p.id) }) { page = "purpose"; redraw() }
            }
        })
    }

    private fun renderOptions(c: ServiceCatalog, p: ModelProfile) {
        title("问答参数")
        val o = p.options
        val config = optionConfig(c, p)
        val verbositySupported = config == null || ProviderCapabilities.verbosity(config)
        val samplingSupported = config == null || ProviderCapabilities.sampling(config)
        val searchSupported = config == null || ProviderCapabilities.search(config)
        val knownModel = config != null && runCatching { ModelOptionCompatibility.validate(GenerationOptions().applyTo(config)) }.isSuccess
        field("最大输出 tokens", o.maxOutputTokens.takeIf { it != 0 }?.toString() ?: "使用服务默认") {
            text(c, "最大输出 tokens", o.maxOutputTokens.takeIf { it != 0 }?.toString().orEmpty(), "留空或填写 16–131072", numeric = true) {
                updateProfile(c, p.copy(options = o.copy(maxOutputTokens = if (it.isBlank()) 0 else it.toInt())))
            }
        }
        field("推理程度", o.reasoning.ifBlank { "不设置" }) {
            val values = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max").filter { value ->
                value.isEmpty() || !knownModel || runCatching {
                    ModelOptionCompatibility.validate(GenerationOptions(reasoning = value).applyTo(requireNotNull(config)))
                }.isSuccess
            }
            choose("推理程度", values.map { it.ifBlank { "不设置" } }) { updateProfile(c, p.copy(options = o.copy(reasoning = values[it]))) }
        }
        if (verbositySupported || o.verbosity.isNotBlank()) field("回答详细程度", o.verbosity.ifBlank { "不设置" }) {
            val values = if (verbositySupported) listOf("", "low", "medium", "high") else listOf("")
            choose("回答详细程度", values.map { it.ifBlank { "不设置" } }) { updateProfile(c, p.copy(options = o.copy(verbosity = values[it]))) }
        }
        if (samplingSupported || o.temperature != null) field("temperature", o.temperature?.toString() ?: "不设置") {
            if (!samplingSupported) choose("此协议不支持 temperature", listOf("清空已有值")) { updateProfile(c, p.copy(options = o.copy(temperature = null))) }
            else text(c, "temperature", o.temperature?.toString().orEmpty(), "留空或填写 0–2；与 top_p 只设置一项。", decimal = true) {
                updateProfile(c, p.copy(options = o.copy(temperature = it.trim().takeIf(String::isNotEmpty)?.toDouble())))
            }
        }
        if (samplingSupported || o.topP != null) field("top_p", o.topP?.toString() ?: "不设置") {
            if (!samplingSupported) choose("此协议不支持 top_p", listOf("清空已有值")) { updateProfile(c, p.copy(options = o.copy(topP = null))) }
            else text(c, "top_p", o.topP?.toString().orEmpty(), "留空或填写 0–1；与 temperature 只设置一项。", decimal = true) {
                updateProfile(c, p.copy(options = o.copy(topP = it.trim().takeIf(String::isNotEmpty)?.toDouble())))
            }
        }
        if (searchSupported || o.webSearch) field("联网搜索", if (o.webSearch) "开启" else "关闭") {
            choose("联网搜索", if (searchSupported) listOf("关闭", "开启") else listOf("关闭")) { updateProfile(c, p.copy(options = o.copy(webSearch = it == 1))) }
        }
        note("参数仅属于此配置。留空使用服务默认；未知模型限制保留显式值。服务仍可能拒绝其不支持的参数。")
    }

    private fun renderServices(c: ServiceCatalog) {
        heading("服务商与密钥", "添加服务商实例") {
            choose("添加服务商实例", ProviderKind.entries.map { it.title }) { index ->
                val kind = ProviderKind.entries[index]
                val provider = ServiceProvider(id(), kind.title, kind)
                val endpoint = ServiceEndpoint(id(), provider.id, "默认接入点", kind.baseUrl, kind.protocol,
                    if (kind == ProviderKind.OPENAI) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE)
                if (save(c) { it.copy(providers = it.providers + provider, endpoints = it.endpoints + endpoint) }) navigate("provider", provider.id)
            }
        }
        c.providers.forEach { p ->
            row(p.name, "${p.kind.title} · ${c.endpoints.count { it.providerId == p.id }} 个接入点 · ${c.credentials.count { it.providerId == p.id }} 个命名 Key") {
                navigate("provider", p.id)
            }
        }
        if (c.providers.isEmpty()) note("添加服务商实例后，可分别配置接入点和多个命名 Key。同一家服务商可以添加多个实例。")
    }

    private fun renderProvider(c: ServiceCatalog, p: ServiceProvider) {
        title(p.name)
        field("实例名称", p.name) { text(c, "实例名称", p.name) { value -> save(c) { it.copy(providers = it.providers.map { old -> if (old.id == p.id) p.copy(name = name(value)) else old }) } } }
        note("厂商类型：${p.kind.title}。每个接入点独立声明回答协议与文件转写接口，不能由聊天协议推断转写支持。")
        heading("接入点", "添加接入点") {
            val e = ServiceEndpoint(id(), p.id, "新接入点", p.kind.baseUrl, p.kind.protocol,
                if (p.kind == ProviderKind.OPENAI) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE)
            if (save(c) { it.copy(endpoints = it.endpoints + e) }) navigate("endpoint", e.id)
        }
        c.endpoints.filter { it.providerId == p.id }.forEach { e ->
            row(e.name, "${e.answerProtocol.title} · ${if (e.transcriptionApi == FileTranscriptionApi.NONE) "未声明文件转写" else "OpenAI 兼容文件转写"}\n${e.baseUrl}") { navigate("endpoint", e.id) }
        }
        heading("命名 Key", "添加命名 Key") {
            val k = ServiceCredential(id(), p.id, "新 Key")
            if (save(c) { it.copy(credentials = it.credentials + k) }) navigate("credential", k.id)
        }
        c.credentials.filter { it.providerId == p.id }.forEach { k ->
            row(k.name, "${if (k.unreadable) "无法解密 · 请替换或删除" else if (k.value.isBlank()) "尚未填写" else "•••••• · 已加密保存"} · 授权 ${k.endpointIds.size} 个接入点\n${references(c) { it.credentialId == k.id }}") { navigate("credential", k.id) }
        }
        body?.addView(iconButton(R.drawable.ic_line_trash, "删除此服务商实例") {
            val ids = c.endpoints.filter { it.providerId == p.id }.map { it.id }.toSet()
            confirmDelete("删除“${p.name}”？", "将删除该实例的接入点与命名 Key。\n${references(c) { it.endpointId in ids }}\n受影响配置会解除绑定并停用，不会切到其他服务。") {
                if (save(c) { it.removeProvider(p.id) }) { page = ""; redraw() }
            }
        })
    }

    private fun renderEndpoint(c: ServiceCatalog, e: ServiceEndpoint) {
        title(e.name)
        field("接入点名称", e.name) { text(c, "接入点名称", e.name) { updateEndpoint(c, e.copy(name = name(it))) } }
        field("基础地址", e.baseUrl) {
            text(c, "基础地址", e.baseUrl, "使用 HTTPS 基础地址；更换域名或端口会解除此接入点的 Key 授权。", saveText = { value, done, error ->
                val address = value.trim().trimEnd('/')
                try {
                    ProviderProtocol.endpoint(address, "models")
                    val changedOrigin = origin(e.baseUrl) != origin(address)
                    val perform = {
                        if (updateEndpoint(c, e.copy(baseUrl = address), changedOrigin, error)) done()
                    }
                    if (changedOrigin) MaterialAlertDialogBuilder(this).setTitle("地址所属站点已改变")
                        .setMessage("保存后会解除所有 Key 对此接入点的授权，需重新明确授权。\n${references(c) { it.endpointId == e.id }}")
                        .setNegativeButton("继续编辑", null).setPositiveButton("保存并解除授权") { _, _ -> perform() }.showTracked()
                    else perform()
                } catch (ex: Exception) { error(ex.message ?: "地址无效") }
            })
        }
        field("回答协议", e.answerProtocol.title) {
            val protocols = when (c.providers.first { it.id == e.providerId }.kind) {
                ProviderKind.DEEPSEEK -> listOf(ApiProtocol.RESPONSES)
                ProviderKind.CLAUDE -> listOf(ApiProtocol.CLAUDE)
                ProviderKind.GEMINI -> listOf(ApiProtocol.GEMINI, ApiProtocol.CHAT_COMPLETIONS)
                ProviderKind.CUSTOM -> ApiProtocol.entries.toList()
                else -> listOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS)
            }
            choose("回答协议", protocols.map { it.title }) { index ->
                val protocol = protocols[index]
                if (protocol != e.answerProtocol) {
                    val currentHost = URI(e.baseUrl).host
                    val gemini = currentHost == "generativelanguage.googleapis.com"
                    val next = e.copy(answerProtocol = protocol, baseUrl = if (gemini)
                        "https://generativelanguage.googleapis.com/v1beta" + (if (protocol == ApiProtocol.CHAT_COMPLETIONS) "/openai" else "") else e.baseUrl)
                    MaterialAlertDialogBuilder(this).setTitle("更改此接入点的回答协议？")
                        .setMessage("${references(c) { it.endpointId == e.id }}\n模型参数将保留；请在各配置中检查兼容性。正在执行的请求保留原协议。")
                        .setNegativeButton("取消", null).setPositiveButton("更改协议") { _, _ -> updateEndpoint(c, next) }.showTracked()
                }
            }
        }
        field("文件转写接口", if (e.transcriptionApi == FileTranscriptionApi.NONE) "未声明 / 不支持" else "OpenAI 兼容 /audio/transcriptions") {
            choose("明确声明文件转写接口", listOf("未声明 / 不支持", "OpenAI 兼容 /audio/transcriptions")) {
                updateEndpoint(c, e.copy(transcriptionApi = if (it == 1) FileTranscriptionApi.OPENAI_COMPATIBLE else FileTranscriptionApi.NONE))
            }
        }
        note("仅在服务确实提供兼容接口时开启文件转写。声明不是网络验证；当前客户端只实现 OpenAI 兼容文件转写。")
        field("API 版本", e.apiVersion) { text(c, "API 版本", e.apiVersion) { updateEndpoint(c, e.copy(apiVersion = it.trim())) } }
        field("工作区 ID", e.workspaceId.ifBlank { "不设置" }) { text(c, "工作区 ID", e.workspaceId) { updateEndpoint(c, e.copy(workspaceId = it.trim())) } }
        note(references(c) { it.endpointId == e.id })
        body?.addView(iconButton(R.drawable.ic_line_trash, "删除此接入点") {
            confirmDelete("删除“${e.name}”？", "${references(c) { it.endpointId == e.id }}\n会解除配置绑定与 Key 授权，并停用受影响用途，不自动回退。") {
                if (save(c) { it.removeEndpoint(e.id) }) { page = "provider"; selectedId = e.providerId; redraw() }
            }
        })
    }

    private fun renderCredential(c: ServiceCatalog, k: ServiceCredential) {
        title(k.name)
        note(if (k.unreadable) "此 Key 无法解密，其他服务仍可使用。请替换或删除该 Key。" else if (k.value.isBlank()) "尚未填写凭证。编辑时始终使用空输入，不回显已有 Key。" else "•••••• · 凭证已加密保存；编辑时不回显已有 Key。")
        field("Key 名称", k.name) { text(c, "Key 名称", k.name) { updateCredential(c, k.copy(name = name(it))) } }
        field("替换 Key", "输入新凭证") {
            text(c, "替换 Key", "", "输入完整新 Key，留空不会修改。已有凭证不会回显。", secret = true) {
                if (it.isBlank() || (!k.unreadable && it.trim() == k.value)) true
                else updateCredential(c, k.copy(value = it.trim(), revision = k.revision + 1, unreadable = false))
            }
        }
        field("接入点授权范围", c.endpoints.filter { it.id in k.endpointIds }.joinToString { it.name }.ifBlank { "未授权" }) {
            val endpoints = c.endpoints.filter { it.providerId == k.providerId }
            if (endpoints.isEmpty()) toast("请先添加接入点") else {
                val checked = endpoints.map { it.id in k.endpointIds }.toBooleanArray()
                MaterialAlertDialogBuilder(this).setTitle("允许此 Key 用于哪些接入点")
                    .setMultiChoiceItems(endpoints.map { "${it.name}\n${it.baseUrl}" }.toTypedArray(), checked) { _, index, enabled -> checked[index] = enabled }
                    .setNegativeButton("取消", null).setPositiveButton("保存授权") { _, _ ->
                        updateCredential(c, k.copy(endpointIds = endpoints.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()))
                    }.showTracked()
            }
        }
        note(references(c) { it.credentialId == k.id })
        body?.addView(iconButton(R.drawable.ic_line_trash, "撤销并删除此 Key") {
            confirmDelete("撤销并删除“${k.name}”？", "${references(c) { it.credentialId == k.id }}\n本机将删除凭证并解除引用、停用受影响用途；若要在服务商侧吊销，请在服务商控制台操作。") {
                if (save(c) { it.removeCredential(k.id) }) { page = "provider"; selectedId = k.providerId; redraw() }
            }
        })
    }

    private fun updateProfile(c: ServiceCatalog, p: ModelProfile, error: (String) -> Unit = ::toast) =
        save(c, error = error) { it.copy(profiles = it.profiles.map { old -> if (old.id == p.id) p else old }) }

    /** Only remove locally known unsupported values, preserving unknown model options. */
    private fun compatibleProfile(c: ServiceCatalog, old: ModelProfile, next: ModelProfile, done: () -> Unit = {}, error: (String) -> Unit = ::toast) {
        if (old == next) { done(); return }
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
        val perform = { if (updateProfile(c, final, error)) done() }
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

    private fun updateEndpoint(c: ServiceCatalog, e: ServiceEndpoint, revoke: Boolean = false, error: (String) -> Unit = ::toast) =
        save(c, error = error) {
            it.copy(endpoints = it.endpoints.map { old -> if (old.id == e.id) e else old },
                credentials = if (revoke) it.credentials.map { k -> k.copy(endpointIds = k.endpointIds - e.id) } else it.credentials)
        }

    private fun updateCredential(c: ServiceCatalog, k: ServiceCredential) = save(c) {
        it.copy(credentials = it.credentials.map { old -> if (old.id == k.id) k else old })
    }

    private fun save(c: ServiceCatalog, message: String = "已保存", error: (String) -> Unit = ::toast, transform: (ServiceCatalog) -> ServiceCatalog): Boolean = try {
        val next = transform(c)
        if (next != c) {
            next.validate()
            store.updateCatalog(c) { next }
            cancelRequest(); status = ""; toast(message); onChanged(); redraw()
        }
        true
    } catch (ex: IllegalArgumentException) { error(ex.message ?: "配置无效"); false }
    catch (_: Exception) { error("保存失败或配置已改变，请重新打开此页面后重试"); false }

    private fun fetchModels(c: ServiceCatalog, p: ModelProfile) {
        val config = requestConfig(c, p) ?: return
        val explanation = "仅请求“${p.name}”已保存接入点与 Key 的模型列表，不发送聊天或媒体。能力信息来自服务声明，与本次用途验证分别显示。" +
            if (p.purpose == ModelPurpose.TRANSCRIPTION) "输入模态声明不能证明支持 /audio/transcriptions，转写接口需单独测试。" else ""
        runRequest(c, "读取模型", explanation, { token -> AiProvider().listModelCatalog(config, token) }) { models ->
            if (models.isEmpty()) { toast("服务未返回模型，可手动填写 ID"); return@runRequest }
            choose("选择模型（服务声明，尚未验证）", models.map(::modelCatalogLabel)) { index ->
                compatibleProfile(c, p, p.copy(model = models[index].id))
            }
        }
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
        require(catalog() == c) { "配置已改变，请重新打开详情" }
        // Model listing is allowed before a model ID has been supplied.
        val endpoint = c.endpoints.find { it.id == p.endpointId }
        val key = c.credentials.find { it.id == p.credentialId }
        require(endpoint != null && key != null && !key.unreadable && key.value.isNotBlank() && endpoint.id in key.endpointIds) { "请先选择接入点并保存已授权的 Key" }
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
                try { file.writeBytes(silentWav()); provider.transcribe(config, file, token) } finally { file.delete() }
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
            status = "${p.name} · $result"; redraw()
            MaterialAlertDialogBuilder(this).setTitle("测试结果：${p.name}")
                .setMessage("$result\n\n结果仅适用于本次接入点、Key、模型和参数，修改后需重新测试。")
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
                    status = if (ex is ProviderException) ex.message.orEmpty() else "请求失败，请检查网络、接入点与服务兼容性。"
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

    private fun text(c: ServiceCatalog, title: String, initial: String, helper: String = "保存仅修改本字段，不改变当前启用项。", secret: Boolean = false,
                     multiline: Boolean = false, numeric: Boolean = false, decimal: Boolean = false,
                     saveText: ((String, () -> Unit, (String) -> Unit) -> Unit)? = null,
                     saveValue: ((String) -> Boolean)? = null) {
        finishEditing {
            editInitial = initial; editDraft = initial
            editor = SettingsEditPanel(this, title, helper, initial, multiline, secret, numeric, decimal,
                onDraft = { editDraft = it }, onRequestClose = { finishEditing {} },
                onSave = { value, error ->
                    if (value == initial) closeEditor() else try {
                        require(catalog() == c) { "配置已改变，请放弃草稿后重新编辑" }
                        if (saveText != null) saveText(value, { closeEditor(); redraw() }, error)
                        else if (saveValue?.invoke(value) == true) { closeEditor(); redraw() }
                    } catch (ex: Exception) { error(ex.message ?: "输入无效") }
                }).also { it.show() }
        }
    }

    private fun closeEditor() {
        closeGuard?.dismiss(); closeGuard = null; editor?.close(); editor = null; editInitial = ""; editDraft = ""
    }

    private fun navigate(next: String, id: String? = null, purpose: ModelPurpose? = this.purpose) {
        finishEditing {
            cancelRequest(); dismissDialogs(); status = ""
            if (next == "provider") providerId = id
            page = next; selectedId = id; this.purpose = purpose; redraw()
        }
    }

    private fun title(value: String) { body?.addView(label(value, 20f).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }) }
    private fun heading(value: String, addDescription: String, add: () -> Unit) {
        body?.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(value, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_line_plus, addDescription) { finishEditing(add) })
        })
    }
    private fun note(value: String) { body?.addView(label(value, 12f, true).apply { setPadding(dp(8), dp(8), dp(8), dp(12)) }) }
    private fun field(title: String, value: String, click: () -> Unit) = row(title, value, click)
    private fun row(title: String, value: String, click: () -> Unit) {
        body?.addView(column().apply {
            background = outlinedCard(SURFACE); isClickable = true; isFocusable = true; foreground = touchFeedback()
            contentDescription = "$title，$value"; setOnClickListener { finishEditing(click) }
            addView(label(title, 15f)); addView(label(value, 13f, true).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun choose(title: String, values: List<String>, selected: (Int) -> Unit) {
        if (values.isEmpty()) { toast("暂无可选项，请先在“服务商与密钥”中添加并授权。"); return }
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(values.toTypedArray()) { _, index -> selected(index) }.setNegativeButton("取消", null).showTracked()
    }
    private fun confirmDelete(title: String, message: String, remove: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> finishEditing(remove) }.showTracked()
    }
    private fun activeId(c: ServiceCatalog, p: ModelPurpose) = if (p == ModelPurpose.ANSWER) c.activeAnswerId else c.activeTranscriptionId
    private fun verificationStatus(c: ServiceCatalog, p: ModelProfile) = verification[p.id]?.takeIf { it.first == c }?.second
        ?: "已保存 · 能力待验证"
    private fun providerName(c: ServiceCatalog, id: String) = c.providers.find { it.id == id }?.name ?: "服务已移除"
    private fun summary(c: ServiceCatalog, p: ModelProfile): String {
        val e = c.endpoints.find { it.id == p.endpointId }
        val k = c.credentials.find { it.id == p.credentialId }
        return "${e?.let { "${providerName(c, it.providerId)} / ${it.name}" } ?: "未绑定接入点"} · ${k?.name ?: "未绑定 Key"}\n${p.model.ifBlank { "未填写模型 ID" }}"
    }
    private fun references(c: ServiceCatalog, predicate: (ModelProfile) -> Boolean): String {
        val profiles = c.profiles.filter(predicate)
        return if (profiles.isEmpty()) "没有模型配置引用" else "引用配置：" + profiles.joinToString { "${it.purpose.title} / ${it.name}${if (activeId(c, it.purpose) == it.id) "（已启用）" else ""}" }
    }
    private fun missing() { page = ""; selectedId = null; note("此项目已删除，请返回上层重新选择。") }
    private fun id() = UUID.randomUUID().toString()
    private fun name(value: String) = value.trim().also { require(it.isNotBlank() && it.length <= 80) { "名称需为 1–80 字" } }
    private fun origin(address: String): String { val uri = URI(address); return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${if (uri.port < 0) 443 else uri.port}" }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun MaterialAlertDialogBuilder.showTracked(): AlertDialog = create().also { dialog ->
        if (!disposed) { dialogs += dialog; dialog.setOnDismissListener { dialogs.remove(dialog) }; dialog.show() }
    }
    private fun dismissDialogs() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }

    private fun diagnosticImage(): String {
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawRect(40f, 20f, 120f, 80f, Paint().apply { color = Color.RED }) }
            val bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
    private fun silentWav(): ByteArray = ByteBuffer.allocate(44 + 32000).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(32036); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1.toShort()); putShort(1.toShort()); putInt(16000); putInt(32000); putShort(2.toShort()); putShort(16.toShort())
        put("data".toByteArray()); putInt(32000); put(ByteArray(32000))
    }.array()
}
