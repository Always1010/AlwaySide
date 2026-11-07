package com.alwayside.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    private var sourceText: TextView? = null
    private var menuBackdrop: View? = null
    private val frameWriter = Executors.newFixedThreadPool(2)
    private val pendingWrites = AtomicInteger()
    @Volatile private var captureError: String? = null
    private val frameIntervalMs = 50L
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
        val metrics = resources.displayMetrics
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(root,FrameLayout.LayoutParams(minOf(dp(400),metrics.widthPixels),
                minOf(dp(800),metrics.heightPixels),Gravity.CENTER))
        })
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
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setPadding(0,dp(24),0,0) }
        page.addView(materialToolbar(if (audio) "音频示例" else "阅读示例", {}).apply {
            setTitleTextAppearance(this@DocMediaActivity,R.style.TextAppearance_DocMedia_ReadingTitle)
        })
        page.addView(View(this).apply {setBackgroundColor(BORDER)},LinearLayout.LayoutParams(-1,dp(1)))
        val article = column().apply { setPadding(dp(28),dp(36),dp(28),dp(24)) }
        article.addView(label(if (audio) "LISTEN & TRANSLATE" else "DAILY READING", 11f, true).apply { textSize=11f;letterSpacing=0f })
        article.addView(label(if (audio) "A longer audio clip" else "Weekend plans", 24f).apply {
            textSize=29f;letterSpacing=0f;setPadding(0, dp(22), 0, dp(18))
        })
        article.addView(label(if (audio) "双击悬浮球，演示录音结束后查看原文和译文。\n\n预设音频内容，不采集设备声音。"
            else fixture.getJSONObject("reading").getString("original"), 14f, true).apply {
            textSize=15f;letterSpacing=0f;setLineSpacing(dp(4).toFloat(), 1f)
        })
        page.addView(ScrollView(this).apply { addView(article) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        bubble = AssistantBubbleView(this)
        root.addView(bubble, FrameLayout.LayoutParams(dp(40), dp(48), Gravity.END or Gravity.TOP).apply {
            marginEnd = dp(2);topMargin=dp(331)
        })
        targets["bubble"] = bubble
    }

    private fun showMenu() {
        removePanel()
        val menu = AssistantMenuViews(this, onAction = {})
        menuBackdrop = View(this).apply { setBackgroundColor(0x52000000) }.also {
            root.addView(it,root.indexOfChild(bubble),FrameLayout.LayoutParams(-1,-1))
        }
        val scroll = ScrollView(this).apply { addView(menu.view); isFillViewport = false }
        panel = FrameLayout(this).apply {
            background = card(SURFACE,dp(24).toFloat())
            addView(scroll)
        }
        val width=minOf(dp(menu.preferredWidthDp),root.width-dp(64))
        menu.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val height=minOf(menu.view.measuredHeight,root.height-dp(16))
        root.addView(panel, FrameLayout.LayoutParams(width,height,Gravity.END or Gravity.TOP).apply {
            marginEnd = dp(47);topMargin=(bubble.top-dp(50)).coerceIn(dp(8),(root.height-height-dp(8)).coerceAtLeast(dp(8)))
        })
        targets["menu"] = menu.view
    }

    private fun removePanel() {
        panel?.let(root::removeView)
        menuBackdrop?.let(root::removeView)
        menuBackdrop=null;sourceText=null
        panel = null
        conversation = null
        listOf("menu", "window", "toggle", "send", "scroll", "question", "answer").forEach(targets::remove)
    }

    private fun showWindow(audio: Boolean) {
        removePanel()
        val views = AssistantWindowViews(this, if (audio) "听一段" else "截图问答", !audio,
            AssistantWindowActions(toggle = { toggleComposer() }, send = { sendFollowup() }))
        conversation = views
        views.cancel.visibility = View.GONE
        views.retry.visibility = View.GONE
        views.status.text = "已完成 · 预设演示内容"
        views.sourceContent.visibility = if (audio) View.VISIBLE else View.GONE
        if (audio) {
            val sourceLabel=label("转写原文 · 示例  ▾",11f,true).apply { textSize=11f;minHeight=dp(28);gravity=Gravity.CENTER_VERTICAL }
            views.sourceContent.addView(sourceLabel)
            sourceText=label(fixture.getJSONObject("audio").getString("original"), 12f, true).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(8), 0, dp(8))
            }.also(views.sourceContent::addView)
            sourceLabel.setOnClickListener {
                sourceText!!.visibility=if(sourceText!!.visibility==View.VISIBLE)View.GONE else View.VISIBLE
            }
            addMessage(false, "中文翻译\n\n" + fixture.getJSONObject("audio").getString("translation"))
        } else {
            addMessage(false, fixture.getJSONObject("reading").getString("translation"))
        }
        composerExpanded = false
        views.input.showSoftInputOnFocus = false
        views.input.isCursorVisible = false
        views.input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                views.send.isEnabled=!s.isNullOrBlank()
            }
        })
        panel = FrameLayout(this).apply {
            background = outlinedCard(BACKGROUND, 15)
            clipToOutline = true
            elevation = dp(3).toFloat()
            addView(views.body, FrameLayout.LayoutParams(-1, -1))
        }
        // Placement belongs to the documentation stage, not to the shared components.
        val height = minOf(dp(assistantWindowHeightDp()), root.height-dp(72))
        root.addView(panel, FrameLayout.LayoutParams(minOf(dp(320),root.width-dp(24)),height,Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin=minOf(dp(328),root.height-height-dp(40)).coerceAtLeast(dp(8))
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
    }

    private fun sendFollowup() {
        val views = conversation!!
        val question = views.input.text.toString()
        require(question == fixture.getJSONObject("reading").getString("question"))
        targets["question"]=addMessage(true, question)
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
            "translation-start" -> {
                sourceText!!.visibility=View.GONE
                conversation!!.scroll.post { conversation!!.scroll.smoothScrollTo(0,0) }
            }
            "original" -> {
                sourceText!!.visibility=View.VISIBLE
                conversation!!.scroll.post { conversation!!.scroll.smoothScrollTo(0,0) }
            }
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
        if(pendingWrites.get()>=3) {
            handler.postDelayed({captureFrame()},16)
            return
        }
        val elapsed = SystemClock.uptimeMillis() - origin
        bubble.invalidate()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val name = "frame-%04d.png".format(frames.length())
        pendingWrites.incrementAndGet()
        frameWriter.execute {
            try {
                File(directory,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } catch(error:Exception) { captureError=error.toString() }
            finally { bitmap.recycle();pendingWrites.decrementAndGet() }
        }
        val rectangles = JSONObject()
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        targets.forEach { (name, view) ->
            val location = IntArray(2).also(view::getLocationOnScreen)
            rectangles.put(name, JSONArray(listOf(location[0] - rootLocation[0], location[1] - rootLocation[1],
                location[0] - rootLocation[0] + view.width, location[1] - rootLocation[1] + view.height)))
        }
        frames.put(JSONObject().put("file", name).put("at", elapsed).put("caption", caption)
            .put("targets", rectangles).put("cue", cue ?: JSONObject.NULL)
            .put("sendEnabled",conversation?.send?.isEnabled ?: false))
        if (elapsed < scene.getLong("duration")) {
            handler.postDelayed({ captureFrame() }, (frameIntervalMs - (SystemClock.uptimeMillis() - origin - elapsed)).coerceAtLeast(1))
        } else {
            val manifest = JSONObject().put("schema", 1).put("scene", intent.getStringExtra("scene"))
                .put("width", root.width).put("height", root.height)
                .put("frames", frames).put("androidApi", android.os.Build.VERSION.SDK_INT)
                .put("density", resources.displayMetrics.density)
                .put("fontScale",resources.configuration.fontScale)
                .put("frameIntervalMs",frameIntervalMs)
                .put("nativeViews", true)
                .put("finalAnswer", answer?.text?.toString() ?: "")
                .put("finalAnswerVisible", answer?.let {
                    val visible = android.graphics.Rect()
                    it.getGlobalVisibleRect(visible) && visible.height() >= it.height && visible.width() >= it.width
                } ?: false)
            finishCapture(manifest)
        }
    }

    private fun finishCapture(manifest:JSONObject) {
        if(pendingWrites.get()>0) { handler.postDelayed({finishCapture(manifest)},20);return }
        captureError?.let { manifest.put("error",it) }
        File(directory,"manifest.tmp").writeText(manifest.toString(2))
        check(File(directory,"manifest.tmp").renameTo(File(directory,"manifest.json")))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        frameWriter.shutdown()
        super.onDestroy()
    }
}
