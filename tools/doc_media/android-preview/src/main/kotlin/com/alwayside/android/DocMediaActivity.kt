package com.alwayside.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Standalone fixture host. No service, provider, permissions or production state. */
class DocMediaActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var bubble: AssistantBubbleView
    private lateinit var fixture: JSONObject
    private lateinit var scene: JSONObject
    private lateinit var directory: File
    private var panel: FrameLayout? = null
    private var conversation: AssistantWindowViews? = null
    private var composerExpanded = false
    private var answer: TextView? = null
    private var answerText = ""
    private var caption = ""
    private var cue: JSONObject? = null
    private val targets = linkedMapOf<String, View>()
    private val frames = JSONArray()
    private var origin = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = JSONObject(assets.open("showcase.json").bufferedReader().use { it.readText() })
        val sceneId = intent.getStringExtra("scene") ?: "S06-floating-menu"
        scene = fixture.getJSONObject("scenes").getJSONObject(sceneId)
        val run = intent.getStringExtra("run") ?: "manual"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid capture run" }
        directory = File(filesDir, "captures/$run").apply {
            require(!exists()) { "Use a new run identifier" }
            mkdirs()
        }
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        setContentView(root)
        root.post {
            root.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        createReading()
        root.postDelayed({ startCapture() }, 900)
    }

    private fun createReading() {
        val audio = scene.optString("kind") == "audio"
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(materialToolbar(if (audio) "音频示例" else "阅读示例", {}))
        val article = column()
        article.addView(label(if (audio) "LISTEN & TRANSLATE" else "DAILY READING", 11f, true))
        article.addView(label(if (audio) "A longer audio clip" else "Weekend plans", 24f).apply {
            setPadding(0, dp(20), 0, dp(18))
        })
        article.addView(label(if (audio) "双击悬浮球，演示录音结束后查看原文和译文。\n\n预设音频内容，不采集设备声音。"
            else fixture.getJSONObject("reading").getString("original"), 14f, true).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        page.addView(ScrollView(this).apply { addView(article) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        bubble = AssistantBubbleView(this)
        root.addView(bubble, FrameLayout.LayoutParams(dp(40), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(6)
        })
        targets["bubble"] = bubble
    }

    private fun showMenu() {
        removePanel()
        val menu = AssistantMenuViews(this, onAction = {})
        val scroll = ScrollView(this).apply { addView(menu.view); isFillViewport = false }
        panel = FrameLayout(this).apply {
            background = outlinedCard(BACKGROUND, 12)
            elevation = dp(5).toFloat()
            addView(scroll)
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(menu.preferredWidthDp), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(54)
        })
        targets["menu"] = menu.view
    }

    private fun removePanel() {
        panel?.let(root::removeView)
        panel = null
        conversation = null
        listOf("menu", "window", "toggle", "send", "scroll").forEach(targets::remove)
    }

    private fun showWindow(audio: Boolean) {
        removePanel()
        val views = AssistantWindowViews(this, if (audio) "音频问答" else "截图问答", !audio,
            AssistantWindowActions(toggle = { toggleComposer() }, send = { sendFollowup() }))
        conversation = views
        views.cancel.visibility = View.GONE
        views.retry.visibility = View.GONE
        views.status.text = "已完成 · 预设演示内容"
        views.sourceContent.visibility = if (audio) View.VISIBLE else View.GONE
        if (audio) {
            views.sourceContent.addView(label("转录原文", 12f, true))
            views.sourceContent.addView(label(fixture.getJSONObject("audio").getString("original"), 12f, true).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(8), 0, dp(8))
            })
            addMessage(false, "中文翻译\n\n" + fixture.getJSONObject("audio").getString("translation"))
        } else {
            addMessage(false, fixture.getJSONObject("reading").getString("translation"))
        }
        composerExpanded = false
        views.input.showSoftInputOnFocus = false
        views.input.isCursorVisible = false
        panel = FrameLayout(this).apply {
            background = outlinedCard(BACKGROUND, 16)
            clipToOutline = true
            elevation = dp(3).toFloat()
            addView(views.body, FrameLayout.LayoutParams(-1, -1))
        }
        // Placement belongs to the documentation stage, not to the shared components.
        val height = minOf(dp(if (audio) 590 else 410), root.height - dp(240))
        root.addView(panel, FrameLayout.LayoutParams(root.width - dp(74), height, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(48); bottomMargin = dp(22)
        })
        targets.putAll(mapOf("window" to views.body, "toggle" to views.toggle,
            "send" to views.send, "scroll" to views.scroll))
    }

    private fun addMessage(user: Boolean, text: String): TextView {
        val row = assistantMessageRow(user)
        val message = row.getChildAt(0) as TextView
        message.text = text
        conversation!!.response.addView(row)
        return message
    }

    private fun toggleComposer() {
        composerExpanded = !composerExpanded
        conversation!!.setComposer(composerExpanded)
        // Retain enough native scrolling area while the input section is open.
        panel!!.layoutParams = (panel!!.layoutParams as FrameLayout.LayoutParams).apply {
            height = minOf(dp(520), root.height - dp(260))
        }
    }

    private fun sendFollowup() {
        val views = conversation!!
        val question = views.input.text.toString()
        require(question == fixture.getJSONObject("reading").getString("question"))
        addMessage(true, question)
        views.input.setText("")
        views.input.clearFocus()
        answer = addMessage(false, "")
        targets["answer"] = answer!!
        answerText = fixture.getJSONObject("reading").getString("answer")
        views.status.text = "正在回答 · 预设演示内容"
        views.scroll.post { views.scroll.smoothScrollTo(0, views.content.height) }
    }

    private fun act(event: JSONObject) {
        caption = event.getString("caption")
        cue = event.optJSONObject("cue")
        when (event.getString("action")) {
            "hold" -> Unit
            "menu" -> showMenu()
            "screenshot" -> showWindow(false)
            "compose" -> conversation!!.toggle.performClick()
            "type" -> conversation!!.input.setText(fixture.getJSONObject("reading").getString("question"))
            "send" -> conversation!!.send.performClick()
            "answer" -> {
                val length = answerText.length
                // Preset streaming is orchestration only; TextView and ScrollView render it.
                for (step in 1..8) handler.postDelayed({
                    answer!!.text = answerText.take(length * step / 8)
                    conversation!!.scroll.post { conversation!!.scroll.smoothScrollTo(0, conversation!!.content.height) }
                    if (step == 8) conversation!!.status.text = "已完成 · 预设演示内容"
                }, step * 100L)
            }
            "record" -> { bubble.recording = true; bubble.invalidate() }
            "audio" -> { bubble.recording = false; bubble.invalidate(); showWindow(true) }
            "cancel" -> { bubble.recording = false; bubble.invalidate() }
            "scroll-middle" -> conversation!!.scroll.smoothScrollTo(0, conversation!!.content.height / 3)
            "scroll-bottom" -> conversation!!.scroll.smoothScrollTo(0, conversation!!.content.height)
            "scroll-top" -> conversation!!.scroll.smoothScrollTo(0, 0)
            else -> error("Unknown action: ${event.getString("action")}")
        }
    }

    private fun startCapture() {
        origin = SystemClock.uptimeMillis()
        val events = scene.getJSONArray("events")
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            handler.postDelayed({ act(event) }, event.getLong("at"))
        }
        handler.postDelayed({ captureFrame() }, 50)
    }

    private fun captureFrame() {
        val elapsed = SystemClock.uptimeMillis() - origin
        bubble.invalidate()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val name = "frame-%04d.png".format(frames.length())
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val rectangles = JSONObject()
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        targets.forEach { (name, view) ->
            val location = IntArray(2).also(view::getLocationOnScreen)
            rectangles.put(name, JSONArray(listOf(location[0] - rootLocation[0], location[1] - rootLocation[1],
                location[0] - rootLocation[0] + view.width, location[1] - rootLocation[1] + view.height)))
        }
        frames.put(JSONObject().put("file", name).put("at", elapsed).put("caption", caption)
            .put("targets", rectangles).put("cue", cue ?: JSONObject.NULL))
        if (elapsed < scene.getLong("duration")) {
            handler.postDelayed({ captureFrame() }, (100 - (SystemClock.uptimeMillis() - origin - elapsed)).coerceAtLeast(1))
        } else {
            val manifest = JSONObject().put("schema", 1).put("scene", intent.getStringExtra("scene"))
                .put("width", root.width).put("height", root.height)
                .put("frames", frames).put("androidApi", android.os.Build.VERSION.SDK_INT)
                .put("density", resources.displayMetrics.density)
                .put("nativeViews", true)
                .put("finalAnswer", answer?.text?.toString() ?: "")
                .put("finalAnswerVisible", answer?.let {
                    val visible = android.graphics.Rect()
                    it.getGlobalVisibleRect(visible) && visible.height() >= it.height && visible.width() >= it.width
                } ?: false)
            File(directory, "manifest.tmp").writeText(manifest.toString(2))
            check(File(directory, "manifest.tmp").renameTo(File(directory, "manifest.json")))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
