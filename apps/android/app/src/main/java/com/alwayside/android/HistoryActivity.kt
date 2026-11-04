package com.alwayside.android

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** A native archive screen. Opening a record never starts an overlay or capture. */
class HistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    override fun onResume() { super.onResume(); showList() }

    private fun showList() {
        val body = column()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("会话记录", 28f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(appIcon(android.R.drawable.ic_menu_revert, "返回") { finish() })
        })
        body.addView(label("点击会话即可查看与追问。记录仅保存在本机。", 14f, true))
        body.addConversationList(this) { showList() }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); fitsSystemWindows = true; isFillViewport = true
            addView(body)
        })
    }
}
