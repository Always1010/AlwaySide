package com.alwayside.android

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

private val nightMode: Boolean get() = AppAppearance.isNight
internal val BACKGROUND: Int get() = if(nightMode)0xFF151719.toInt() else Color.WHITE
internal val SURFACE: Int get() = if(nightMode)0xFF24272B.toInt() else 0xFFF2F4F5.toInt()
internal val ACCENT: Int get() = AppAppearance.accent
internal val ON_ACCENT: Int get() = AppAppearance.onAccent
internal val TONAL_SURFACE: Int get() = AppAppearance.tonalSurface
internal val ON_TONAL_SURFACE: Int get() = if(nightMode)0xFFF5F6F7.toInt() else 0xFF202428.toInt()
internal val INK: Int get() = if(nightMode)0xFFF5F6F7.toInt() else 0xFF202428.toInt()
internal val MUTED: Int get() = if(nightMode)0xFFBEC3C9.toInt() else 0xFF535B64.toInt()
internal val HINT: Int get() = if(nightMode)0xFFABB2BB.toInt() else 0xFF68717C.toInt()
internal val BORDER: Int get() = if(nightMode)0xFF41464E.toInt() else 0xFFDCE0E5.toInt()
private val DISABLED_INK: Int get() = if(nightMode)0xFF858D96.toInt() else 0xFF767E88.toInt()
private val DISABLED_SURFACE: Int get() = if(nightMode)0xFF30343A.toInt() else 0xFFE3E6E9.toInt()
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun card(color: Int,radius: Float = 16f * Resources.getSystem().displayMetrics.density) = GradientDrawable().apply {
    setColor(color);cornerRadius=radius
}
internal fun Context.outlinedCard(color: Int=BACKGROUND,radius:Int=16) = card(color,dp(radius).toFloat()).apply {
    setStroke(dp(1).coerceAtLeast(1),BORDER)
}
internal fun Context.touchFeedback() = RippleDrawable(ColorStateList.valueOf((INK and 0x00FFFFFF) or 0x14000000),null,card(Color.WHITE,dp(12).toFloat()))
private fun stateColors(normal:Int,disabled:Int) = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(disabled,normal))

/** Compatibility for free-form edit surfaces; form fields use TextInputLayout directly. */
internal fun EditText.applyInputStyle() {
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
    setTextColor(stateColors(INK,DISABLED_INK));setHintTextColor(HINT)
    minHeight=context.dp(48)
}
internal fun Context.selectionBackground() = RippleDrawable(ColorStateList.valueOf((ACCENT and 0x00FFFFFF) or 0x18000000),card(SURFACE,dp(12).toFloat()),null)

/** Material controls keep their own shape, inset, ripple and typography machinery. */
internal fun MaterialButton.applyActionStyle(primary:Boolean=false) {
    isAllCaps=false
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
    setTextColor(stateColors(if(primary)ON_ACCENT else ACCENT,DISABLED_INK))
    backgroundTintList=if(primary)stateColors(ACCENT,DISABLED_SURFACE) else ColorStateList.valueOf(Color.TRANSPARENT)
    iconTint=stateColors(if(primary)ON_ACCENT else ACCENT,DISABLED_INK)
    strokeWidth=0;minHeight=context.dp(48)
}
internal fun MaterialButton.applyIconActionStyle(primary:Boolean=false) {
    text="";iconPadding=0;iconSize=context.dp(20)
    iconTint=stateColors(if(primary)ON_ACCENT else INK,DISABLED_INK)
    backgroundTintList=if(primary)stateColors(ACCENT,DISABLED_SURFACE) else ColorStateList.valueOf(Color.TRANSPARENT)
    strokeWidth=0
    insetTop=0;insetBottom=0;insetLeft=0;insetRight=0
    minWidth=context.dp(48);minHeight=context.dp(48)
}
/** Local to onboarding callers; never changes the user's scheme or real conversation controls. */
internal fun MaterialButton.applyOnboardingPrimaryStyle(iconOnly:Boolean=false) {
    if(iconOnly)applyIconActionStyle(primary=true) else applyActionStyle(primary=true)
    val night=AppAppearance.isNight
    backgroundTintList=stateColors(ThemeScheme.BLUE.accent(night),DISABLED_SURFACE)
    val foreground=stateColors(ThemeScheme.BLUE.onAccent(night),DISABLED_INK)
    setTextColor(foreground);iconTint=foreground
}
/** A visible secondary action, with normal contrast and a surface even when it is not primary. */
internal fun MaterialButton.applySecondarySurfaceStyle() {
    applyActionStyle(false)
    backgroundTintList=stateColors(SURFACE,DISABLED_SURFACE)
    setTextColor(stateColors(INK,DISABLED_INK));iconTint=stateColors(INK,DISABLED_INK)
    strokeWidth=context.dp(1).coerceAtLeast(1);strokeColor=ColorStateList.valueOf(BORDER);cornerRadius=context.dp(12)
}
internal fun Context.iconButton(resource:Int,description:String,action:()->Unit):MaterialButton =
    MaterialButton(this,null,com.google.android.material.R.attr.materialIconButtonStyle).apply {
        icon=prototypeIcon(resource);iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START
        applyIconActionStyle();layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))
        accessible(description);setOnClickListener{action()}
    }
