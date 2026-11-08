package com.alwayside.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Mirrors ChatScreen's view layout; playback icon and subtitle dialog are staged unchanged. */
internal class DocReplayScenes(
    private val activity: Activity,
    private val root: FrameLayout,
    fixture: JSONObject,
    private val autoSubtitles: Boolean,
    initialPosition: Long,
    private val registerTarget: (String, View) -> Unit,
) {
    private val audio = fixture.getJSONObject("audio")
    private val owner = "doc-saved-meeting"
    private val recording = StoredConversation(owner, audio.getString("title"))
    private val source = File(activity.cacheDir, "doc-saved-meeting.wav")
    private val playback = ChatAudioPlayback.get(activity)
    private val playbackIcon = AudioPlaybackIcon(activity)
    private var playButton: MaterialButton? = null
    private var panel: AlertDialog? = null
    private var panelOwner: SubtitlePanel? = null
    private var overlay: DocSubtitleOverlay? = null
    private val observer: () -> Unit = {
        playbackIcon.update(playback.currentState)
        overlay?.refresh()
        playButton?.accessible(if (playback.currentState.playing) "暂停回听" else "继续回听")
    }

    init {
        val duration = audio.optInt("replayDurationMs", 78000)
        // A private, sparse silent fixture gives the real panel its normal local duration source.
        RandomAccessFile(source, "rw").use { it.setLength(44L + duration.toLong() * RecordingLimits.SAMPLE_RATE * 2 / 1000) }
        DocReplayFixtures.audio = source
        val rows = audio.getJSONArray("subtitleCues")
        val cues = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            SubtitleCue(index + 1, row.getLong("start"), row.getLong("end"), row.getString("english"))
        }
        val translations = (0 until rows.length()).associate { it + 1 to rows.getJSONObject(it).getString("chinese") }
        DocReplayFixtures.bundle = SubtitleBundle("a".repeat(64), SubtitleMethod.WHISPER, duration.toLong(),
            "b".repeat(64), cues, translations, transcriptionComplete = true)
        SubtitlePreferences(activity).saveDisplay(SubtitleDisplay.BILINGUAL)
        SubtitleReadingPreferences(activity).saveSegmentation(SubtitleSegmentation.ORIGINAL)
        playback.reset(duration)
        playback.seekTo(owner, source, initialPosition)
        createChat()
        if (autoSubtitles) overlay = DocSubtitleOverlay(activity, root, owner, playback, ::showSubtitles)
        playback.addObserver(observer)
    }

    private fun createChat() = with(activity) {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, 0) }
        val toolbar = materialToolbar(recording.title, {}).apply {
            overflowIcon = prototypeIcon(R.drawable.ic_line_more).apply { setTint(INK) }
            addIconAction(4, R.drawable.ic_line_star, "收藏会话") {}.isEnabled = false
            addOverflowIconAction(1, R.drawable.ic_line_copy, "复制整段会话") {}.isEnabled = false
            addOverflowIconAction(10, R.drawable.ic_line_edit, "重命名") {}.isEnabled = false
            addIconAction(3, R.drawable.ic_line_play, "回听原始音频", ::togglePlayback).apply {
                actionView = iconButton(R.drawable.ic_line_play, "回听原始音频", ::togglePlayback).also {
                    it.icon = playbackIcon
                    it.iconSize = dp(32)
                    playButton = it
                    registerTarget("replayPlay", it)
                }
            }
            addIconAction(8, R.drawable.ic_line_subtitles, "音频字幕", ::showSubtitles).apply {
                actionView = iconButton(R.drawable.ic_line_subtitles, "音频字幕", ::showSubtitles).also {
                    registerTarget("replaySubtitles", it)
                }
            }
            addOverflowIconAction(9, R.drawable.ic_line_subtitles, "完整字幕", ::showSubtitles)
        }
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        page.addView(label("已完成 · 预设内容", 11f, true).apply { setPadding(dp(22), dp(8), dp(22), 0) })
        page.addView(label("后续提问使用转写文字", 10f, true).apply { setPadding(dp(22), dp(4), dp(22), 0) })
        val body = column().apply {
            setPadding(dp(22), dp(16), dp(22), dp(16))
            addView(MaterialCardView(this@with).apply {
                setCardBackgroundColor(SURFACE); strokeWidth = 0; cardElevation = 0f
                radius = dp(12).toFloat(); isClickable = true; isFocusable = true
                accessible("展开完整音频转写")
                val heading = label("音频转写 · 点按展开", 11f, true)
                val content = label(audio.getString("original"), 12f).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }
                var expanded = false
                setOnClickListener {
                    expanded = !expanded
                    content.maxLines = if (expanded) Int.MAX_VALUE else 3
                    content.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
                    heading.text = "音频转写 · 点按${if (expanded) "收起" else "展开"}"
                }
                heading.setOnClickListener { performClick() }
                addView(column().apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12)); addView(heading); addView(content)
                })
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            })
            // ChatScreen.createMessageRow's completed-assistant typography and spacing.
            addView(column().apply {
                setPadding(0, 0, 0, 0)
                addView(label(audio.getString("bilingual"), 13f).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    enableTextActions()
                    setLineSpacing(dp(4).toFloat(), 1f)
                })
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) }
            })
        }
        page.addView(ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0)
            val inputLayout = TextInputLayout(this@with, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "继续追问…"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            inputLayout.addView(TextInputEditText(inputLayout.context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                minHeight = dp(56); minLines = 1; maxLines = 4
                accessible("问题输入框"); showSoftInputOnFocus = false; isCursorVisible = false
            }, LinearLayout.LayoutParams(-1, -2))
            addView(inputLayout, LinearLayout.LayoutParams(-1, -2))
        })
        page.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(10), dp(10))
            addView(View(this@with), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(R.drawable.ic_globe, "联网搜索：关闭") {}.apply {
                applySearchToggleStyle(false); isEnabled = false
            })
            addView(iconButton(android.R.drawable.ic_menu_send, "发送问题") {}.apply {
                applyIconActionStyle(primary = true); isEnabled = false
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
    }

    private fun togglePlayback() {
        if (playback.isActive(owner)) playback.toggle() else playback.play(owner, source)
    }

    private fun showSubtitles() {
        panel?.dismiss()
        overlay?.panelVisible(true)
        panelOwner = SubtitlePanel(activity, { recording }, { source })
        panel = panelOwner!!.show()
        panel!!.window?.decorView?.post { registerPanelTargets() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun panelView(predicate: (View) -> Boolean): View =
        checkNotNull(panel?.window?.decorView?.let { descendants(it).firstOrNull(predicate) }) { "Missing shared subtitle control" }

    private fun registerPanelTargets() {
        val decor = panel?.window?.decorView ?: return
        descendants(decor).forEach { view ->
            when {
                view is MaterialButton && view.text.toString() == "中文" -> registerTarget("subtitleChinese", view)
                view is MaterialButton && view.text.toString() == "双语" -> registerTarget("subtitleBilingual", view)
                view.contentDescription?.toString() in listOf("暂停回听", "继续回听", "回听原始音频") -> registerTarget("subtitlePlay", view)
                view is ListView -> seekChild(view)?.let { registerTarget("subtitleSeek", it) }
            }
        }
    }

    // Replaying the current 00:06 sentence from its start is the scene's explicit goal.
    private fun seekChild(list: ListView): View? = list.getChildAt(if (list.childCount > 1) 1 else 0)

    fun act(action: String): Boolean {
        when (action) {
            "replay-play", "replay-pause", "replay-resume" -> playButton!!.performClick()
            "replay-subtitles" -> showSubtitles()
            "replay-chinese" -> panelView { it is MaterialButton && it.text.toString() == "中文" }.performClick()
            "replay-bilingual" -> panelView { it is MaterialButton && it.text.toString() == "双语" }.performClick()
            "replay-seek" -> {
                val list = panelView { it is ListView } as ListView
                val child = checkNotNull(seekChild(list)) { "Subtitle list has no visible cue" }
                val index = list.firstVisiblePosition + list.indexOfChild(child)
                registerTarget("subtitleSeek", child)
                list.performItemClick(child, index, list.adapter.getItemId(index))
            }
            "replay-panel-pause" -> panelView {
                it.contentDescription?.toString() in listOf("暂停回听", "继续回听", "回听原始音频")
            }.performClick()
            "replay-close" -> { panel?.dismiss(); overlay?.panelVisible(false) }
            else -> return false
        }
        return true
    }

    fun tick(elapsed: Long) {
        playback.advance(elapsed)
        if (panel?.isShowing == true) registerPanelTargets()
    }

    /** View.draw omits other windows: draw the actual dialog decor at its real screen offset. */
    fun captureComposite(bitmap: Bitmap): Bitmap {
        val shown = panel?.takeIf { it.isShowing } ?: return bitmap
        val decor = shown.window?.decorView ?: return bitmap
        if (decor.width == 0 || decor.height == 0) return bitmap
        val rootPosition = IntArray(2).also(root::getLocationOnScreen)
        val dialogPosition = IntArray(2).also(decor::getLocationOnScreen)
        Canvas(bitmap).apply {
            drawColor(0x38000000)
            val checkpoint = save()
            translate((dialogPosition[0] - rootPosition[0]).toFloat(), (dialogPosition[1] - rootPosition[1]).toFloat())
            decor.draw(this)
            restoreToCount(checkpoint)
        }
        return bitmap
    }

    fun close() {
        panel?.dismiss(); panel = null; panelOwner = null
        overlay?.close(); overlay = null
        playback.removeObserver(observer)
        playback.reset(78000)
        DocReplayFixtures.reset()
        source.delete()
    }
}
