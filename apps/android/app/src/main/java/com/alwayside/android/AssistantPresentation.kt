package com.alwayside.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = context.dp(size) / 2f
        val x = width / 2f; val y = height / 2f
        paint.color = color; paint.alpha = if(recording)245 else (opacity * 2.55f).toInt()
        paint.style = Paint.Style.FILL; canvas.drawCircle(x,y,radius,paint)
        paint.color = Color.WHITE; paint.alpha = if(recording)255 else (opacity * 2.55f).toInt()
        if(recording) {
            val phase = SystemClock.uptimeMillis() / 180.0
            for(i in -1..1) {
                val h = radius * (.35 + .55 * abs(sin(phase+i))).toFloat()
                canvas.drawRoundRect(x+i*radius*.35f-context.dp(1),y-h/2,x+i*radius*.35f+context.dp(1),y+h/2,
                    context.dp(1).toFloat(),context.dp(1).toFloat(),paint)
            }
        } else {
            val r=radius*.57f
            sparkle.alpha=paint.alpha;sparkle.setBounds((x-r).toInt(),(y-r).toInt(),(x+r).toInt(),(y+r).toInt());sparkle.draw(canvas)
            if(working) {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(1).toFloat()
                canvas.drawArc(x-radius-2,y-radius-2,x+radius+2,y+radius+2,(SystemClock.uptimeMillis()%1800)/5f,90f,false,paint)
                paint.style=Paint.Style.FILL
            }
        }
    }
}

internal enum class AssistantMenuAction { SCREEN, AUDIO, CANCEL_AUDIO, CROP, LAST, PLAY, HISTORY, SETTINGS, STOP }

internal class AssistantMenuViews(context: Context, recording: Boolean = false, hasClip: Boolean = false,
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
                textSize=13f;maxLines=2
                setPadding(dp(8),dp(6),dp(8),dp(6));accessible(title)
                setOnClickListener { onAction(action) }
            }
            buttons[action]=row;view.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        item(AssistantMenuAction.SCREEN,R.drawable.ic_line_scan,"截图问答")
        item(AssistantMenuAction.AUDIO,R.drawable.ic_line_audio,if(recording)"结束录音并处理" else "听一段")
        if(recording)item(AssistantMenuAction.CANCEL_AUDIO,R.drawable.ic_line_close,"取消并丢弃录音")
        item(AssistantMenuAction.CROP,R.drawable.ic_line_crop,"框选问答")
        item(AssistantMenuAction.LAST,R.drawable.ic_line_text,"最近悬浮对话")
        if(hasClip)item(AssistantMenuAction.PLAY,R.drawable.ic_line_play,"回听上一段")
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
)

