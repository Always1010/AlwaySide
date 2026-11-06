package com.alwayside.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Demonstrations feed synthetic touch events to the same controller as real practice.
 * No callback reaches the real assistant, preferences or media capture. */
internal object LongPressSlideTutorial {
    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: Activity, config: InteractionConfig,
        showDialog: (MaterialAlertDialogBuilder) -> AlertDialog = { it.show() },
        lesson: LongPressSlideLesson = LongPressSlideLesson()): AlertDialog = with(activity) {
        val localConfig = config.copy(longPressSlide = true)
        val hold = maxOf(config.longPressMillis.toLong(), ViewConfiguration.getLongPressTimeout().toLong())
        val handler = Handler(Looper.getMainLooper())
        val content = column()
        val heading = label("", 17f)
        val instruction = label("", 14f).apply { setPadding(0, dp(8), 0, dp(12)) }
        val state = label("", 14f, true).apply {
            setPadding(0, dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(label("模拟练习 · 不截图、不录音、不联网", 12f, true).apply { setPadding(0, 0, 0, dp(16)) })
        content.addView(heading); content.addView(instruction)
        val sceneHeight = maxOf(dp(272), (dp(210) * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
        val scene = FrameLayout(this).apply {
            background = card(SURFACE); minimumHeight = sceneHeight
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(scene, LinearLayout.LayoutParams(-1, sceneHeight))
        val target = FrameLayout(this).apply {
            isClickable = true; isFocusable = true
            accessible("练习用小球，按住到提示出现后，按步骤松手或拖动")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val visual = AssistantBubbleView(this, config.ballSize, config.ballOpacity, config.ballColor)
        target.addView(visual, FrameLayout.LayoutParams(-1, -1))
        scene.addView(target, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(12); topMargin = (sceneHeight - dp(48)) / 2
        })
        val finger = ImageView(this).apply {
            setImageDrawable(prototypeIcon(R.drawable.ic_line_hand)); visibility = View.GONE
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scene.addView(finger, FrameLayout.LayoutParams(dp(32), dp(32)))
        content.addView(state)
        var hints: BubbleGestureHintsView? = null
        var demonstrating = false
        var active = true
        var demoDownAt = 0L
        lateinit var dialog: AlertDialog
        lateinit var controller: BubbleTouchController
        fun clearHints() { hints?.let(scene::removeView); hints = null }
        fun renderStep() {
            heading.text = if (lesson.complete) "练习完成" else "${lesson.index + 1}/${LongPressSlideLesson.Step.entries.size} · ${lesson.step.title}"
            instruction.text = if (lesson.complete) "现在可以返回设置，为功能分配这些手势。" else lesson.step.instruction
            state.text = if (lesson.complete) "练习没有修改你的开关或绑定。" else "先按住，等待小球反馈。"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                text = if (lesson.complete) "完成" else "下一步"
                isEnabled = lesson.complete || lesson.passed
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !lesson.complete
        }
        fun showHints() {
            clearHints()
            val view = BubbleGestureHintsView(this,
                mapOf(BubbleGesture.UP to BubbleGestureHint("框选问答", R.drawable.ic_line_crop),
                    BubbleGesture.DOWN to BubbleGestureHint("展开菜单", R.drawable.ic_line_menu),
                    BubbleGesture.INWARD to BubbleGestureHint("开始录音", R.drawable.ic_line_audio)),
                false, "截图问答", localConfig)
            view.measure(View.MeasureSpec.makeMeasureSpec((scene.width - dp(12)).coerceAtLeast(dp(48)), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(scene.height, View.MeasureSpec.AT_MOST))
            scene.addView(view, FrameLayout.LayoutParams(view.measuredWidth, view.measuredHeight).apply {
                leftMargin = (target.left + target.width / 2 - view.anchorX).coerceIn(0, (scene.width - view.measuredWidth).coerceAtLeast(0))
                topMargin = (target.top + target.height / 2 - view.anchorY).coerceIn(0, (scene.height - view.measuredHeight).coerceAtLeast(0))
                view.alignAnchor(target.left + target.width / 2 - leftMargin, target.top + target.height / 2 - topMargin)
            })
            hints = view; view.showAnimated(); finger.bringToFront()
        }
        fun accept(gesture: BubbleGesture?) {
            if (!demonstrating && active && !lesson.complete) {
                val accepted = lesson.accept(gesture)
                state.text = if (accepted) "做到了 · ${lesson.step.title}" else "再试一次 · ${lesson.step.instruction}"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = lesson.passed
            }
        }
        controller = BubbleTouchController(target, visual, config = { localConfig },
            onLeft = { false }, onGesture = ::accept, onCenterCancelled = { accept(null) }, onMove = { _, _ ->
            if (!demonstrating) state.text = "这是普通拖动。请先停住按到反馈出现，再移动。"
        }, onArmedChanged = { armed ->
            if (armed && active) {
                showHints()
                if (!demonstrating) state.text = "已就绪 · ${lesson.step.instruction}"
            } else clearHints()
        }, onPreview = { hints?.preview(it) })
        // Real touches are ignored while a scripted demonstration is in progress.
        target.setOnTouchListener { v, event -> if (demonstrating || lesson.complete) true else controller.onTouch(v, event) }
        fun send(action: Int, dx: Float = 0f, dy: Float = 0f) {
            if (!active) return
            val event = MotionEvent.obtain(demoDownAt, SystemClock.uptimeMillis(), action, dp(24) + dx, dp(24) + dy, 0)
            controller.onTouch(target, event); event.recycle()
        }
        fun stopDemo() {
            handler.removeCallbacksAndMessages(null)
            controller.cancel(); finger.visibility = View.GONE; demonstrating = false
        }
        fun demonstrate() {
            if (!active || lesson.complete) return
            stopDemo(); demonstrating = true
            state.text = "观看示范 · ${lesson.step.title}"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            val originX = target.left + target.width / 2f
            val originY = target.top + target.height / 2f
            finger.translationX = originX; finger.translationY = originY; finger.visibility = View.VISIBLE
            demoDownAt = SystemClock.uptimeMillis(); send(MotionEvent.ACTION_DOWN)
            val step = lesson.step
            val wait = hold + 180L
            handler.postDelayed({
                if (!active) return@postDelayed
                if (step.gesture?.directional == true || step == LongPressSlideLesson.Step.CANCEL) {
                    val travel = bubbleSlideThreshold(localConfig) + dp(8)
                    val dx = if (step.gesture == BubbleGesture.INWARD) -travel else 0f
                    val dy = when (step.gesture) { BubbleGesture.UP, null -> -travel; BubbleGesture.DOWN -> travel; else -> 0f }
                    val movementStarted = SystemClock.uptimeMillis()
                    // Recognition uses real time even when system animations are slowed or off.
                    val movement = object : Runnable {
                        override fun run() {
                            if (!active || !demonstrating) return
                            val f = ((SystemClock.uptimeMillis() - movementStarted) / 400f).coerceIn(0f, 1f)
                            val moved = if (step == LongPressSlideLesson.Step.CANCEL) (if (f < .5f) f * 2 else (1-f) * 2) else f
                            val drawn = if (ValueAnimator.areAnimatorsEnabled()) moved else if (f >= 1f) moved else 0f
                            finger.translationX = originX + dx * drawn; finger.translationY = originY + dy * drawn
                            send(MotionEvent.ACTION_MOVE, dx * moved, dy * moved)
                            if (f < 1f) handler.postDelayed(this, 16)
                        }
                    }
                    handler.post(movement)
                } else send(MotionEvent.ACTION_UP)
                handler.postDelayed({
                    if (!active) return@postDelayed
                    if (step.gesture?.directional == true) {
                        val travel = bubbleSlideThreshold(localConfig) + dp(8)
                        send(MotionEvent.ACTION_UP, if (step.gesture == BubbleGesture.INWARD) -travel else 0f,
                            if (step.gesture == BubbleGesture.UP) -travel else if (step.gesture == BubbleGesture.DOWN) travel else 0f)
                    } else send(MotionEvent.ACTION_UP)
                    stopDemo()
                    state.text = "示范结束 · 请亲自试一次"
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = lesson.passed
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                }, 550)
            }, wait)
        }
        dialog = showDialog(MaterialAlertDialogBuilder(this).setTitle("长按滑动指南")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null).setNeutralButton("观看示范", null).setPositiveButton("下一步", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (lesson.complete) dialog.dismiss()
            else { stopDemo(); controller.cancel(); lesson.next(); renderStep() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { demonstrate() }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { active = false; stopDemo(); controller.dispose() }
        })
        renderStep()
        dialog
    }
}
