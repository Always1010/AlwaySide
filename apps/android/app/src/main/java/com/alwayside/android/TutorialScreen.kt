package com.alwayside.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Uses the service's presentation components with local scripted callbacks, never the service itself. */
internal class TutorialScreen(
    private val activity: Activity,
    private val flow: TutorialFlow,
    private val onBack: () -> Unit,
    private val onExit: () -> Unit,
    private val onChanged: (TutorialStep) -> Unit = {},
    private val onPrepare: () -> Unit,
) {
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(BACKGROUND) }
    init { render() }

    fun back(): Boolean {
        if(!flow.back())return false
        onChanged(flow.step);render();return true
    }
    private fun act(action: TutorialAction) {
        if(flow.act(action)){onChanged(flow.step);render()}
    }

    private fun render(): Unit = with(activity) {
        fun LinearLayout.guideAction(title: String,primary: Boolean=false,run: () -> Unit) =
            action(title,primary,run).apply { if(primary)applyOnboardingPrimaryStyle() }
        view.removeAllViews()
        view.addView(materialToolbar("新手引导",onBack).apply {
            subtitle="模拟 · 不联网 · 不消耗 Token"
            if(flow.step!=TutorialStep.LESSONS)addIconAction(700,R.drawable.ic_line_more,"查看其他引导") { act(TutorialAction.LESSONS) }
            addIconAction(701,R.drawable.ic_line_close,"退出引导",onExit)
        })
        if(flow.step==TutorialStep.LESSONS) {
            val lessons=column()
            view.addView(ScrollView(this).apply { addView(lessons);isFillViewport=true },LinearLayout.LayoutParams(-1,0,1f))
            lessons.addView(label("跟着提示，了解常用操作",20f))
            lessons.addView(label("已完成 ${flow.completedLessons.size}/4 项。以下只使用本地示例，不截图、不录音、不调用 API。",14f,true).apply { setPadding(0,dp(12),0,dp(8)) })
            fun lesson(id: TutorialLesson,title: String,description: String,action: TutorialAction) {
                lessons.guideAction(if(id in flow.completedLessons)"✓  $title" else title) { act(action) }.apply {
                    applySecondarySurfaceStyle();maxLines=2
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) }
                }
                lessons.addView(label(description,14f,true).apply { setPadding(dp(4),dp(6),dp(4),0) })
            }
            lesson(TutorialLesson.MENU,"单击：菜单截图与追问","跟着提示展开菜单，了解截图和继续提问。",TutorialAction.SELECT_MENU)
            lesson(TutorialLesson.SCREENSHOT,"长按：直接截图","按住小球，直接呼出截图问答。",TutorialAction.SELECT_SCREENSHOT)
            lesson(TutorialLesson.AUDIO,"双击：开始与结束录音","连续点两下开始，再点两下结束并处理。",TutorialAction.SELECT_AUDIO)
            lesson(TutorialLesson.CANCEL,"三击：取消录音","先双击开始，再连续点三下取消并丢弃。",TutorialAction.SELECT_CANCEL)
            lessons.addView(label("这是默认快捷动作。真实使用时，也可在设置中自定义手势。",12f,true).apply { setPadding(0,dp(20),0,0) })
            return@with
        }
        if(flow.step==TutorialStep.COMPLETE) {
            val done=column()
            view.addView(ScrollView(this).apply { addView(done) },LinearLayout.LayoutParams(-1,0,1f))
            val allDone=flow.completedLessons.size==TutorialLesson.entries.size
            done.addView(label(if(allDone)"新手引导已完成" else "本项引导已完成",20f))
            done.addView(label("已完成 ${flow.completedLessons.size}/4 项。刚才的画面和回答全部为本地示例，没有调用 AI 服务。",14f,true).apply { setPadding(0,dp(12),0,dp(12)) })
            done.guideAction("查看其他引导",!allDone) { act(TutorialAction.LESSONS) }.apply { if(allDone)applySecondarySurfaceStyle() }
            done.guideAction("准备实际使用",allDone,onPrepare).apply { if(!allDone)applySecondarySurfaceStyle() }
            return@with
        }
        val scene=FrameLayout(this).apply { setBackgroundColor(BACKGROUND);minimumHeight=dp(380) }
        val sceneScroll=ScrollView(this).apply { isFillViewport=true;addView(scene,FrameLayout.LayoutParams(-1,-2)) }
        view.addView(sceneScroll,LinearLayout.LayoutParams(-1,0,1f))
        val passage=column().apply {
            setPadding(dp(28),dp(40),dp(28),dp(24))
            val audio=flow.step in setOf(TutorialStep.AUDIO_START,TutorialStep.AUDIO_RECORDING,TutorialStep.AUDIO_RESULT,TutorialStep.CANCEL_START,TutorialStep.CANCEL_RECORDING,TutorialStep.CANCEL_RESULT)
            addView(label(if(audio)"音频示例" else "阅读示例",20f))
            addView(label(if(audio)"模拟应用内声音，不播放或采集音频" else "DAILY READING",12f,true).apply { setPadding(0,dp(24),0,dp(12)) })
            addView(label(if(audio)TutorialFlow.AUDIO_TRANSCRIPT else TutorialFlow.PASSAGE,20f))
            if(flow.recording)addView(label("模拟录音中",17f).apply { setTextColor(ACCENT);setPadding(0,dp(24),0,0) })
        }
        scene.addView(passage,FrameLayout.LayoutParams(-1,-2))
        val coach=column().apply { background=card(SURFACE);setPadding(dp(16),dp(12),dp(16),dp(12)) }
        val instruction=label(when(flow.step) {
            TutorialStep.BUBBLE -> "1/4 · 点屏幕边缘的小球，展开菜单。"
            TutorialStep.MENU -> "2/4 · 在菜单中点「截图问答」。"
            TutorialStep.ANSWER -> "3/4 · 回答显示在这个结果小窗里。"
            TutorialStep.COMPOSER -> "4/4 · 点小窗左下角的「继续提问」。"
            TutorialStep.QUESTION -> "4/4 · 点输入框，填入示例问题。"
            TutorialStep.SEND -> "4/4 · 点输入框下方的发送箭头。"
            TutorialStep.FOLLOWUP -> "追问完成。以后也在这里继续提问。"
            TutorialStep.LONG_PRESS -> "按住高亮的小球，直到出现截图结果。"
            TutorialStep.LONG_PRESS_RESULT -> "长按截图完成。这与菜单中的「截图问答」作用相同。"
            TutorialStep.AUDIO_START,TutorialStep.CANCEL_START -> "快速连续点两下高亮小球，开始模拟录音。"
            TutorialStep.AUDIO_RECORDING -> "模拟录音中，再快速点两下小球，结束并处理。"
            TutorialStep.AUDIO_RESULT -> "录音已结束。这里显示预设的转写与回答。"
            TutorialStep.CANCEL_RECORDING -> "快速连续点三下高亮小球，取消并丢弃录音。"
            TutorialStep.CANCEL_RESULT -> "录音已取消并丢弃，没有发送请求。"
            TutorialStep.COMPLETE,TutorialStep.LESSONS -> error("Handled above")
        },14f).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        coach.addView(instruction)
        val hint=label("",12f,true).apply { visibility=View.GONE }
        coach.addView(hint)
        fun explain(value: String) { hint.text=value;hint.visibility=View.VISIBLE }
        if(flow.step in setOf(TutorialStep.ANSWER,TutorialStep.FOLLOWUP,TutorialStep.LONG_PRESS_RESULT,TutorialStep.AUDIO_RESULT,TutorialStep.CANCEL_RESULT)) {
            coach.addView(LinearLayout(this).apply {
                if(flow.step==TutorialStep.ANSWER)guideAction("了解如何追问",true) { act(TutorialAction.PRACTISE) }
                guideAction("完成本项",flow.step!=TutorialStep.ANSWER) { act(TutorialAction.FINISH) }
            })
        }
        view.addView(coach,LinearLayout.LayoutParams(-1,-2))
        val glyph=AssistantBubbleView(this).apply { recording=flow.recording }
        val bubble=FrameLayout(this).apply {
            accessible(instruction.text.toString())
            background=card(BACKGROUND,dp(24).toFloat())
            addView(glyph,FrameLayout.LayoutParams(-1,-1))
        }
        TutorialBubbleTouch(bubble,{ count ->
            if(flow.tap(count)){onChanged(flow.step);render()}
            else explain(when(flow.step) {
                TutorialStep.LONG_PRESS -> "这一步需要按住小球，不是短点一下。"
                TutorialStep.CANCEL_RECORDING -> "需要快速连续点三下，取消并丢弃录音。"
                TutorialStep.AUDIO_START,TutorialStep.AUDIO_RECORDING,TutorialStep.CANCEL_START -> "请快速连续点两下小球；单击不会开始或结束录音。"
                else -> "请按当前高亮位置的提示操作。"
            })
        },{
            if(flow.longPress()){onChanged(flow.step);render()} else explain("这一步请按提示点按小球，不需要长按。")
        })
        if(flow.recording) {
            val animate=object: Runnable {
                override fun run() { if(glyph.isAttachedToWindow){glyph.invalidate();glyph.postDelayed(this,180)} }
            }
            glyph.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { glyph.post(animate) }
                override fun onViewDetachedFromWindow(v: View) { glyph.removeCallbacks(animate) }
            })
        }
        scene.addView(bubble,FrameLayout.LayoutParams(dp(48),dp(48)))
        var target: View?=null
        var overlay: View?=null
        var menu: ScrollView?=null
        var window: FrameLayout?=null
        if(flow.step in setOf(TutorialStep.BUBBLE,TutorialStep.LONG_PRESS,TutorialStep.AUDIO_START,TutorialStep.AUDIO_RECORDING,TutorialStep.CANCEL_START,TutorialStep.CANCEL_RECORDING))target=bubble
        else if(flow.step==TutorialStep.MENU) {
            val shared=AssistantMenuViews(this) { action ->
                if(action==AssistantMenuAction.SCREEN)act(TutorialAction.CAPTURE)
                else explain("本项先介绍「截图问答」，其他功能可在实际使用时操作。")
            }
            menu=ScrollView(this).apply { isVerticalScrollBarEnabled=false;addView(shared.view);background=card(SURFACE,dp(12).toFloat());clipToOutline=true }
            scene.addView(menu,FrameLayout.LayoutParams(dp(204),-2))
            target=shared.buttons[AssistantMenuAction.SCREEN]
            scene.setOnClickListener { act(TutorialAction.CLOSE_MENU) }
        } else if(flow.step==TutorialStep.CANCEL_RESULT) {
            scene.addView(label("已取消 · 未发送",20f).apply { gravity=Gravity.CENTER;setTextColor(ACCENT) },FrameLayout.LayoutParams(-1,dp(80),Gravity.BOTTOM).apply { bottomMargin=dp(24) })
        } else {
            val expanded=flow.step in setOf(TutorialStep.QUESTION,TutorialStep.SEND)
            val audioResult=flow.step==TutorialStep.AUDIO_RESULT
            val shared=AssistantWindowViews(this,if(audioResult)"听一段" else "截图问答",!audioResult,AssistantWindowActions(
                toggle={
                    if(flow.step==TutorialStep.ANSWER){flow.act(TutorialAction.PRACTISE);act(TutorialAction.EXPAND)}
                    else if(flow.step==TutorialStep.COMPOSER)act(TutorialAction.EXPAND) else explain("请先跟随当前提示操作。")
                },
                send={act(TutorialAction.SEND)},
                pin={explain("实际使用时，这里可以固定小窗。")},expand={explain("实际使用时，这里会进入应用内的完整对话。")},
                copy={explain("实际使用时，这里可以复制最近回答。")},
                imageChoice={explain("这里可选择沿用截图、更新画面或仅文字。本次保留示例截图。")},
                search={explain("本次引导不联网；真实使用时可在这里切换联网搜索。")},
            ))
            val source=label(if(audioResult)"转写原文 · 示例  ▾" else "截图示例  ▾",11f,true).apply { minHeight=dp(28);gravity=Gravity.CENTER_VERTICAL }
            val preview=label(if(audioResult)TutorialFlow.AUDIO_TRANSCRIPT else TutorialFlow.PASSAGE,14f).apply { visibility=View.GONE;setPadding(0,dp(8),0,dp(8)) }
            source.setOnClickListener { preview.visibility=if(preview.visibility==View.VISIBLE)View.GONE else View.VISIBLE }
            shared.sourceContent.addView(source);shared.sourceContent.addView(preview)
            fun message(user: Boolean,text: String) {
                val row=assistantMessageRow(user)
                (row.getChildAt(0) as TextView).apply { this.text=text;setTextIsSelectable(false);movementMethod=null }
                shared.response.addView(row)
            }
            message(false,if(audioResult)TutorialFlow.AUDIO_ANSWER else TutorialFlow.ANSWER)
            if(flow.step==TutorialStep.FOLLOWUP) {
                message(true,TutorialFlow.FOLLOWUP_QUESTION);message(false,TutorialFlow.FOLLOWUP_ANSWER)
                shared.scroll.post { shared.scroll.fullScroll(View.FOCUS_DOWN) }
            }
            shared.status.text="已完成 · 本地示例回答"
            shared.cancel.visibility=View.GONE;shared.retry.visibility=View.GONE
            shared.search.applySearchToggleStyle(false)
            shared.setComposer(expanded)
            shared.input.apply {
                accessible("截图追问，点此填入示例问题")
                isFocusable=false;isFocusableInTouchMode=false;showSoftInputOnFocus=false;isCursorVisible=false;isLongClickable=false
                keyListener=null;setTextIsSelectable(false)
                if(flow.step==TutorialStep.SEND)setText(TutorialFlow.FOLLOWUP_QUESTION)
                setOnClickListener { act(TutorialAction.FILL) }
            }
            shared.send.isEnabled=flow.step==TutorialStep.SEND
            shared.send.applyOnboardingPrimaryStyle(iconOnly=true)
            window=FrameLayout(this).apply { background=outlinedCard(BACKGROUND,15);clipToOutline=true;elevation=dp(8).toFloat();addView(shared.body,FrameLayout.LayoutParams(-1,-1)) }
            scene.addView(window,FrameLayout.LayoutParams(dp(320),dp(340)))
            target=when(flow.step) {
                TutorialStep.COMPOSER -> shared.toggle
                TutorialStep.QUESTION -> shared.inputLayout
                TutorialStep.SEND -> shared.send
                else -> null
            }
            installLocalDrag(scene,window,shared.header,false) { overlay?.invalidate() }
            installLocalDrag(scene,window,shared.resize,true) { overlay?.invalidate() }
        }
        target?.let { highlighted ->
            overlay=TutorialSpotlight(this,scene,highlighted)
            scene.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        }
        scene.addOnLayoutChangeListener { _,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if(right-left==oldRight-oldLeft && bottom-top==oldBottom-oldTop)return@addOnLayoutChangeListener
            val width=right-left;val height=bottom-top
            val ballY=(height/2-dp(24)).coerceAtLeast(dp(8))
            bubble.layoutParams=FrameLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=(width-dp(48)).coerceAtLeast(0);topMargin=ballY }
            menu?.layoutParams=FrameLayout.LayoutParams(minOf(dp(204),width-dp(64)),minOf(dp(357),height-dp(16))).apply {
                leftMargin=(width-this.width-dp(47)).coerceAtLeast(dp(8));topMargin=(ballY-dp(50)).coerceIn(dp(8),(height-this.height-dp(8)).coerceAtLeast(dp(8)))
            }
            window?.layoutParams=FrameLayout.LayoutParams(minOf(dp(320),width-dp(24)),minOf(dp(340),height-dp(24))).apply {
                leftMargin=(width-this.width)/2;topMargin=(height-this.height)/2
            }
            overlay?.invalidate()
            target?.post {
                if(target?.isAttachedToWindow==true)target?.requestRectangleOnScreen(Rect(0,0,target!!.width,target!!.height),true)
            }
        }
    }

    private fun installLocalDrag(scene: FrameLayout,window: FrameLayout,handle: View,resizing: Boolean,changed: () -> Unit) {
        var x=0f;var y=0f;var originalX=0;var originalY=0;var width=0;var height=0
        handle.setOnTouchListener { _,event ->
            val params=window.layoutParams as FrameLayout.LayoutParams
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x=event.rawX;y=event.rawY;originalX=params.leftMargin;originalY=params.topMargin;width=params.width;height=params.height;handle.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(event.rawX-x).toInt();val dy=(event.rawY-y).toInt()
                    if(resizing) { params.width=(width+dx).coerceIn(activity.dp(220).coerceAtMost(scene.width),scene.width);params.height=(height+dy).coerceIn(activity.dp(220).coerceAtMost(scene.height),scene.height) }
                    else { params.leftMargin=originalX+dx;params.topMargin=originalY+dy }
                    params.leftMargin=params.leftMargin.coerceIn(0,(scene.width-params.width).coerceAtLeast(0));params.topMargin=params.topMargin.coerceIn(0,(scene.height-params.height).coerceAtLeast(0))
                    window.layoutParams=params;changed()
                }
                MotionEvent.ACTION_UP -> {
                    handle.parent.requestDisallowInterceptTouchEvent(false)
                    if(kotlin.math.abs(event.rawX-x)<activity.dp(4) && kotlin.math.abs(event.rawY-y)<activity.dp(4))handle.performClick()
                }
                MotionEvent.ACTION_CANCEL -> handle.parent.requestDisallowInterceptTouchEvent(false)
            }
            true
        }
    }
}