/** Exactly the same window hierarchy and controls for the service and the local tutorial. */
internal class AssistantWindowViews(context: Context, title: String, image: Boolean, actions: AssistantWindowActions) {
    val body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    val header = context.materialToolbar(title,null,compact=true)
    val pin = header.addIconAction(1,R.drawable.ic_pin,"固定窗口，点外部保持显示",actions.pin)
    val content = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(15),context.dp(13),context.dp(15),context.dp(8)) }
    val sourceContent = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(12),context.dp(10),context.dp(12),context.dp(10)) }
    val response = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(0,context.dp(12),0,0) }
    val status = context.label("",11f,true).apply { setPadding(0,context.dp(6),0,0) }
    val scroll = ScrollView(context).apply { addView(content);isFillViewport=true;isVerticalScrollBarEnabled=true }
    private val controls = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(context.dp(8),0,context.dp(8),0) }
    val toggle = MaterialButton(context,null,androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        applyActionStyle();setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        iconSize=context.dp(14);iconPadding=context.dp(4);iconGravity=MaterialButton.ICON_GRAVITY_TEXT_END
        minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0
        insetLeft=0;insetRight=0;insetTop=0;insetBottom=0
        setPadding(context.dp(8),0,context.dp(8),0);cornerRadius=context.dp(8)
        maxLines=1;ellipsize=TextUtils.TruncateAt.END;backgroundTintList=ColorStateList.valueOf(TONAL_SURFACE)
        setOnClickListener { actions.toggle() }
    }
    val cancel: MaterialButton
    val retry: MaterialButton
    val resize: MaterialButton
    val copy: MaterialButton
    val composerSection = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    val imageChoice: MaterialButton?
    val inputLayout = TextInputLayout(context,null,com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        hint="想了解什么？";boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
    }
    val input = TextInputEditText(inputLayout.context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        minLines=1;maxLines=3;minHeight=context.dp(48)
        filters=arrayOf(android.text.InputFilter.LengthFilter(12000))
    }
    val search = context.iconButton(R.drawable.ic_globe,"联网搜索",actions.search)
    val send = context.iconButton(android.R.drawable.ic_menu_send,"发送问题",actions.send).apply { applyIconActionStyle(primary=true) }

    init { with(context) {
        header.addIconAction(2,R.drawable.ic_line_expand,"在应用中打开",actions.expand)
        body.addView(header,LinearLayout.LayoutParams(-1,dp(48)))
        val sourceCard=MaterialCardView(this).apply { setCardBackgroundColor(SURFACE);strokeWidth=0;cardElevation=0f;radius=dp(12).toFloat() }
        sourceCard.addView(sourceContent);content.addView(sourceCard,LinearLayout.LayoutParams(-1,-2))
        content.addView(response);content.addView(status)
        body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val entry=FrameLayout(this)
        controls.addView(entry,LinearLayout.LayoutParams(0,dp(32),1f))
        entry.addView(toggle,FrameLayout.LayoutParams(-2,-1,Gravity.START or Gravity.CENTER_VERTICAL))
        fun compact(resource: Int, description: String, action: () -> Unit) = iconButton(resource,description,action).apply {
            iconSize=dp(14);minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0;setPadding(dp(9),0,dp(9),0)
        }.also { controls.addView(it,LinearLayout.LayoutParams(dp(32),dp(32))) }
        cancel=compact(android.R.drawable.ic_menu_close_clear_cancel,"取消当前回答",actions.cancel)
        retry=compact(android.R.drawable.ic_popup_sync,"重试原问题与证据",actions.retry)
        resize=compact(R.drawable.ic_resize,"拖动调整窗口大小") {}
        copy=compact(R.drawable.ic_copy,"复制最近回答",actions.copy)
        body.addView(controls,LinearLayout.LayoutParams(-1,-2))
        composerSection.addView(View(this).apply { setBackgroundColor(BORDER) },LinearLayout.LayoutParams(-1,dp(1)))
        val composer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(9)) }
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,0) }
        imageChoice=if(image)MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text="沿用本次截图  ▾";isAllCaps=false;maxLines=1;ellipsize=TextUtils.TruncateAt.END;minWidth=0;minimumWidth=0
            setPadding(dp(10),0,dp(10),0);setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
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
        toggle.icon=body.context.prototypeIcon(if(expanded)R.drawable.ic_line_chevron_down else R.drawable.ic_line_chevron_up)
        toggle.text=if(expanded)"收起输入框" else if(draft)"继续编辑" else "继续提问"
        toggle.accessible(if(expanded)"收起追问输入框" else "展开追问输入框")
    }
}

internal fun Context.assistantMessageRow(user: Boolean): ViewGroup {
    val message=label("",if(user)12f else 13f).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextIsSelectable(true);setLineSpacing(dp(3).toFloat(),1f)
        movementMethod=android.text.method.LinkMovementMethod.getInstance()
        if(user) { setTextColor(ON_TONAL_SURFACE);setPadding(dp(12),dp(8),dp(12),dp(8)) }
    }
    return (if(user)MaterialCardView(this).apply {
        setCardBackgroundColor(TONAL_SURFACE);strokeWidth=0;cardElevation=0f;radius=dp(16).toFloat();addView(message)
    } else FrameLayout(this).apply { addView(message) }).apply {
        tag=user;layoutParams=LinearLayout.LayoutParams(if(user)-2 else -1,-2).apply {
            gravity=if(user)Gravity.END else Gravity.START;if(user)marginStart=dp(24);bottomMargin=dp(12)
        }
    }
}
