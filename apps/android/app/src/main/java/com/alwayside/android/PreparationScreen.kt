package com.alwayside.android

import android.app.Activity
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Prepare each purpose explicitly; saved drafts never count as an active answer configuration. */
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
        body.addView(label("先添加服务与密钥，再建立并启用图文问答配置。",14f,true).apply { setPadding(0,0,0,dp(20)) })
        val connection=try { SettingsStore(this).connectionPreparation() } catch(_:Exception) { ConnectionPreparation(readable=false) }
        val connected=connection.readable && connection.addressEntered && connection.credentialSaved
        val model=connection.readable && connection.imageModelEntered
        val resourcesReady=connection.readable && connection.serviceResourcesReady
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
        row("1","服务商与密钥",if(!connection.readable)"配置需修复" else if(resourcesReady)"已有服务接入点与命名密钥，尚未验证" else "添加服务接入点与命名密钥",!resourcesReady) { onSettings("API") }
        row("2","图文问答配置",if(connected && model)"当前配置已填写，尚未验证图片能力" else "选择接入点、密钥、模型，保存后显式启用",resourcesReady && !(connected && model)) { onSettings("MODELS") }
        row("3","音频转写配置（可选）",if(connection.readable && connection.transcriptionConfigured)"已启用转写配置，仍需按实际用途验证" else "尚未启用；可共用或另选服务与密钥",false) { onSettings("MODELS") }
        val running=ProbeState.serviceRunning
        row("4",if(running)"关闭悬浮助手" else "权限与实际使用",if(running)"正在运行，可主动测试" else if(Settings.canDrawOverlays(this))"悬浮权限已允许，点此开启助手" else "点此开启助手并按需授权",connected && model && !running,onToggleAssistant)
        body.addView(label("新增和保存不会自动启用或验证。未设置转写不影响截图与文字；仅转写无需问答密钥。实际测试与提问可能产生 API 费用。",12f,true).apply { setPadding(dp(4),dp(16),dp(4),0) })
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(activity).setTitle("连接 AI 服务")
            .setMessage("先在「服务商与密钥」添加服务接入点和命名密钥；同一服务可以保存多个密钥。密钥是调用凭证，请只连接你信任的服务。\n\n再在「模型与服务 → 图文问答」建立配置，选择接入点、密钥、支持图片的模型及用途参数。新增、查看和保存不会自动启用，请显式设为当前图文问答配置。\n\n录音时可在「音频转写」独立建立并启用配置。两种用途可共用服务与密钥，也可分开。仅转写不需要问答密钥；未设置转写不影响截图与文字。转写需要目标服务实际提供本客户端支持的文件转写接口。\n\n保存不代表验证成功；主动读取模型列表会联网，实际测试与提问可能收费。悬浮、截图和录音权限在需要时申请。新手引导始终使用本地示例，不消耗 Token。")
            .setPositiveButton("知道了",null).show()
    }
}
