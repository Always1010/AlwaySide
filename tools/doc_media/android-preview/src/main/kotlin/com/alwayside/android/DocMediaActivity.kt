package com.alwayside.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var replay: DocReplayScenes? = null
    private var audioPlayer: View? = null
    private var playerElapsed = 0L
    private var frameClock: Long? = null
    private data class FrameAction(val at: Long, val run: () -> Unit)
    private val frameActions = mutableListOf<FrameAction>()
    private data class ScrollMotion(val from: Int, val to: Int, val start: Long, val duration: Long)
    private var scrollMotion: ScrollMotion? = null
    private var pendingScroll: (() -> Int)? = null
    private var pendingScrollDuration = 400L

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
        if (scene.optString("kind") == "replay") {
            replay = DocReplayScenes(this, root, fixture, scene.optBoolean("autoSubtitles"),
                scene.optLong("replayStartMs", 0)) { name, view -> targets[name] = view }
        } else createReading()
        // Resolve first-use component/font work before the teaching timeline.
        root.postDelayed({
            val events = scene.getJSONArray("events")
            val actions = (0 until events.length()).map { events.getJSONObject(it).getString("action") }
            when {
                "replay-subtitles" in actions -> replay?.act("replay-subtitles")
                "audio" in actions -> showWindow(true)
                "screenshot" in actions || "followup-result" in actions -> showWindow(false)
            }
            root.postDelayed({
                removePanel()
                replay?.act("replay-close")
                root.postDelayed({ startCapture() }, 150)
            }, 250)
        }, 900)
    }

    private fun createReading() {
        val audio = scene.optString("kind") == "audio"
        val content = fixture.getJSONObject(if (audio) "audio" else "reading")
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setPadding(0,dp(24),0,0) }
        page.addView(materialToolbar(content.optString("pageTitle", if (audio) "音频示例" else "阅读示例"), {}).apply {
            setTitleTextAppearance(this@DocMediaActivity,R.style.TextAppearance_DocMedia_ReadingTitle)
        })
        page.addView(View(this).apply {setBackgroundColor(BORDER)},LinearLayout.LayoutParams(-1,dp(1)))
        val article = column().apply { setPadding(dp(28),dp(36),dp(28),dp(24)) }
        article.addView(label(content.optString("eyebrow", if (audio) "LISTENING NOTE" else "DAILY READING"), 11f, true).apply { textSize=11f;letterSpacing=0f })
        article.addView(label(content.getString("title"), 24f).apply {
            textSize=29f;letterSpacing=0f;setPadding(0, dp(22), 0, dp(18))
            if (audio) typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        })
        if (audio) addAudioPlayer(article, content)
        else article.addView(label(content.getString("original").let {
                if (scene.optString("presentation") == "original-teaching") it.replace("\n\n", " ") else it
            }, 14f, true).apply {
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

    private fun addAudioPlayer(article: LinearLayout, content: JSONObject) {
        article.addView(label(content.getString("subtitle") + "\n" + content.getString("source") + " · 01:18", 17f, true).apply {
            textSize=17f;letterSpacing=0f;setLineSpacing(dp(4).toFloat(),1f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        })
        // The external-player backdrop follows the existing illustration, not a new App control.
        val player = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val pause = prototypeIcon(R.drawable.ic_line_pause)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val center = height / 2f
                pause.setTint(INK)
                pause.setBounds(dp(17), (center - dp(11)).toInt(), dp(39), (center + dp(11)).toInt())
                pause.draw(canvas)
                val left = dp(59).toFloat()
                val right = (width - dp(56)).toFloat()
                val seconds = (content.getInt("positionSeconds") + playerElapsed / 1000f)
                    .coerceAtMost(content.getInt("durationSeconds").toFloat())
                val progress = left + (right - left) * seconds / content.getInt("durationSeconds")
                paint.strokeWidth = dp(3).toFloat(); paint.strokeCap = Paint.Cap.ROUND
                paint.color = 0xFFC5CCD7.toInt(); canvas.drawLine(left, center, right, center, paint)
                paint.color = ACCENT; canvas.drawLine(left, center, progress, center, paint)
                canvas.drawCircle(progress, center, dp(4).toFloat(), paint)
                paint.color = MUTED; paint.textSize = 10f * resources.displayMetrics.scaledDensity
                paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                paint.textAlign = Paint.Align.RIGHT
                val duration = content.getInt("durationSeconds")
                canvas.drawText("%d:%02d".format(duration / 60, duration % 60),
                    width - dp(18).toFloat(), center - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }.apply {
            background = card(SURFACE, dp(14).toFloat())
            accessible("正在播放会议录音")
        }
        article.addView(player, LinearLayout.LayoutParams(-1,dp(58)).apply { topMargin=dp(10) })
        audioPlayer = player
        targets["audio-player"] = player
        updatePlayer(0)
    }

    private fun updatePlayer(elapsed: Long) {
        playerElapsed = elapsed
        audioPlayer?.invalidate()
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
        views.status.text = "已完成 · 预设内容"
        views.sourceContent.visibility = View.VISIBLE
        if (audio) {
            val sourceLabel=label("转写原文  ▾",11f,true).apply { textSize=11f;minHeight=dp(28);gravity=Gravity.CENTER_VERTICAL }
            views.sourceContent.addView(sourceLabel)
            sourceText=label(fixture.getJSONObject("audio").getString("original"), 12f, true).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(8), 0, dp(8))
            }.also(views.sourceContent::addView)
            sourceLabel.setOnClickListener {
                sourceText!!.visibility=if(sourceText!!.visibility==View.VISIBLE)View.GONE else View.VISIBLE
            }
            addMessage(false, fixture.getJSONObject("audio").getString("bilingual"))
        } else {
            val content = fixture.getJSONObject("reading")
            val sourceLabel = label("本次截图  ▾", 11f, true).apply {
                textSize = 11f; minHeight = dp(28); gravity = Gravity.CENTER_VERTICAL
                accessible("展开本次截图")
            }
            // Capture the native example page itself, as a real screenshot preview would.
            val pageBitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.getChildAt(0).draw(Canvas(pageBitmap))
            val preview = ImageView(this).apply {
                setImageBitmap(pageBitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE; accessible("本次截图预览")
            }
            views.sourceContent.addView(sourceLabel)
            views.sourceContent.addView(preview, LinearLayout.LayoutParams(-1, dp(110)))
            sourceLabel.setOnClickListener { preview.visibility = if (preview.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            addMessage(false, content.getString("bilingual"))
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
        fun messageText(view: View): TextView? = when (view) {
            is TextView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { messageText(view.getChildAt(it)) }
            else -> null
        }
        val message = checkNotNull(messageText(row)) { "Shared message row has no text view" }
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
        views.status.text = "正在回答 · 预设内容"
        scrollTo({ views.content.height }, 0)
    }

    private fun after(delay: Long, run: () -> Unit) {
        val clock = frameClock
        if (clock == null) handler.postDelayed({ run() }, delay)
        else frameActions += FrameAction(clock + delay, run)
    }

    private fun scrollTo(target: () -> Int, duration: Long = 400) {
        if (frameClock == null) conversation!!.scroll.post { conversation!!.scroll.smoothScrollTo(0, target()) }
        else {
            pendingScroll = target
            pendingScrollDuration = duration
        }
    }

    private fun applyFrameScroll(clock: Long) {
        val views = conversation ?: return
        pendingScroll?.let { target ->
            val maximum = (views.content.height - views.scroll.height).coerceAtLeast(0)
            scrollMotion = ScrollMotion(views.scroll.scrollY, target().coerceIn(0, maximum), clock, pendingScrollDuration)
            pendingScroll = null
        }
        scrollMotion?.let { motion ->
            val fraction = if (motion.duration == 0L) 1.0 else
                ((clock - motion.start).toDouble() / motion.duration).coerceIn(0.0, 1.0)
            val eased = (1 - kotlin.math.cos(Math.PI * fraction)) / 2
            views.scroll.scrollTo(0, (motion.from + (motion.to - motion.from) * eased).toInt())
            if (fraction >= 1) scrollMotion = null
        }
    }

    private fun advanceFrame() {
        val clock = checkNotNull(frameClock)
        while (true) {
            val action = frameActions.minByOrNull { it.at } ?: break
            if (action.at > clock) break
            frameActions.remove(action)
            action.run()
        }
        replay?.tick(clock)
        updatePlayer(clock)
        // Let the actual Android views complete layout before drawing this time step.
        root.post { captureFrame() }
    }

    private fun act(event: JSONObject) {
        caption = event.getString("caption")
        cue = event.optJSONObject("cue")
        if (replay?.act(event.getString("action")) == true) return
        when (event.getString("action")) {
            "hold" -> Unit
            "menu" -> showMenu()
            "screenshot" -> showWindow(false)
            "followup-result" -> {
                showWindow(false)
                toggleComposer()
                targets["question"] = addMessage(true, fixture.getJSONObject("reading").getString("question"))
                answerText = fixture.getJSONObject("reading").getString("answer")
                answer = addMessage(false, answerText)
                targets["answer"] = answer!!
                conversation!!.scroll.post { conversation!!.scroll.scrollTo(0, conversation!!.content.height) }
            }
            "compose" -> conversation!!.toggle.performClick()
            "type" -> conversation!!.input.setText(fixture.getJSONObject("reading").getString("question"))
            "send" -> conversation!!.send.performClick()
            "answer" -> {
                val length = answerText.length
                // Preset streaming is orchestration only; TextView and ScrollView render it.
                for (step in 1..8) after(step * 100L) {
                    answer!!.text = answerText.take(length * step / 8)
                    scrollTo({ conversation!!.content.height }, 0)
                    if (step == 8) conversation!!.status.text = "已完成 · 预设内容"
                }
            }
            "record" -> { bubble.recording = true; bubble.invalidate() }
            "audio" -> { bubble.recording = false; bubble.invalidate(); showWindow(true) }
            "cancel" -> { bubble.recording = false; bubble.invalidate() }
            "scroll-middle" -> scrollTo({ conversation!!.content.height / 3 })
            "scroll-bottom" -> scrollTo({ conversation!!.content.height })
            "scroll-top" -> scrollTo({ 0 })
            "scroll-page" -> scrollTo({ conversation!!.scroll.let { it.scrollY + (it.height * .78f).toInt() } })
            "scroll-audio-answer" -> scrollTo({ conversation!!.sourceContent.height }, event.optLong("duration", 1600))
            "scroll-source-bottom" -> scrollTo({ conversation!!.let {
                (it.sourceContent.height - it.scroll.height + dp(36)).coerceAtLeast(0)
            } })
            "translation-start" -> {
                sourceText!!.visibility=View.GONE
                scrollTo({ 0 })
            }
            "original" -> {
                sourceText!!.visibility=View.VISIBLE
                scrollTo({ 0 })
            }
            else -> error("Unknown action: ${event.getString("action")}")
        }
    }

    private fun startCapture() {
        origin = SystemClock.uptimeMillis()
        val events = scene.getJSONArray("events")
        if (!scene.optBoolean("still")) {
            frameClock = 0L
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                frameActions += FrameAction(event.getLong("at")) { act(event) }
            }
            advanceFrame()
            return
        }
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            handler.postDelayed({ act(event) }, event.getLong("at"))
        }
        handler.postDelayed({ captureFrame() }, 50)
    }

    private fun captureFrame() {
        // A just-added native view has no drawable bounds until its layout pass.
        if (root.isLayoutRequested || targets.values.any { it.isShown && (it.width == 0 || it.height == 0) }) {
            handler.postDelayed({ captureFrame() }, 16)
            return
        }
        if(pendingWrites.get()>=3) {
            handler.postDelayed({captureFrame()},16)
            return
        }
        val elapsed = frameClock ?: (SystemClock.uptimeMillis() - origin)
        if (frameClock == null) {
            replay?.tick(elapsed)
            updatePlayer(elapsed)
        } else applyFrameScroll(elapsed)
        if (::bubble.isInitialized) bubble.invalidate()
        val baseBitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(baseBitmap))
        val bitmap = replay?.captureComposite(baseBitmap) ?: baseBitmap
        if (bitmap !== baseBitmap) baseBitmap.recycle()
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
            .put("sendEnabled",conversation?.send?.isEnabled ?: false)
            .put("scrollY", conversation?.scroll?.scrollY ?: 0)
            .put("scrollMax", conversation?.let { (it.content.height - it.scroll.height).coerceAtLeast(0) } ?: 0))
        val more = if (frameClock == null) elapsed < scene.getLong("duration")
            else elapsed + frameIntervalMs < scene.getLong("duration")
        if (more) {
            if (frameClock == null) handler.postDelayed({ captureFrame() },
                (frameIntervalMs - (SystemClock.uptimeMillis() - origin - elapsed)).coerceAtLeast(1))
            else {
                frameClock = elapsed + frameIntervalMs
                handler.post { advanceFrame() }
            }
        } else {
            val manifest = JSONObject().put("schema", 1).put("scene", intent.getStringExtra("scene"))
                .put("width", root.width).put("height", root.height)
                .put("frames", frames).put("androidApi", android.os.Build.VERSION.SDK_INT)
                .put("density", resources.displayMetrics.density)
                .put("fontScale",resources.configuration.fontScale)
                .put("frameIntervalMs",frameIntervalMs)
                .put("captureMode", if (frameClock == null) "realtime-still" else "fixed-step")
                .put("captureWallTimeMs", SystemClock.uptimeMillis() - origin)
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
        replay?.close()
        frameWriter.shutdown()
        super.onDestroy()
    }
}
