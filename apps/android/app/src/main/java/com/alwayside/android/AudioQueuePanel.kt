package com.alwayside.android

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.text.TextUtils
import com.google.android.material.bottomsheet.BottomSheetDialog

internal class AudioQueuePanel(private val activity: Activity) {
    private val playback = ChatAudioPlayback.get(activity)
    private val snapshot = playback.queue
    private val dialog = BottomSheetDialog(activity)
    private var renderedIndex = -1
    private var renderedPlaying: Boolean? = null
    private val adapter = object : BaseAdapter() {
        override fun getCount() = snapshot?.items?.size ?: 0
        override fun getItem(position: Int) = requireNotNull(snapshot).items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val item = getItem(position)
            val selected = snapshot?.index == position
            return LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = activity.dp(56)
                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                if (selected) background = card(TONAL_SURFACE, activity.dp(10).toFloat())
                addView(activity.label(if (selected) "▶" else "${position + 1}", 13f, !selected),
                    LinearLayout.LayoutParams(activity.dp(36), -2))
                addView(activity.label(item.title, 15f).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                contentDescription = "${item.title}，第 ${position + 1} 段" + if (selected) "，当前录音" else ""
            }
        }
    }
    private val changed: () -> Unit = {
        if (snapshot == null || playback.queue !== snapshot || playback.readingKey.isNotEmpty()) dialog.dismiss()
        else if (snapshot.index != renderedIndex || playback.currentState.playing != renderedPlaying) {
            renderedIndex = snapshot.index; renderedPlaying = playback.currentState.playing
            adapter.notifyDataSetChanged()
        }
    }

    fun show(): BottomSheetDialog {
        if (snapshot == null) return dialog
        val body = activity.column().apply {
            setBackgroundColor(BACKGROUND)
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(activity.label("播放队列 · ${snapshot.items.size} 段", 20f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(activity.iconButton(R.drawable.ic_line_close, "收起播放队列", { dialog.dismiss() }))
            })
            addView(activity.label(snapshot.source, 13f, true))
            addView(ListView(activity).apply {
                adapter = this@AudioQueuePanel.adapter; divider = null; dividerHeight = 0
                setOnItemClickListener { _, _, position, _ ->
                    if (playback.queue === snapshot) playback.selectQueueItem(position)
                }
                setSelection(snapshot.index)
            }, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * .45f).toInt()))
        }
        dialog.setContentView(body)
        dialog.setOnDismissListener { playback.removeObserver(changed) }
        dialog.show(); playback.addObserver(changed)
        return dialog
    }
}
