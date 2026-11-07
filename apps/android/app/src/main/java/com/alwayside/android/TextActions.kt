package com.alwayside.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** A view ID is required by Android's PROCESS_TEXT menu, even for read-only text. */
internal fun TextView.enableTextActions(selectable: Boolean = true, read: ((String) -> Unit)? = null) {
    if (id == View.NO_ID) id = View.generateViewId()
    if (selectable) setTextIsSelectable(true)
    // Activity windows use Android's result-aware implementation. Service overlays need
    // explicit read-only launches because their Context cannot startActivityForResult.
    if (read != null) {
        setTag(R.id.point_reading_action, read)
        customSelectionActionModeCallback = ReadingTextActions(this, if (context.hasActivity()) null else OverlayTextActions(this))
    } else {
        if (getTag(R.id.point_reading_action) != null) {
            setTag(R.id.point_reading_action, null)
            customSelectionActionModeCallback = null
        }
        if (!context.hasActivity()) customSelectionActionModeCallback = OverlayTextActions(this)
    }
}

private class ReadingTextActions(private val text: TextView, private val delegate: ActionMode.Callback?) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        delegate?.onCreateActionMode(mode, menu)
        if (InteractionPreferences(text.context).load().chatPointReading)
            menu.add(Menu.NONE, R.id.point_reading_action, 0, "朗读")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val changed = !InteractionPreferences(text.context).load().chatPointReading && menu.findItem(R.id.point_reading_action) != null
        if (changed) menu.removeItem(R.id.point_reading_action)
        return (delegate?.onPrepareActionMode(mode, menu) ?: false) || changed
    }
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != R.id.point_reading_action) return delegate?.onActionItemClicked(mode, item) ?: false
        val start = minOf(text.selectionStart, text.selectionEnd)
        val end = maxOf(text.selectionStart, text.selectionEnd)
        if (start >= 0 && end <= text.text.length && end > start) {
            @Suppress("UNCHECKED_CAST")
            val read = text.getTag(R.id.point_reading_action) as? ((String) -> Unit)
            read?.invoke(text.text.subSequence(start, end).toString())
        }
        mode.finish()
        return true
    }
    override fun onDestroyActionMode(mode: ActionMode) { delegate?.onDestroyActionMode(mode) }
}

private fun Context.hasActivity(): Boolean = when (this) {
    is Activity -> true
    is ContextWrapper -> baseContext !== this && baseContext.hasActivity()
    else -> false
}

private class OverlayTextActions(private val text: TextView) : ActionMode.Callback {
    private val group = View.generateViewId()
    private val actions = mutableMapOf<Int, Intent>()

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        actions.clear()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
        add(menu, "分享", share)
        val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val context = text.context
        val manager = context.packageManager
        manager.queryIntentActivities(process, 0).forEach { resolved ->
            val activity = resolved.activityInfo ?: return@forEach
            val own = activity.packageName == context.packageName
            val allowed = activity.exported && (activity.permission == null ||
                context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
            if (own || allowed) add(menu, resolved.loadLabel(manager), Intent(process)
                .setClassName(activity.packageName, activity.name)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        }
        return true
    }

    private fun add(menu: Menu, title: CharSequence, intent: Intent) {
        val id = View.generateViewId()
        actions[id] = intent
        menu.add(group, id, Menu.CATEGORY_SECONDARY, title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = actions[item.itemId] ?: return false // Let TextView handle copy, paste and select all.
        val value = text.text
        val start = minOf(text.selectionStart, text.selectionEnd)
        val end = maxOf(text.selectionStart, text.selectionEnd)
        if (start < 0 || end > value.length || start == end) return true
        val selected = value.subSequence(start, end).toString()
        val intent = if (action.action == Intent.ACTION_SEND) {
            Intent.createChooser(Intent(action).putExtra(Intent.EXTRA_TEXT, selected), null)
        } else Intent(action).putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
        try {
            text.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(text.context, "未找到可用的文字处理应用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(text.context, "无法打开此文字处理应用", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) { actions.clear() }
}
