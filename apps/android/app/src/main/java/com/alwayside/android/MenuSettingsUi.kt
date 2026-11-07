package com.alwayside.android

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.PopupMenu

/** Immediate saves: navigation and rotation have no uncommitted menu draft. */
internal class MenuSettingsUi(private val context: Context, initial: List<AssistantMenuAction>,
    private val save: (List<AssistantMenuAction>) -> Boolean) {
    private var items = initial
    private val selected = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val available = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val availableSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var popup: PopupMenu? = null
    private var disposed = false
    private data class Drag(val owner: MenuSettingsUi, val action: AssistantMenuAction)

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.settingsSectionHeader("菜单工具"))
        addView(context.settingsListGroup(selected), LinearLayout.LayoutParams(-1, -2))
        addView(context.label("长按拖动调整工具顺序", 12f, true).apply {
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        })
        addView(availableSection, LinearLayout.LayoutParams(-1, -2))
    }

    init {
        availableSection.addView(context.settingsSectionHeader("可添加工具"))
        availableSection.addView(context.settingsListGroup(available), LinearLayout.LayoutParams(-1, -2))
        render()
    }

    fun reset() = update(AssistantMenuAction.defaults)
    fun dispose() { disposed = true; popup?.dismiss(); popup = null }

    private fun update(next: List<AssistantMenuAction>): Boolean {
        if (disposed) return false
        if (next == items) return true
        if (!save(next)) return false
        items = next; render()
        return true
    }

    private fun remove(action: AssistantMenuAction) = update(items - action)
    private fun add(action: AssistantMenuAction) = update((items + action).distinct())
    private fun move(action: AssistantMenuAction, delta: Int): Boolean {
        val index = items.indexOf(action)
        val target = items.getOrNull(index + delta) ?: return false
        return update(AssistantMenuAction.move(items, action, target))
    }

    private fun render() { with(context) {
        selected.removeAllViews(); available.removeAllViews()
        val cells = items.map { tile(it, true) }.toMutableList<View>()
        if (items.size < AssistantMenuAction.entries.size) {
            cells += AssistantMenuTileView(this, R.drawable.ic_line_plus, "添加工具") {
                // The available grid is already on this page; move focus and reveal it.
                available.requestRectangleOnScreen(android.graphics.Rect(0, 0, available.width, available.height), false)
                available.findViewWithTag<View>("first_available")?.requestFocus()
            }.apply {
                getChildAt(0).background = outlinedCard(android.graphics.Color.TRANSPARENT, 12).apply {
                    setStroke(dp(1), MUTED, dp(4).toFloat(), dp(4).toFloat())
                }
            }
        }
        selected.setPadding(dp(8), dp(8), dp(8), dp(8))
        selected.addView(assistantMenuGrid(cells), LinearLayout.LayoutParams(-1, -2))
        val missing = AssistantMenuAction.entries.filter { it !in items }
        availableSection.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        available.setPadding(dp(8), dp(8), dp(8), dp(8))
        available.addView(assistantMenuGrid(missing.mapIndexed { index, action ->
            tile(action, false).apply { if (index == 0) tag = "first_available" }
        }), LinearLayout.LayoutParams(-1, -2))
    } }

    private fun tile(action: AssistantMenuAction, included: Boolean): View = with(context) {
        val cell = AssistantMenuTileView(this, action.iconResource(), action.title,
            if (included) "${action.title}，点击调整或长按拖动" else "添加${action.title}") {
            if (included) showActions(action) else add(action)
        }
        if (included) {
            cell.setOnLongClickListener {
                val drag = Drag(this@MenuSettingsUi, action)
                val started = cell.startDragAndDrop(ClipData.newPlainText("menu-tool", action.name),
                    View.DragShadowBuilder(cell), drag, 0)
                started
            }
            cell.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val index = items.indexOf(action)
                    if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.menu_move_before, "前移"))
                    if (index in 0 until items.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.menu_move_after, "后移"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.menu_remove, "移除"))
                }
                override fun performAccessibilityAction(host: View, id: Int, args: Bundle?): Boolean = when (id) {
                    R.id.menu_move_before -> move(action, -1)
                    R.id.menu_move_after -> move(action, 1)
                    R.id.menu_remove -> remove(action)
                    else -> super.performAccessibilityAction(host, id, args)
                }
            }
        }
        FrameLayout(this).apply {
            tag = action
            addView(cell, FrameLayout.LayoutParams(-1, -1))
            val badge = iconButton(if (included) R.drawable.ic_line_minus else R.drawable.ic_line_plus,
                (if (included) "移除" else "添加") + action.title) {
                if (included) remove(action) else add(action)
            }.apply {
                iconSize = dp(12); setPadding(dp(14), dp(14), dp(14), dp(14))
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
                // The visible badge is 20dp; its transparent touch target is 44dp.
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                insetLeft = dp(12); insetRight = dp(12); insetTop = dp(12); insetBottom = dp(12)
                cornerRadius = dp(10)
            }
            addView(badge, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
            if (included) setOnDragListener { host, event ->
                val drag = event.localState as? Drag
                if (drag?.owner !== this@MenuSettingsUi) return@setOnDragListener false
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        host.background = outlinedCard(TONAL_SURFACE, 12); true
                    }
                    DragEvent.ACTION_DRAG_LOCATION -> { scrollAtEdge(host, event); true }
                    DragEvent.ACTION_DRAG_EXITED -> { host.background = null; true }
                    DragEvent.ACTION_DROP -> {
                        host.background = null
                        val next = AssistantMenuAction.move(items, drag.action, action)
                        // Wait until drag dispatch finishes before replacing source/target views.
                        host.post { update(next) }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { host.background = null; true }
                    else -> true
                }
            }
        }
    }

    private fun scrollAtEdge(host: View, event: DragEvent) {
        var parent = host.parent
        while (parent != null && parent !is ScrollView) parent = parent.parent
        val scroll = parent as? ScrollView ?: return
        val point = IntArray(2); val bounds = IntArray(2)
        host.getLocationOnScreen(point); scroll.getLocationOnScreen(bounds)
        val y = point[1] + event.y - bounds[1]
        val amount = when {
            y < context.dp(64) -> -context.dp(16)
            y > scroll.height - context.dp(64) -> context.dp(16)
            else -> 0
        }
        if (amount != 0) scroll.smoothScrollBy(0, amount)
    }

    private fun showActions(action: AssistantMenuAction) {
        val anchor = selected.findViewWithTag<View>(action) ?: selected
        popup?.dismiss()
        popup = PopupMenu(context, anchor).apply {
            val index = items.indexOf(action)
            if (index > 0) menu.add("前移").setOnMenuItemClickListener { move(action, -1) }
            if (index in 0 until items.lastIndex) menu.add("后移").setOnMenuItemClickListener { move(action, 1) }
            menu.add("移除").setOnMenuItemClickListener { remove(action) }
            show()
        }
    }
}