internal fun MaterialButton.applySearchToggleStyle(selected:Boolean) {
    applyIconActionStyle()
    isCheckable=true;isChecked=selected
    backgroundTintList=if(selected)stateColors(TONAL_SURFACE,DISABLED_SURFACE) else ColorStateList.valueOf(Color.TRANSPARENT)
    iconTint=stateColors(if(selected)ACCENT else MUTED,DISABLED_INK)
}
internal fun Context.column() = LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(24))
}
internal fun Context.label(value:String,size:Float=14f,muted:Boolean=false) = MaterialTextView(this).apply {
    setTextAppearance(when {
        size>=20 -> com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
        size>=17 -> com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        size>=15 -> com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        size>=13 -> com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        else -> com.google.android.material.R.style.TextAppearance_Material3_BodySmall
    })
    text=value;setTextColor(if(muted)MUTED else INK);includeFontPadding=false
}
internal fun LinearLayout.action(title:String,primary:Boolean=false,run:()->Unit):MaterialButton =
    MaterialButton(context,null,if(primary)com.google.android.material.R.attr.materialButtonStyle else androidx.appcompat.R.attr.borderlessButtonStyle).also { button ->
        button.text=title;button.applyActionStyle(primary);button.setOnClickListener{run()}
        addView(button,LinearLayout.LayoutParams(-2,-2).apply{topMargin=context.dp(8)})
    }
internal fun Context.materialToolbar(title:String,onBack:(()->Unit)?=null,compact:Boolean=false) = MaterialToolbar(this).apply {
    this.title=title;setTitleTextColor(INK);setSubtitleTextColor(MUTED)
    setBackgroundColor(BACKGROUND);elevation=0f
    minimumHeight=dp(if(compact)48 else 64)
    layoutParams=LinearLayout.LayoutParams(-1,dp(if(compact)48 else 64))
    setTitleTextAppearance(this@materialToolbar,if(compact)com.google.android.material.R.style.TextAppearance_Material3_TitleSmall else com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
    if(onBack!=null) {
        navigationIcon=prototypeIcon(android.R.drawable.ic_menu_revert)
        setNavigationIconTint(INK);navigationContentDescription="返回"
        setNavigationOnClickListener{onBack()}
    }
}
internal fun MaterialToolbar.addIconAction(id:Int,iconRes:Int,label:String,onClick:()->Unit):MenuItem = menu.add(0,id,menu.size(),label).apply {
    icon=context.prototypeIcon(iconRes);iconTintList=stateColors(INK,DISABLED_INK)
    contentDescription=label;tooltipText=label
    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    setOnMenuItemClickListener{onClick();true}
}
internal fun View.accessible(name:String) {contentDescription=name;tooltipText=name}

/** Shared settings rows: used by the existing Settings page and service settings. */
internal fun Context.settingsListRow(
    title: String, value: String = "", leading: View? = null, showArrow: Boolean = true,
    onClick: (() -> Unit)? = null,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(64); setPadding(dp(16), dp(14), dp(16), dp(14))
        if (onClick != null) {
            background = touchFeedback(); isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        contentDescription = listOf(title, value).filter(String::isNotBlank).joinToString("，")
        if (leading != null) addView(leading, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
        addView(LinearLayout(this@settingsListRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 16f))
            if (value.isNotBlank()) addView(label(value, 14f, true).apply {
                setPadding(0, dp(4), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        if (onClick != null && showArrow) addView(android.widget.ImageView(this@settingsListRow).apply {
            setImageDrawable(prototypeIcon(android.R.drawable.ic_media_next))
            imageTintList = ColorStateList.valueOf(MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
    }

internal fun Context.settingsListGroup(content: View) = com.google.android.material.card.MaterialCardView(this).apply {
    radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(SURFACE)
    addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
}

internal fun Context.settingsSectionHeader(
    title: String, help: String? = null, onHelp: () -> Unit = {},
    addDescription: String? = null, onAdd: () -> Unit = {},
) = LinearLayout(this).apply {
    gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, 0, 0)
    addView(label(title, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
    if (help != null) addView(iconButton(R.drawable.ic_line_info, "查看${title}说明", onHelp).apply {
        iconTint = ColorStateList.valueOf(MUTED)
    })
    if (addDescription != null) addView(iconButton(R.drawable.ic_line_plus, addDescription, onAdd))
}