@SuppressLint("ViewConstructor")
private class TutorialSpotlight(context: Context,private val scene: FrameLayout,private val target: View) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x38000000 }
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ACCENT;style=Paint.Style.STROKE;strokeWidth=context.dp(2).toFloat() }
    private val geometry=TutorialHighlightBounds()
    private val visibleBounds=Rect()
    private val targetLocation=IntArray(2)
    private val sceneLocation=IntArray(2)
    private val cutout=RectF()
    private val outline=RectF()
    private val radius=context.dp(12).toFloat()
    private val gap=context.dp(6).toFloat()
    private val arrow=context.dp(24).toFloat()
    private var ready=false
    private val positionListener=ViewTreeObserver.OnPreDrawListener {
        updateTarget();true
    }
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false
        elevation=context.dp(16).toFloat()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();viewTreeObserver.addOnPreDrawListener(positionListener)
    }
    override fun onDetachedFromWindow() {
        if(viewTreeObserver.isAlive)viewTreeObserver.removeOnPreDrawListener(positionListener)
        super.onDetachedFromWindow()
    }
    private fun updateTarget() {
        val wasReady=ready
        if(!target.isShown || !target.isAttachedToWindow || !target.getGlobalVisibleRect(visibleBounds))ready=false
        else {
            target.getLocationInWindow(targetLocation);scene.getLocationInWindow(sceneLocation)
            ready=geometry.update((targetLocation[0]-sceneLocation[0]).toFloat(),(targetLocation[1]-sceneLocation[1]).toFloat(),
                target.width.toFloat(),target.height.toFloat(),width.toFloat(),height.toFloat(),gap)
        }
        val changed=ready && (cutout.left!=geometry.left || cutout.top!=geometry.top || cutout.right!=geometry.right || cutout.bottom!=geometry.bottom)
        if(ready)cutout.set(geometry.left,geometry.top,geometry.right,geometry.bottom)
        if(wasReady!=ready || changed)invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(!ready)return
        // Draw only outside the target. No fill-rule hole can accidentally cover the active control.
        canvas.drawRect(0f,0f,width.toFloat(),cutout.top,paint)
        canvas.drawRect(0f,cutout.bottom,width.toFloat(),height.toFloat(),paint)
        canvas.drawRect(0f,cutout.top,cutout.left,cutout.bottom,paint)
        canvas.drawRect(cutout.right,cutout.top,width.toFloat(),cutout.bottom,paint)
        outline.set(cutout);outline.inset(ring.strokeWidth/2,ring.strokeWidth/2)
        canvas.drawRoundRect(outline,radius,radius,ring)
        if(cutout.left>arrow*1.5f) {
            val end=cutout.left-gap;val y=cutout.centerY()
            canvas.drawLine(end-arrow,y,end,y,ring)
            canvas.drawLine(end-gap,y-gap,end,y,ring);canvas.drawLine(end-gap,y+gap,end,y,ring)
        }
    }
}
