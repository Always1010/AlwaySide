package com.alwayside.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** Apply the same whitelist to Activity windows, service overlays and editable text. */
internal fun TextView.enableTextActions(selectable: Boolean = true, read: ((String) -> Unit)? = null) {
    if (id == View.NO_ID) id = View.generateViewId()
    if (selectable) setTextIsSelectable(true)
    setTag(R.id.point_reading_action, read)
    val callback = customSelectionActionModeCallback as? SelectionTextActions ?: SelectionTextActions(this)
    customSelectionActionModeCallback = callback
    customInsertionActionModeCallback = callback
}

/** Hide by item identity: Android can assign the same ID (Menu.NONE) to many extensions. */
internal fun restrictTextActionMenu(menu: Menu, extras: Collection<MenuItem>): Boolean {
    var changed = false
    for (index in 0 until menu.size()) {
        val item = menu.getItem(index)
        val basic = when (item.itemId) {
            android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.pasteAsPlainText,
            android.R.id.selectAll, android.R.id.startSelectingText, android.R.id.stopSelectingText,
            android.R.id.selectTextMode, android.R.id.shareText, android.R.id.undo, android.R.id.redo -> true
            else -> false
        }
        if (!basic && extras.none { it === item } && item.isVisible) {
            item.isVisible = false
            changed = true
        }
    }
    return changed
}

internal fun isSystemTranslationTitle(title: String): Boolean = when (title.trim().lowercase(java.util.Locale.ROOT)) {
    "翻译", "翻譯", "系统翻译", "系統翻譯", "translate" -> true
    else -> false
}

internal fun isSystemTextTranslator(packageName: String, systemApp: Boolean): Boolean =
    systemApp && packageName == "com.vivo.translator"

internal fun findNativeSystemTranslation(menu: Menu, isFrameworkItem: (MenuItem) -> Boolean): MenuItem? =
    (0 until menu.size()).asSequence().map(menu::getItem)
        .firstOrNull { it.isVisible && it.itemId != R.id.system_translation_action &&
            it.intent == null && isSystemTranslationTitle(it.title.toString()) && isFrameworkItem(it) }

private class SelectionTextActions(private val text: TextView) : ActionMode.Callback {
    private var activeMode: ActionMode? = null
    private var translator: Intent? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        activeMode = mode
        translator = text.context.systemTextTranslator()
        updateMenu(menu)
        // Framework PROCESS_TEXT items are added after onCreateActionMode returns.
        text.post { if (activeMode === mode) mode.invalidate() }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = updateMenu(menu)

    private fun updateMenu(menu: Menu): Boolean {
        var changed = false
        val selected = selectedText() != null
        // Prefer native translation; an extension with the same label is not trusted.
        val nativeTranslation = findNativeSystemTranslation(menu, ::isFrameworkItem)
        val extras = mutableListOf<MenuItem>()
        if (nativeTranslation != null && selected) {
            if (nativeTranslation.title.toString() != "系统翻译") {
                nativeTranslation.title = "系统翻译"
                changed = true
            }
            extras.add(nativeTranslation)
        }

        fun action(id: Int, title: String, available: Boolean, order: Int) {
            val existing = menu.findItem(id)
            if (available) {
                val item = existing ?: menu.add(Menu.NONE, id, order, title).also {
                    it.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    changed = true
                }
                if (!item.isVisible) { item.isVisible = true; changed = true }
                extras.add(item)
            } else if (existing?.isVisible == true) {
                existing.isVisible = false
                changed = true
            }
        }

        action(R.id.point_reading_action, "点读", selected &&
            text.getTag(R.id.point_reading_action) != null && InteractionPreferences(text.context).load().chatPointReading, 0)
        action(R.id.text_share_action, "分享", selected && menu.findItem(android.R.id.shareText)?.isVisible != true,
            Menu.CATEGORY_SECONDARY)
        action(R.id.system_translation_action, "系统翻译", selected && nativeTranslation == null && translator != null,
            Menu.CATEGORY_SECONDARY + 1)
        return restrictTextActionMenu(menu, extras) || changed
    }

    private fun isFrameworkItem(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.textAssist || item.groupId == android.R.id.textAssist) return true
        return try {
            text.resources.getResourcePackageName(item.itemId) in setOf("android", "vivo", "com.vivo")
        } catch (_: Resources.NotFoundException) { false }
    }

    private fun selectedText(): String? {
        val value = text.text
        val start = minOf(text.selectionStart, text.selectionEnd)
        val end = maxOf(text.selectionStart, text.selectionEnd)
        return if (start >= 0 && end <= value.length && end > start)
            value.subSequence(start, end).toString().takeIf { it.isNotBlank() } else null
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.point_reading_action -> {
                val selected = selectedText()
                if (selected != null && InteractionPreferences(text.context).load().chatPointReading) {
                    @Suppress("UNCHECKED_CAST")
                    val read = text.getTag(R.id.point_reading_action) as? ((String) -> Unit)
                    read?.invoke(selected)
                }
                mode.finish()
            }
            android.R.id.shareText, R.id.text_share_action -> {
                val selected = selectedText() ?: return true
                launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, selected), null))
            }
            R.id.system_translation_action -> {
                val selected = selectedText() ?: return true
                val target = translator ?: return true
                // Read-only is deliberate: service Contexts cannot deliver Activity results.
                launch(Intent(target).putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
            }
            else -> return false // Keep platform editing and native translation handlers.
        }
        return true
    }

    private fun launch(intent: Intent) {
        try {
            text.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(text.context, "未找到可用的文字处理应用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(text.context, "无法打开此文字处理应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (activeMode === mode) { activeMode = null; translator = null }
    }
}

private fun Context.systemTextTranslator(): Intent? {
    val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage("com.vivo.translator")
    val resolved = packageManager.queryIntentActivities(process, 0).asSequence().filter { result ->
        val activity = result.activityInfo ?: return@filter false
        val flags = activity.applicationInfo?.flags ?: 0
        isSystemTextTranslator(activity.packageName,
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) &&
            activity.enabled && activity.exported && (activity.permission == null ||
                checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
    }.sortedBy { it.activityInfo.name }.firstOrNull() ?: return null
    return Intent(process).setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
}
