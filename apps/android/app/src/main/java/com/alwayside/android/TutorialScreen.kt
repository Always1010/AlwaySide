package com.alwayside.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** These are ordinary in-page views, never service windows. Only navigation callbacks leave this script. */
internal class TutorialScreen(
    private val activity: Activity,
    private val flow: TutorialFlow,
    private val onBack: () -> Unit,
    private val onExit: () -> Unit,
    private val onChanged: (TutorialStep) -> Unit = {},
) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND) }

    init { render() }

    fun back(): Boolean {
        if (!flow.back()) return false
        onChanged(flow.step); render(); return true
    }

    private fun act(action: TutorialAction) {
        if (flow.act(action)) { onChanged(flow.step); render() }
    }

    private fun render() = with(activity) {
        view.removeAllViews()
        view.addView(materialToolbar("截图提问入门", onBack).apply {
            addIconAction(701, R.drawable.ic_line_close, "退出体验", onExit)
        })
        val body = column()
        view.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("模拟体验 · 不联网 · 不消耗 Token", 12f, true).apply {
            setPadding(0, 0, 0, dp(16))
        })
        if (flow.step == TutorialStep.COMPLETE) {
            body.addView(label("你已经学会截图提问了", 20f))
            body.addView(label("点小球 → 截图问答 → 查看回答 → 继续提问").apply { setPadding(0, dp(16), 0, dp(16)) })
            body.addView(label("刚才使用的是内置模拟，没有调用 AI 服务，也没有截取你的屏幕。实际使用需要配置自己的 AI 服务，并按需授予权限。", 14f, true))
            body.action("回到首页", true, onExit)
            return@with
        }
        val instruction = when (flow.step) {
            TutorialStep.BUBBLE -> "1 · 点一下右侧的小球，展开操作菜单。"
            TutorialStep.MENU -> "2 · 选择「截图问答」。本次使用内置示例，不会截取你的屏幕。"
            TutorialStep.ANSWER -> "3 · 回答会出现在这个小窗里。你已完成截图练习，也可以继续练习追问。"
            TutorialStep.COMPOSER -> "4 · 点击小窗左下方的「继续提问」，展开输入区。"
            TutorialStep.QUESTION -> "4 · 点击练习问题，将它填入输入区。"
            TutorialStep.SEND -> "4 · 点击发送箭头，查看预设的追问回答。"
            TutorialStep.FOLLOWUP -> "追问练习完成。实际使用时，你可以围绕截图自由提问。"
            TutorialStep.COMPLETE -> error("Handled above")
        }
        body.addView(label(instruction, 17f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, dp(16))
        })
        val stage = FrameLayout(this)
        val scene = column().apply { background = card(SURFACE) }
        stage.addView(scene, FrameLayout.LayoutParams(-1, -2))
        body.addView(stage, LinearLayout.LayoutParams(-1, -2))
        scene.addView(label("阅读示例", 12f, true))
        scene.addView(label(TutorialFlow.PASSAGE, 20f).apply { setPadding(0, dp(12), 0, dp(12)) })
        scene.addView(label("假设你正在阅读这段内容，想知道它是什么意思。", 14f, true))
        var target: View? = null
        if (flow.step in setOf(TutorialStep.BUBBLE, TutorialStep.MENU)) {
            val ballRow = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, 0) }
            val ball = iconButton(R.drawable.ic_assistant, "模拟悬浮球，展开菜单") { act(TutorialAction.OPEN_MENU) }.apply {
                backgroundTintList = ColorStateList.valueOf(TONAL_SURFACE)
                isEnabled = flow.step == TutorialStep.BUBBLE
            }
            ballRow.addView(ball); scene.addView(ballRow)
            if (flow.step == TutorialStep.BUBBLE) target = ball
            if (flow.step == TutorialStep.MENU) {
                val menu = column().apply { background = outlinedCard(); setPadding(dp(12), dp(8), dp(12), dp(8)) }
                val capture = menu.action("截图问答", true) { act(TutorialAction.CAPTURE) }.apply {
                    icon = prototypeIcon(R.drawable.ic_line_scan)
                }
                menu.action("框选问答") {}.apply { isEnabled = false; contentDescription = "框选问答，本次先练习截图问答" }
                menu.action("录制声音") {}.apply { isEnabled = false; contentDescription = "录制声音，本次先练习截图问答" }
                menu.action("收起菜单") { act(TutorialAction.CLOSE_MENU) }
                scene.addView(menu, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                target = capture
                stage.setOnClickListener { act(TutorialAction.CLOSE_MENU) }
            }
        } else {
            val window = column().apply { background = outlinedCard(); setPadding(dp(12), dp(12), dp(12), dp(16)) }
            window.addView(label("截图问答 · 示例回答", 17f))
            window.addView(label("示例画面：${TutorialFlow.PASSAGE}", 12f, true).apply { setPadding(0, dp(12), 0, dp(8)) })
            window.addView(label(TutorialFlow.QUESTION, 14f, true))
            window.addView(label(TutorialFlow.ANSWER, 17f).apply { setPadding(0, dp(12), 0, dp(12)) })
            when (flow.step) {
                TutorialStep.ANSWER -> {
                    body.action("练习追问", true) { act(TutorialAction.PRACTISE) }
                    body.action("完成体验") { act(TutorialAction.FINISH) }
                }
                TutorialStep.COMPOSER -> {
                    target = window.action("继续提问", true) { act(TutorialAction.EXPAND) }.apply {
                        icon = prototypeIcon(R.drawable.ic_line_chevron_down)
                    }
                }
                TutorialStep.QUESTION, TutorialStep.SEND -> {
                    window.addView(label("练习输入区", 12f, true))
                    window.addView(label(if (flow.step == TutorialStep.SEND) TutorialFlow.FOLLOWUP_QUESTION else "请使用下方的练习问题", 14f).apply {
                        background = outlinedCard(); setPadding(dp(12), dp(16), dp(12), dp(16))
                    })
                    if (flow.step == TutorialStep.QUESTION) {
                        target = window.action(TutorialFlow.FOLLOWUP_QUESTION, true) { act(TutorialAction.FILL) }
                        window.addView(label("练习仅使用这个预设问题，不会调用 AI。", 12f, true))
                    } else {
                        val sendRow = LinearLayout(this).apply { gravity = Gravity.END }
                        target = iconButton(R.drawable.ic_line_send, "发送练习问题，不调用 API") { act(TutorialAction.SEND) }
                        sendRow.addView(target); window.addView(sendRow)
                    }
                }
                TutorialStep.FOLLOWUP -> {
                    window.addView(label(TutorialFlow.FOLLOWUP_QUESTION, 14f, true))
                    window.addView(label(TutorialFlow.FOLLOWUP_ANSWER, 17f).apply { setPadding(0, dp(12), 0, 0) })
                    body.action("完成体验", true) { act(TutorialAction.FINISH) }
                }
                else -> Unit
            }
            scene.addView(window, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            body.addView(label("本次演示截图后自动提问。实际使用方式可在设置中调整；这里的回答全部为预设内容。", 12f, true).apply {
                setPadding(0, dp(12), 0, 0)
            })
        }
        target?.let { highlighted ->
            val spotlight = TutorialSpotlight(this, stage, highlighted)
            stage.addView(spotlight, FrameLayout.LayoutParams(-1, -1))
            highlighted.post {
                highlighted.requestRectangleOnScreen(Rect(0, 0, highlighted.width, highlighted.height), true)
                spotlight.invalidate()
            }
        }
    }
}

/** Non-interactive scrim: the real target remains clickable, with bounds measured in this scene. */
private class TutorialSpotlight(context: Context, private val stage: FrameLayout, private val target: View) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!target.isAttachedToWindow) return
        val bounds = Rect(); target.getDrawingRect(bounds); stage.offsetDescendantRectToMyCoords(target, bounds)
        val cutout = RectF(bounds).apply { inset(-context.dp(4).toFloat(), -context.dp(4).toFloat()) }
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(cutout, context.dp(16).toFloat(), context.dp(16).toFloat(), Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
    }
}
