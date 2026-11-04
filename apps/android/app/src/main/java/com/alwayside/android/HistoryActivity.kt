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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND); fitsSystemWindows = true
            addView(appHeader(back = { finish() }))
            addHistoryTabs(this@HistoryActivity)
            addView(ScrollView(this@HistoryActivity).apply {
                isFillViewport = true
                addView(column().apply {
                    addConversationList(this@HistoryActivity) { showList() }
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}
