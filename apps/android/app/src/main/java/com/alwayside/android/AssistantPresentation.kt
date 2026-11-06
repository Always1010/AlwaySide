package com.alwayside.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.abs
import kotlin.math.sin

/** Shared presentation only: no preferences, services, capture, providers or navigation. */
@SuppressLint("ViewConstructor")
internal class AssistantBubbleView(context: Context, private val size: Int = 36,
    private val opacity: Int = 55, private val color: Int = 0xFFCC5C74.toInt()) : View(context) {
    var recording = false
    var working = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkle = context.prototypeIcon(R.drawable.ic_line_sparkles).apply { setTint(Color.WHITE) }
    private val cancelIcon = context.prototypeIcon(R.drawable.ic_line_close).apply { setTint(Color.WHITE) }
    private var gesturePhase = BubbleGestureStateMachine.Phase.IDLE
    private var gestureProgress = 0f
    private var gestureDx = 0f
    private var gestureDy = 0f
    private var gestureSlide = false
    private var gestureCancelled = false
    private var phaseStartedAt = 0L

    fun setGestureFeedback(phase: BubbleGestureStateMachine.Phase, progress: Float,
        dx: Float = 0f, dy: Float = 0f, slideEnabled: Boolean = true, cancelled: Boolean = false) {
        if (phase != gesturePhase) phaseStartedAt = SystemClock.uptimeMillis()
        gesturePhase = phase; gestureProgress = progress.coerceIn(0f, 1f)
        gestureDx = dx; gestureDy = dy; gestureSlide = slideEnabled
        gestureCancelled = cancelled
        invalidate()
    }

    fun resetGestureFeedback() = setGestureFeedback(BubbleGestureStateMachine.Phase.IDLE, 0f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = context.dp(size) / 2f
        val x = width / 2f; val y = height / 2f
        val pressing = gesturePhase == BubbleGestureStateMachine.Phase.PRESSING
        val armed = gesturePhase == BubbleGestureStateMachine.Phase.ARMED
        val feedback = pressing || armed
        val animated = android.animation.ValueAnimator.areAnimatorsEnabled()
        val elapsed = (SystemClock.uptimeMillis() - phaseStartedAt).coerceAtLeast(0)
        val pressFraction = if (animated) (elapsed / 100f).coerceAtMost(1f) else 1f
        val scale = when {
            pressing -> 1f - .04f * pressFraction
            armed && animated -> {
                val t = (elapsed / 150f).coerceAtMost(1f)
                1f + .04f * sin(t * Math.PI).toFloat() - .04f * (1f-t)
            }
            else -> 1f
        }
        var scaleX = scale; var scaleY = scale
        if (armed) {
            val amount = (maxOf(abs(gestureDx), abs(gestureDy)) / context.dp(48)).coerceIn(0f, 1f) * .06f
            if (abs(gestureDx) > abs(gestureDy)) { scaleX += amount; scaleY -= amount / 2 }
            else { scaleY += amount; scaleX -= amount / 2 }
        }
        val save = canvas.save()
        canvas.scale(scaleX, scaleY, x, y)
        val normalAlpha = if(recording)245 else (opacity * 2.55f).toInt()
        paint.color = color
        paint.alpha = if (feedback) (normalAlpha + (255 - normalAlpha) * if (pressing) pressFraction else 1f).toInt() else normalAlpha
        paint.style = Paint.Style.FILL; canvas.drawCircle(x,y,radius,paint)
        paint.color = Color.WHITE; paint.alpha = if(recording || feedback)255 else (opacity * 2.55f).toInt()
        if (armed && gestureCancelled) {
            val r = radius * .48f
            cancelIcon.setBounds((x-r).toInt(),(y-r).toInt(),(x+r).toInt(),(y+r).toInt())
            cancelIcon.draw(canvas)
        } else if(recording) {
            val phase = SystemClock.uptimeMillis() / 180.0
            for(i in -1..1) {
                val h = radius * (.35 + .55 * abs(sin(phase+i))).toFloat()
                canvas.drawRoundRect(x+i*radius*.35f-context.dp(1),y-h/2,x+i*radius*.35f+context.dp(1),y+h/2,
                    context.dp(1).toFloat(),context.dp(1).toFloat(),paint)
            }
        } else {
            val r=radius*.57f
            sparkle.alpha=paint.alpha;sparkle.setBounds((x-r).toInt(),(y-r).toInt(),(x+r).toInt(),(y+r).toInt());sparkle.draw(canvas)
            if(working && !feedback) {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(1).toFloat()
                canvas.drawArc(x-radius-2,y-radius-2,x+radius+2,y+radius+2,(SystemClock.uptimeMillis()%1800)/5f,90f,false,paint)
                paint.style=Paint.Style.FILL
            }
        }
        canvas.restoreToCount(save)
        if (pressing && gestureSlide) {
            // Keep the progress ring inside the 48dp touch target, including the largest ball.
            val ringRadius = (radius + context.dp(3)).coerceAtMost(minOf(width, height) / 2f - context.dp(1))
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
            paint.color = if (ringRadius <= radius) Color.WHITE else color
            paint.alpha = if (armed) (60 + 130 * gestureProgress).toInt() else 60
            canvas.drawCircle(x, y, ringRadius, paint)
            paint.alpha = if (armed) (255 * gestureProgress).toInt() else 245
            canvas.drawArc(x-ringRadius,y-ringRadius,x+ringRadius,y+ringRadius,-90f,
                360f * if (animated) gestureProgress else 1f,false,paint)
            paint.style = Paint.Style.FILL
        }
    }
}

