package com.alwayside.android

import android.app.Activity
import android.text.InputFilter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal fun Activity.showConversationTitleDialog(current: String, save: (String) -> Unit) {
    val layout = TextInputLayout(this).apply {
        hint = "聊天标题"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
    }
    val input = TextInputEditText(layout.context).apply {
        setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(ConversationTitle.MAX_CUSTOM_LENGTH))
        setText(current); setSelection(text?.length ?: 0)
    }
    layout.addView(input)
    val dialog = MaterialAlertDialogBuilder(this).setTitle("重命名聊天")
        .setView(column().apply { addView(layout) })
        .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
    dialog.setOnShowListener {
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val title = input.text.toString().trim()
                ConversationTitle.validateCustom(title)
                save(title)
            }.onSuccess { dialog.dismiss() }
                .onFailure { layout.error = it.message ?: "无法保存标题，请重试" }
        }
    }
    dialog.show()
}
