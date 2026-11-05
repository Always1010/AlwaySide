package com.alwayside.android

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView

internal class HelpScreen(activity: Activity, onBack: () -> Unit, onTutorial: () -> Unit, onPreparation: () -> Unit,practised: Boolean = false) {
    val view=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setBackgroundColor(BACKGROUND)
        addView(activity.materialToolbar("帮助与引导",onBack))
        val body=activity.column().apply {
            addView(activity.label(if(practised)"可重新查看引导，也可以开始准备连接。" else "建议先看新手引导，再准备实际使用。",14f,true))
            fun entry(title: String,primary: Boolean,iconRes: Int,run: () -> Unit) {
                action(title,primary,run).apply {
                    if(primary)applyOnboardingPrimaryStyle() else applySecondarySurfaceStyle()
                    icon=activity.prototypeIcon(iconRes)
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=activity.dp(16) }
                }
            }
            entry("新手引导",!practised,R.drawable.ic_line_hand,onTutorial)
            addView(activity.label("跟随提示了解截图、录音与追问；全程模拟。",14f,true).apply { setPadding(0,activity.dp(8),0,0) })
            entry("准备实际使用",practised,R.drawable.ic_line_settings,onPreparation)
            addView(activity.label("填写连接、选择模型，再开启悬浮助手。",14f,true).apply { setPadding(0,activity.dp(8),0,0) })
        }
        addView(ScrollView(activity).apply { addView(body);isFillViewport=true },LinearLayout.LayoutParams(-1,0,1f))
    }
}