internal enum class AssistantMenuAction { SCREEN, AUDIO, CANCEL_AUDIO, CROP, LAST, PLAY, HISTORY, SETTINGS, STOP }

internal fun Context.assistantWindowHeightDp() = (432 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()

internal class AssistantMenuViews(context: Context, recording: Boolean = false, hasClip: Boolean = false,
    webSearch: Boolean = false, useDefaultQuestion: Boolean = true, webSearchAvailable: Boolean = true,
    onWebSearchChanged: (Boolean) -> Unit = {}, onDefaultQuestionChanged: (Boolean) -> Unit = {},
    onAction: (AssistantMenuAction) -> Unit) {
    val view = LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(context.dp(4),context.dp(4),context.dp(4),context.dp(4))
        background=card(SURFACE,context.dp(12).toFloat());elevation=0f;clipToOutline=true
    }
    val buttons = linkedMapOf<AssistantMenuAction,MaterialButton>()
    val preferredWidthDp = (188 * context.resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
    init { with(context) {
        fun item(action: AssistantMenuAction, resource: Int, title: String) {
            val row=MaterialButton(this,null,androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text=title;icon=prototypeIcon(resource);iconSize=dp(18);iconPadding=dp(8)
                gravity=Gravity.CENTER_VERTICAL or Gravity.START;isAllCaps=false
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                applyActionStyle(false);iconTint=ColorStateList.valueOf(MUTED);stateListAnimator=null;elevation=0f;translationZ=0f
                setTextColor(INK);cornerRadius=dp(8);insetTop=0;insetBottom=0
                minHeight=dp(40);minimumHeight=dp(40);minWidth=0;minimumWidth=0
                textSize=13f;typeface=Typeface.create("sans-serif",Typeface.NORMAL);letterSpacing=0f;maxLines=2
                setPadding(dp(8),dp(6),dp(8),dp(6));accessible(title)
                setOnClickListener { onAction(action) }
            }
            buttons[action]=row;view.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        item(AssistantMenuAction.SCREEN,R.drawable.ic_line_scan,"截图问答")
        item(AssistantMenuAction.AUDIO,R.drawable.ic_line_audio,if(recording)"结束录音并处理" else "听一段")
        if(recording)item(AssistantMenuAction.CANCEL_AUDIO,R.drawable.ic_line_close,"取消并丢弃录音")
        item(AssistantMenuAction.CROP,R.drawable.ic_line_crop,"框选问答")
        if(hasClip)item(AssistantMenuAction.PLAY,R.drawable.ic_line_play,"回听上一段")
        item(AssistantMenuAction.LAST,R.drawable.ic_line_text,"最近悬浮对话")
        view.addView(View(this).apply { setBackgroundColor(BORDER) },LinearLayout.LayoutParams(-1,dp(1)).apply {
            setMargins(dp(8),dp(4),dp(8),dp(4))
        })
        fun option(title:String,checked:Boolean,description:String,enabled:Boolean=true,changed:(Boolean)->Unit) {
            val check=MaterialCheckBox(this).apply {
                text=title;isChecked=checked;textSize=13f;setTextColor(INK);letterSpacing=0f
                minHeight=dp(38);minimumHeight=dp(38);minWidth=0;minimumWidth=0
                setPadding(dp(8),0,dp(8),0);compoundDrawablePadding=dp(4)
                buttonTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(ACCENT,MUTED))
                accessible(title);tooltipText=description;isEnabled=enabled||checked
                setOnCheckedChangeListener { _,selected -> isEnabled=enabled||selected;changed(selected) }
            }
            view.addView(check,LinearLayout.LayoutParams(-1,-2))
        }
        option("联网搜索",webSearch,if(webSearchAvailable)"允许新悬浮会话搜索网页；关闭后仍需联网调用模型" else "当前模型或协议不支持搜索，可关闭已有选择",webSearchAvailable,onWebSearchChanged)
        option("使用默认提问方式",useDefaultQuestion,"关闭后展开输入框，补充问题再发送",changed=onDefaultQuestionChanged)
        view.addView(View(this).apply { setBackgroundColor(BORDER) },LinearLayout.LayoutParams(-1,dp(1)).apply {
            setMargins(dp(8),dp(4),dp(8),dp(4))
        })
        val footer=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        fun footerAction(action:AssistantMenuAction,resource:Int,title:String) {
            val button=iconButton(resource,title) { onAction(action) }.apply {
                iconSize=dp(18);minWidth=0;minimumWidth=0;minHeight=dp(40);minimumHeight=dp(40)
                stateListAnimator=null;elevation=0f;translationZ=0f
            }
            buttons[action]=button;footer.addView(button,LinearLayout.LayoutParams(0,dp(40),1f))
        }
        footerAction(AssistantMenuAction.HISTORY,R.drawable.ic_line_history,"对话记录")
        footerAction(AssistantMenuAction.SETTINGS,R.drawable.ic_line_settings,"设置")
        footerAction(AssistantMenuAction.STOP,R.drawable.ic_line_power,"关闭悬浮球")
        view.addView(footer,LinearLayout.LayoutParams(-1,-2))
    } }
}

internal class AssistantWindowActions(
    val pin: () -> Unit = {}, val expand: () -> Unit = {}, val toggle: () -> Unit = {},
    val cancel: () -> Unit = {}, val retry: () -> Unit = {}, val copy: () -> Unit = {},
    val imageChoice: (View) -> Unit = {}, val search: () -> Unit = {}, val send: () -> Unit = {},
    val favorite: () -> Unit = {},
)

/** Exactly the same window hierarchy and controls for the service and the local tutorial. */
internal class AssistantWindowViews(context: Context, title: String, image: Boolean, actions: AssistantWindowActions) {
    val body = LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(context.dp(1),context.dp(1),context.dp(1),context.dp(1))
        // Draw the perimeter after the toolbar so its opaque fill cannot cover the rounded stroke.
        foreground=context.outlinedCard(Color.TRANSPARENT,15)
    }
    val header = context.materialToolbar(title,null,compact=true).apply {
        setTitleTextAppearance(context,R.style.TextAppearance_AlwaySide_AssistantTitle)
        minimumHeight=context.dp(44)
        overflowIcon=context.prototypeIcon(R.drawable.ic_line_more).apply { setTint(INK) }
    }
    private fun headerAction(id: Int, resource: Int, description: String, action: () -> Unit): MaterialButton {
        val context=header.context
        return context.iconButton(resource,description,action).apply {
            iconSize=context.dp(20)
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
            setPadding(context.dp(12),0,context.dp(12),0)
            cornerRadius=context.dp(22)
            setToggleCheckedStateOnClick(false)
            layoutParams=ViewGroup.LayoutParams(context.dp(44),context.dp(44))
        }.also { header.addIconAction(id,resource,description,action).actionView=it }
    }
    val favorite = headerAction(3,R.drawable.ic_line_star,"收藏会话",actions.favorite)
    val pin = headerAction(1,R.drawable.ic_pin,"固定窗口，点外部保持显示",actions.pin)
    val content = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(15),context.dp(13),context.dp(15),context.dp(8)) }
    val sourceContent = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(12),context.dp(5),context.dp(12),context.dp(5)) }
    val response = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(0,context.dp(12),0,0) }
    val status = context.label("",11f,true).apply { textSize=11f;letterSpacing=0f;setPadding(0,context.dp(6),0,0) }
    val scroll = ScrollView(context).apply { addView(content);isFillViewport=true;isVerticalScrollBarEnabled=true }
    // Empty in the tutorial; the service supplies request notices without coupling shared views to sessions.
    val noticeSlot = FrameLayout(context)
    private val controls = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(context.dp(8),0,context.dp(8),0) }
    val toggle = MaterialButton(context,null,androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        applyActionStyle();setTextAppearance(R.style.TextAppearance_AlwaySide_AssistantAction)
        iconSize=context.dp(14);iconPadding=context.dp(4);iconGravity=MaterialButton.ICON_GRAVITY_TEXT_END
        minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0
        insetLeft=0;insetRight=0;insetTop=0;insetBottom=0
        setPadding(context.dp(8),0,context.dp(8),0);cornerRadius=context.dp(8)
        maxLines=1;ellipsize=TextUtils.TruncateAt.END
        backgroundTintList=ColorStateList.valueOf(if(AppAppearance.isNight)TONAL_SURFACE else 0xFFEAF0FF.toInt())
        setOnClickListener { actions.toggle() }
    }
    val cancel: MaterialButton
    val retry: MaterialButton
    val resize: MaterialButton
    val copy: MenuItem
    val composerSection = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    val imageChoice: MaterialButton?
    val inputLayout = TextInputLayout(context,null,com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        isHintEnabled=false;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxStrokeWidth=context.dp(1);boxStrokeWidthFocused=context.dp(1)
        setBoxCornerRadii(context.dp(5).toFloat(),context.dp(5).toFloat(),context.dp(5).toFloat(),context.dp(5).toFloat())
        setBoxStrokeColorStateList(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(BORDER,ACCENT,
                if(AppAppearance.isNight)HINT else 0xFF929AA5.toInt())))
    }
    val input = TextInputEditText(inputLayout.context).apply {
        setTextAppearance(R.style.TextAppearance_AlwaySide_AssistantBody)
        textSize=13f;setTextColor(INK);setHintTextColor(HINT);hint="想了解什么？"
        minLines=1;maxLines=3;minHeight=context.dp(50)
        setPadding(context.dp(12),context.dp(10),context.dp(12),context.dp(10))
        filters=arrayOf(android.text.InputFilter.LengthFilter(12000))
    }
    val search = context.iconButton(R.drawable.ic_globe,"联网搜索",actions.search)
    val send = context.iconButton(android.R.drawable.ic_menu_send,"发送问题",actions.send).apply {
        applyIconActionStyle(primary=true);applyAssistantSendShape();isEnabled=false
    }

    init { with(context) {
        headerAction(2,R.drawable.ic_line_expand,"在应用中打开",actions.expand)
        copy=header.menu.add(0,4,4,"复制最近回答").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { actions.copy();true }
        }
        body.addView(header,LinearLayout.LayoutParams(-1,dp(44)))
        body.addView(View(this).apply { setBackgroundColor(BORDER) },LinearLayout.LayoutParams(-1,dp(1)))
        val sourceCard=assistantCard(12)
        sourceCard.addView(sourceContent);content.addView(sourceCard,LinearLayout.LayoutParams(-1,-2))
        content.addView(response);content.addView(status)
        body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(noticeSlot,LinearLayout.LayoutParams(-1,-2))
        val entry=FrameLayout(this)
        controls.addView(entry,LinearLayout.LayoutParams(0,dp(32),1f))
        entry.addView(toggle,FrameLayout.LayoutParams(-2,-1,Gravity.START or Gravity.CENTER_VERTICAL))
        fun compact(resource: Int, description: String, action: () -> Unit) = iconButton(resource,description,action).apply {
            iconSize=dp(17);minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0;setPadding(dp(7),0,dp(7),0)
        }.also { controls.addView(it,LinearLayout.LayoutParams(dp(32),dp(32))) }
        cancel=compact(android.R.drawable.ic_menu_close_clear_cancel,"取消当前回答",actions.cancel)
        retry=compact(android.R.drawable.ic_popup_sync,"重试原问题与证据",actions.retry)
        resize=compact(R.drawable.ic_resize,"拖动调整窗口大小") {}
        body.addView(controls,LinearLayout.LayoutParams(-1,-2))
        composerSection.addView(View(this).apply { setBackgroundColor(BORDER) },LinearLayout.LayoutParams(-1,dp(1)))
        val composer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(9)) }
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,0) }
        imageChoice=if(image)MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text="沿用本次截图";isAllCaps=false;maxLines=1;ellipsize=TextUtils.TruncateAt.END;minWidth=0;minimumWidth=0
            setPadding(dp(12),0,dp(12),0);setTextAppearance(R.style.TextAppearance_AlwaySide_AssistantAction)
            icon=prototypeIcon(R.drawable.ic_line_chevron_down);iconSize=dp(14);iconPadding=dp(4)
            iconGravity=MaterialButton.ICON_GRAVITY_END;iconTint=ColorStateList.valueOf(MUTED);gravity=Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(INK);strokeColor=ColorStateList.valueOf(BORDER);strokeWidth=dp(1);cornerRadius=dp(18)
            insetTop=dp(5);insetBottom=dp(5)
            accessible("图片上下文：沿用本次截图，点按选择");setOnClickListener(actions.imageChoice)
        }.also { tools.addView(it,LinearLayout.LayoutParams(0,dp(48),1f)) }
        else null.also { tools.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }
        inputLayout.addView(input,LinearLayout.LayoutParams(-1,-2));composer.addView(inputLayout,LinearLayout.LayoutParams(-1,-2))
        tools.addView(search,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(4) })
        tools.addView(send,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(4) })
        composer.addView(tools,LinearLayout.LayoutParams(-1,-2));composerSection.addView(composer,LinearLayout.LayoutParams(-1,-2))
        body.addView(composerSection,LinearLayout.LayoutParams(-1,-2));setComposer(false)
    } }

    fun setComposer(expanded: Boolean, draft: Boolean = false) {
        composerSection.visibility=if(expanded)View.VISIBLE else View.GONE
        toggle.setAssistantComposerState(expanded,draft)
    }
}

