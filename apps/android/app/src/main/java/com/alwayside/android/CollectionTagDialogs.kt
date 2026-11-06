package com.alwayside.android

import android.app.Activity
import android.content.res.ColorStateList
import android.text.InputFilter
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Shared collection editing for the conversation and history screens. */
internal class CollectionTagDialogs(private val activity: Activity, private val onChanged: () -> Unit) {
    private val store = HistoryStore(activity)

    private fun failed(error: Throwable) = Toast.makeText(activity,
        error.message?.take(120) ?: "操作失败，请检查本机存储后重试", Toast.LENGTH_LONG).show()

    fun createTag() { editName(after = {}) }

    private fun editName(current: String? = null, after: () -> Unit) {
        val layout = TextInputLayout(activity).apply {
            hint = "标签名称"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(layout.context).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(64)); setText(current.orEmpty())
        }
        layout.addView(input)
        val content = activity.column().apply { addView(layout) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (current == null) "新建标签" else "重命名标签")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val name = input.text.toString().trim()
                    require(name.isNotBlank()) { "请输入标签名称" }
                    if (current == null) store.createTag(name) else store.renameTag(current, name)
                }.onSuccess { dialog.dismiss(); after(); onChanged() }
                    .onFailure { layout.error = it.message ?: "无法保存标签" }
            }
        }
        dialog.show()
    }

    fun editTags(ids: List<String>, collectMissing: Boolean = false, onSaved: () -> Unit = {}) {
        val records = try { ids.mapNotNull(store::summary) } catch (error: Exception) { failed(error); return }
        if (records.isEmpty()) return
        if (!collectMissing && records.any { !it.isFavorite }) return
        val collecting = collectMissing && records.any { !it.isFavorite }
        val edits = linkedMapOf<String, Boolean>()
        val dialog = BottomSheetDialog(activity)
        val body = activity.column()
        val choices = activity.column().apply { setPadding(0, 0, 0, 0) }
        fun renderChoices() {
            choices.removeAllViews()
            val names = try { store.tags() } catch (error: Exception) { failed(error); return }
            if (names.isEmpty()) choices.addView(activity.label("还没有标签", 14f, true).apply {
                setPadding(activity.dp(8), activity.dp(20), activity.dp(8), activity.dp(20))
            })
            names.forEach { name ->
                val count = records.count { name in it.tags }
                choices.addView(MaterialCheckBox(activity).apply {
                    text = name; setTextColor(INK); minHeight = activity.dp(48)
                    checkedState = when {
                        edits[name] == true -> MaterialCheckBox.STATE_CHECKED
                        edits[name] == false -> MaterialCheckBox.STATE_UNCHECKED
                        count == records.size -> MaterialCheckBox.STATE_CHECKED
                        count > 0 -> MaterialCheckBox.STATE_INDETERMINATE
                        else -> MaterialCheckBox.STATE_UNCHECKED
                    }
                    addOnCheckedStateChangedListener { _, checked -> edits[name] = checked == MaterialCheckBox.STATE_CHECKED }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        body.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.label(if (collecting) "收藏并设置标签" else "会话标签", 20f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.iconButton(R.drawable.ic_line_plus, "新建标签") { editName(after = ::renderChoices) })
        })
        body.addView(ScrollView(activity).apply { addView(choices); isFillViewport = false },
            LinearLayout.LayoutParams(-1, activity.dp(280)))
        body.action(if (collecting) "收藏并保存" else "保存", true) {
            runCatching {
                store.editTags(ids.distinct(), edits.toMap(), collectMissing)
            }.onSuccess { dialog.dismiss(); onSaved(); onChanged() }.onFailure { failed(it) }
        }
        renderChoices(); dialog.setContentView(body); dialog.show()
    }

    fun manageTags() {
        val dialog = BottomSheetDialog(activity)
        val body = activity.column()
        val rows = activity.column().apply { setPadding(0, 0, 0, 0) }
        fun renderRows() {
            rows.removeAllViews()
            val names = try { store.tags() } catch (error: Exception) { failed(error); return }
            if (names.isEmpty()) rows.addView(activity.label("还没有标签", 14f, true))
            names.forEach { name ->
                rows.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(activity.label(name, 16f).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(activity.iconButton(R.drawable.ic_line_edit, "重命名标签：$name") {
                        editName(name, ::renderRows)
                    })
                    addView(activity.iconButton(R.drawable.ic_line_trash, "删除标签：$name") {
                        MaterialAlertDialogBuilder(activity).setTitle("删除标签“$name”？")
                            .setMessage("会话及其内容会保留。")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                runCatching { store.deleteTag(name) }.onSuccess { renderRows(); onChanged() }
                                    .onFailure(::failed)
                            }.show()
                    }.apply { iconTint = ColorStateList.valueOf(MUTED) })
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        body.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.label("管理标签", 20f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.iconButton(R.drawable.ic_line_plus, "新建标签") { editName(after = ::renderRows) })
        })
        body.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, activity.dp(320)))
        renderRows(); dialog.setContentView(body); dialog.show()
    }
}
