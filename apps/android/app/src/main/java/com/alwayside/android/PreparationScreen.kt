package com.alwayside.android

import android.app.Activity
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** An explicit destination after the simulation; merely opening it never tests or starts a service. */
internal class PreparationScreen(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val onSettings: (String) -> Unit,
    private val onToggleAssistant: () -> Unit,
    var helpExpanded: Boolean = false,
) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND) }

    init { refresh() }

    fun refresh(): Unit = with(activity) {
        view.removeAllViews()
        view.addView(materialToolbar("准备实际使用", onBack))
        val body = column()
        view.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("以下为实际使用设置", 20f))
        body.addView(label("模拟体验已经结束。这里不会自动调用 API；之后真正提问会向你配置的服务发送内容，并可能产生费用。",14f,true).apply {
            setPadding(0,dp(12),0,dp(20))
        })
        val connection = try { SettingsStore(this).connectionPreparation() }
            catch (_: Exception) { ConnectionPreparation(readable = false) }
        val api = column().apply { background = card(SURFACE) }
        api.addView(label("1 · 连接你自己的 AI 服务",17f))
        api.addView(label("AlwaySide 提供截图与对话界面，AI 服务负责生成回答。服务地址决定请求发往哪里，API 密钥是调用凭证；截图需要支持图片输入的模型。",14f,true).apply {
            setPadding(0,dp(12),0,dp(12))
        })
        api.action(if(helpExpanded) "收起获取说明" else "还没有服务地址或密钥？") {
            helpExpanded=!helpExpanded; refresh()
        }
        if(helpExpanded) api.addView(label(
            "从你选择的 AI 服务提供方注册账号，在其控制台创建 API 密钥，并查看接口地址及支持图片的模型名称。没有这些资料时可以先返回，之后继续。\n\n" +
            "服务需要支持 Responses 问答接口和图片输入，仅支持 Chat Completions 的服务不兼容。模型列表中有某个名称，不代表它支持看图。\n\n" +
            "密钥应只填写在你信任的服务地址下；不要把它当成练习内容或发给其他人。费用和可用额度由服务提供方决定。",14f,true))
        if(connection.readable) api.addView(label(
            "服务地址：${if(connection.addressEntered) "已填写" else "待填写"}\n" +
            "API 密钥：${if(connection.credentialSaved) "已保存，尚未验证" else "待填写"}\n" +
            "图文模型：${if(connection.imageModelEntered) "已填写，请确认支持图片" else "待填写"}",14f).apply {
                setPadding(0,dp(12),0,dp(12))
            })
        api.addView(label(connection.description,14f,true))
        api.action("填写服务地址与密钥",true) { onSettings("API") }
        api.action("选择图文模型") { onSettings("MODELS") }
        api.addView(label("保存不会自动测试。设置中的模型列表操作会向服务发起真实网络请求，请按需主动使用。",12f,true).apply {
            setPadding(0,dp(12),0,0)
        })
        body.addView(api)
        val overlay = column().apply { background = card(SURFACE) }
        overlay.addView(label("2 · 按需开启悬浮助手",17f))
        val granted = Settings.canDrawOverlays(this)
        overlay.addView(label("悬浮显示权限：${if(granted) "已允许" else "未允许"}\n" +
            "悬浮助手：${if(ProbeState.serviceRunning) "已开启" else "未开启"}",14f).apply { setPadding(0,dp(12),0,dp(12)) })
        overlay.addView(label("开启后可以在其他应用中点小球，再选择截图问答。开启助手本身不会发送提问；截图操作可能按设置自动发送画面，并消耗你的 API 额度。",14f,true))
        overlay.action(if(ProbeState.serviceRunning) "关闭真实悬浮助手" else "开启真实悬浮助手",true,onToggleAssistant)
        overlay.addView(label("系统截图授权会在真正截图时按需请求。普通应用内文字聊天不需要悬浮权限。",12f,true).apply {
            setPadding(0,dp(12),0,0)
        })
        body.addView(overlay,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
    }
}