internal fun MaterialButton.applyAssistantSendShape() {
    cornerRadius=context.dp(17)
    insetLeft=context.dp(1);insetRight=context.dp(1);insetTop=context.dp(4);insetBottom=context.dp(4)
}

internal fun MaterialButton.setAssistantComposerState(expanded:Boolean,draft:Boolean=false) {
    icon=context.prototypeIcon(if(expanded)R.drawable.ic_line_chevron_down else R.drawable.ic_line_chevron_up)
    text=if(expanded)"收起输入框" else if(draft)"继续编辑" else "继续提问"
    isActivated=draft;setTextColor(ACCENT);iconTint=ColorStateList.valueOf(ACCENT)
    accessible(if(expanded)"收起追问输入框" else if(draft)"展开追问输入框，继续编辑草稿" else "展开追问输入框")
}

private fun Context.assistantCard(radiusDp:Int) = MaterialCardView(
    ContextThemeWrapper(this,R.style.ThemeOverlay_AlwaySide_AssistantCard)
).apply { setCardBackgroundColor(SURFACE);strokeWidth=0;cardElevation=0f;radius=dp(radiusDp).toFloat() }

internal fun Context.assistantMessageRow(user: Boolean): ViewGroup {
    val message=label("",if(user)12f else 13f).apply {
        setTextAppearance(if(user)R.style.TextAppearance_AlwaySide_AssistantQuestion else R.style.TextAppearance_AlwaySide_AssistantBody)
        setTextColor(INK);includeFontPadding=false;setTextIsSelectable(true)
        movementMethod=android.text.method.LinkMovementMethod.getInstance()
        if(user) { setTextColor(ON_TONAL_SURFACE);setPadding(dp(12),dp(8),dp(12),dp(8)) }
    }
    return (if(user)assistantCard(15).apply { addView(message) }
        else LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(message) }).apply {
        tag=user;layoutParams=LinearLayout.LayoutParams(if(user)-2 else -1,-2).apply {
            gravity=if(user)Gravity.END else Gravity.START;if(user)marginStart=dp(24);bottomMargin=dp(12)
        }
    }
}
