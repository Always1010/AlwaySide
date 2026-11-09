package com.alwayside.android

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** A single field draft. All user dismissal paths pass through the owner's draft guard. */
internal class SettingsEditPanel(
    context: Context,
    title: String,
    helper: String,
    initial: String,
    multiline: Boolean,
    secret: Boolean,
    numeric: Boolean,
    decimal: Boolean,
    onDraft: (String) -> Unit,
    private val onRequestClose: () -> Unit,
    onSave: (String, (String) -> Unit) -> Unit,
    confirmLabel: String = "保存",
    maxDecimalPlaces: Int? = null,
) : BottomSheetDialog(context) {
    private var closing = false
    private val inputLayout = TextInputLayout(context).apply {
        hint = title
        helperText = helper
        setHelperTextTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setHelperTextColor(android.content.res.ColorStateList.valueOf(MUTED))
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = BACKGROUND
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    private val field = TextInputEditText(inputLayout.context).apply {
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(INK)
        setHintTextColor(HINT)
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        setSingleLine(!multiline)
        if (multiline) { minLines = 6; maxLines = 12; gravity = Gravity.TOP or Gravity.START }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (secret) {
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            setAutofillHints(null)
        }
        contentDescription = title
        if (maxDecimalPlaces != null) {
            val pattern = Regex("[0-9]*(\\.[0-9]{0,$maxDecimalPlaces})?")
            filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
                val next = dest.subSequence(0, dstart).toString() + source.subSequence(start, end) + dest.subSequence(dend, dest.length)
                if (pattern.matches(next)) null else dest.subSequence(dstart, dend)
            })
        }
        setText(initial)
        setSelection(length())
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                inputLayout.error = null
                onDraft(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    init {
        val content = context.column().apply { setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(24)) }
        inputLayout.addView(field, LinearLayout.LayoutParams(-1, -2))
        content.addView(inputLayout, LinearLayout.LayoutParams(-1, -2))
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "取消"; applyActionStyle(false)
                setOnClickListener { cancel() }
            }, LinearLayout.LayoutParams(-2, -2))
            addView(MaterialButton(context).apply {
                text = confirmLabel; applyActionStyle(true)
                setOnClickListener { onSave(field.text?.toString().orEmpty()) { inputLayout.error = it } }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(16) })
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.materialToolbar("编辑$title", compact = true))
            addView(ScrollView(context).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(layout)
        if (secret) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setOnShowListener {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            field.requestFocus()
            field.post {
                if (isShowing && !closing) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun cancel() {
        if (closing) { super.cancel(); return }
        // Drag-to-hide reaches cancel() too. Keep the same view/draft while confirmation is open.
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        onRequestClose()
    }

    /** Restore an in-memory draft without making it the editor's saved baseline. */
    fun restoreDraft(value: String) {
        field.setText(value)
        field.setSelection(field.length())
    }

    fun close() {
        closing = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(field.windowToken, 0)
        field.text?.clear()
        field.clearFocus()
        dismiss()
    }
}
