package com.alwayside.android

import android.app.Activity
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Three short actions; explanations are available on demand, never ahead of the next action. */
internal class PreparationScreen(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val onSettings: (String) -> Unit,
    private val onToggleAssistant: () -> Unit,
) {
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(BACKGROUND) }
    init { refresh() }

    fun refresh(): Unit = with(activity) {
        view.removeAllViews()
        view.addView(materialToolbar("准备实际使用",onBack).apply {
            addIconAction(702,R.drawable.ic_line_help,"API 与使用费用说明",::showHelp)
        })
        val body=column()
        view.addView(ScrollView(this).apply { isFillViewport=true;addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        body.addView(label("先连接 AI 服务，再开启悬浮助手。",14f,true).apply { setPadding(0,0,0,dp(20)) })
        val connection=try { SettingsStore(this).connectionPreparation() } catch(_:Exception) { ConnectionPreparation(readable=false) }
        val connected=connection.readable && connection.addressEntered && connection.credentialSaved
        val model=connection.readable && connection.imageModelEntered
        fun row(number: String,title: String,summary: String,next: Boolean,action: () -> Unit) {
            val item=LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(88);setPadding(dp(16),dp(16),dp(16),dp(16))
                background=if(next)card(TONAL_SURFACE) else touchFeedback();isClickable=true;isFocusable=true
                accessible("$title，$summary${if(next)"，下一步" else ""}");setOnClickListener { action() }
            }
            item.addView(label(number,20f).apply { setTextColor(ACCENT);gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(32),dp(48)).apply { marginEnd=dp(12) })
            item.addView(LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                addView(label(title,17f))
                addView(label(if(next)"下一步 · $summary" else summary,14f,true).apply { setPadding(0,dp(6),0,0) })
            },LinearLayout.LayoutParams(0,-2,1f))
            item.addView(ImageView(this).apply {
                setImageDrawable(prototypeIcon(R.drawable.ic_line_next));imageTintList=ColorStateList.valueOf(MUTED)
                importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginStart=dp(8) })
            body.addView(item,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        row("1","服务地址与密钥",if(!connection.readable)"配置需修复" else if(connected)"已填写，未验证" else "填写服务提供方给你的信息",!connected) { onSettings("API") }
        row("2","图文模型",if(model)"已填写，请确认支持看图" else "选择支持图片输入的模型",connected && !model) { onSettings("MODELS") }
        val running=ProbeState.serviceRunning
        row("3",if(running)"关闭悬浮助手" else "开启悬浮助手",if(running)"正在运行" else if(Settings.canDrawOverlays(this))"权限已允许，点此开启" else "点此开启并按需授权",connected && model && !running,onToggleAssistant)
        body.addView(label("保存配置不会发起请求。真实提问可能产生 API 费用。",12f,true).apply { setPadding(dp(4),dp(16),dp(4),0) })
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(activity).setTitle("连接 AI 服务")
            .setMessage("从服务提供方获取服务地址、API 密钥和支持图片的模型名称。密钥是调用凭证，请只连接你信任的服务。\n\n服务需要兼容 Responses 接口和图片输入。保存配置不会测试；主动读取模型列表会联网，真实提问可能按服务规则收费。\n\n悬浮与截图权限会在实际需要时申请。新手引导始终使用本地示例，不消耗 Token。")
            .setPositiveButton("知道了",null).show()
    }
}
