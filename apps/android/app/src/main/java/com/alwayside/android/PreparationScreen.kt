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
        body.addView(label("先添加 API Key，再添加并选用图文问答卡片。",14f,true).apply { setPadding(0,0,0,dp(20)) })
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
        row("1","管理 API 服务",if(!connection.readable)"配置需修复" else if(resourcesReady)"已有 API 密钥与 API 地址，尚未验证" else "选择服务商，添加 Key 与名称",!resourcesReady) { onSettings("API") }
        row("2","图文问答",if(connected && model)"已选用卡片，尚未验证图片能力" else "选择服务商、已有 Key 和模型，首项自动选中，之后点击服务行切换",resourcesReady && !(connected && model)) { onSettings("ANSWER") }
        row("3","音频转写（可选）",if(connection.readable && connection.transcriptionConfigured)"已选用卡片，仍需按实际用途验证" else "可共用或另选服务与 Key，首项自动选中，之后点击服务行切换",false) { onSettings("TRANSCRIPTION") }
        val running=ProbeState.serviceRunning
        row("4",if(running)"关闭悬浮助手" else "权限与实际使用",if(running)"正在运行，可主动测试" else if(Settings.canDrawOverlays(this))"悬浮权限已允许，点此开启助手" else "点此开启助手并按需授权",connected && model && !running,onToggleAssistant)
        body.addView(label("首项自动选中，之后点击服务行切换。选用不代表验证成功。未设置转写不影响截图与文字；仅转写无需问答 Key。实际测试与提问可能产生 API 费用。",12f,true).apply { setPadding(dp(4),dp(16),dp(4),0) })
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(activity).setTitle("连接 AI 服务")
            .setMessage("先在「AI 服务 → 管理 API 服务」选择服务商，填写 Key 与名称；API 地址和接口信息随该 Key 一起管理。同一服务商可以保存多个 API 密钥，Key 只在此处编辑。请只连接你信任的服务。\n\n再在「AI 服务 → 图文问答」添加卡片，选择服务商、该服务商已有的 Key、支持图片的模型及默认参数。每个服务商在每个用途中只保留一张卡片；首项保存后自动选中，后续新增不切换；点击服务行选用，行尾图标编辑或删除。未知参数可留空使用服务默认。\n\n录音时可在「音频转写」独立添加并选用卡片。两种用途可共用服务与 Key，也可分开。仅转写不需要问答 Key；未设置转写不影响截图与文字。转写需要目标服务实际提供本客户端支持的文件转写接口。\n\n保存不代表验证成功；主动读取模型列表会联网，实际测试与提问可能收费。悬浮、截图和录音权限在需要时申请。新手引导始终使用本地示例，不消耗 Token。")
            .setPositiveButton("知道了",null).show()
    }
}
