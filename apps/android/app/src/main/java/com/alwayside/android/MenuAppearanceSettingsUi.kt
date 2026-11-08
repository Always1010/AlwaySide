package com.alwayside.android

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Sliders preview continuously and save on release; choices and numeric edits save immediately. */
internal class MenuAppearanceSettingsUi(
    private val context: Context,
    private val config: InteractionConfig,
    private val save: (MenuAppearance) -> Boolean,
    private val showDialog: (AlertDialog) -> Unit,
    private val onCustomize: () -> Unit,
) {
    private var appearance = config.menuAppearance
    private var saved = appearance
    private var disposed = false
    private var fullPreview: Dialog? = null
    private val caption = context.label("实时预览 · 缩略显示", 12f, true)
    private val preview = MenuAppearancePreview(context, config) { supported ->
        caption.text = if (supported) "实时预览 · 缩略显示" else "实时预览 · 背景模糊暂不可用"
    }
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(controls); isSaveEnabled = false }
    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init { with(context) {
        view.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40); setPadding(dp(16), 0, dp(8), 0)
            addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_line_expand, "全屏预览") { showFullPreview() }.apply {
                iconTint = ColorStateList.valueOf(MUTED)
            })
        }, LinearLayout.LayoutParams(-1, -2))
        view.addView(preview, LinearLayout.LayoutParams(-1,
            dp((resources.configuration.screenHeightDp / 3).coerceIn(120, 250))).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(6)
        })
        controls.setPadding(dp(16), 0, dp(16), dp(24))
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderControls()
    } }

    fun reset() {
        appearance = MenuAppearance()
        if (commit()) {
            renderControls(); preview.render(appearance)
            Toast.makeText(context, "已恢复默认外观", Toast.LENGTH_SHORT).show()
        }
    }

    fun dispose() {
        if (disposed) return
        commit()
        disposed = true
        fullPreview?.dismiss(); fullPreview = null
    }

    private fun commit(): Boolean {
        if (disposed) return false
        if (saved == appearance) return true
        if (save(appearance)) { saved = appearance; return true }
        appearance = saved
        renderControls(); preview.render(appearance)
        return false
    }

    private fun change(next: MenuAppearance) {
        if (disposed) return
        appearance = next
        preview.render(next)
    }

    private fun choose(next: MenuAppearance) {
        change(next)
        if (commit()) renderControls()
    }

    private fun renderControls() { with(context) {
        val y = scroll.scrollY
        controls.removeAllViews()
        fun section(title: String, build: LinearLayout.() -> Unit) {
            controls.addView(settingsSectionHeader(title), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12); bottomMargin = dp(6)
            })
            controls.addView(settingsListGroup(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; build()
            }), LinearLayout.LayoutParams(-1, -2))
        }
        section("菜单面板") {
            addView(settingsListRow("背景颜色", if (appearance.backgroundColor == null) "跟随应用" else "自定义",
                inlineValue = true) { chooseBackground() })
            addView(slider("面板透明度", 0..100, "%", { it.transparency }, { a, v -> a.copy(transparency = v) },
                "0% · 实心" to "100% · 全透明"))
            addView(slider("菜单大小", 30..150, "%", { it.sizePercent }, { a, v -> a.copy(sizePercent = v) },
                "30%" to "150%"))
        }
        section("背景效果") {
            addView(slider("背景模糊强度", 0..100, "%", { it.blurStrength }, { a, v -> a.copy(blurStrength = v) },
                "0% · 关闭" to "100% · 最强"))
            addView(settingsListRow("背景压暗", if (appearance.dimPercent == null) "自动" else "手动调整",
                inlineValue = true) {
                val choices = arrayOf("自动", "手动调整")
                showDialog(MaterialAlertDialogBuilder(context).setTitle("背景压暗")
                    .setSingleChoiceItems(choices, if (appearance.dimPercent == null) 0 else 1) { dialog, index ->
                        choose(appearance.copy(dimPercent = if (index == 0) null else preview.currentDimPercent))
                        dialog.dismiss()
                    }.setNegativeButton("取消", null).create())
            })
            if (appearance.dimPercent != null) addView(slider("压暗程度", 0..100, "%",
                { it.dimPercent ?: 18 }, { a, v -> a.copy(dimPercent = v) }, "0% · 不压暗" to "100% · 全黑"))
        }
        section("面板形状") {
            addView(slider("面板圆角", 0..100, "dp", { it.cornerRadiusDp }, { a, v -> a.copy(cornerRadiusDp = v) },
                "0 · 直角" to "100"))
        }
        controls.addView(settingsListGroup(settingsListRow("自定义菜单") { onCustomize() }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        scroll.post { if (!disposed) scroll.scrollTo(0, y) }
    } }

    private fun slider(title: String, range: IntRange, unit: String, read: (MenuAppearance) -> Int,
        update: (MenuAppearance, Int) -> MenuAppearance, ends: Pair<String, String>): View = with(context) {
        val value = label("${read(appearance)} $unit", 14f, true).apply {
            gravity = Gravity.END; setPadding(dp(8), dp(2), 0, dp(2))
            minimumHeight = dp(44); minimumWidth = dp(56)
            background = touchFeedback(); isFocusable = true; isClickable = true
            accessible("$title，${read(appearance)} $unit，点击输入数值")
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(8))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(value, LinearLayout.LayoutParams(-2, -2))
            })
            var dragging = false
            addView(Slider(context).apply {
                valueFrom = range.first.toFloat(); valueTo = range.last.toFloat(); stepSize = 1f
                this.value = read(appearance).toFloat(); contentDescription = title
                setLabelFormatter { "${it.toInt()} $unit" }
                addOnChangeListener { _, v, fromUser ->
                    if (fromUser) {
                        value.text = "${v.toInt()} $unit"
                        value.contentDescription = "$title，${v.toInt()} $unit，点击输入数值"
                        change(update(appearance, v.toInt()))
                        if (!dragging) commit()
                    }
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) { dragging = true }
                    override fun onStopTrackingTouch(slider: Slider) { dragging = false; commit() }
                })
            }, LinearLayout.LayoutParams(-1, dp(40)))
            addView(LinearLayout(context).apply {
                addView(label(ends.first, 12f, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(ends.second, 12f, true).apply { gravity = Gravity.END })
            })
            value.setOnClickListener { editNumber(title, range, unit, read, update) }
        }
    }

    private fun editNumber(title: String, range: IntRange, unit: String, read: (MenuAppearance) -> Int,
        update: (MenuAppearance, Int) -> MenuAppearance) {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(read(appearance).toString()); selectAll()
        }
        val field = TextInputLayout(context).apply {
            hint = "${range.first}–${range.last} $unit"; addView(input)
        }
        val wrap = LinearLayout(context).apply {
            setPadding(context.dp(24), context.dp(12), context.dp(24), 0); addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(wrap)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val number = input.text?.toString()?.toIntOrNull()
            if (number == null || number !in range) field.error = "请输入 ${range.first}–${range.last} 之间的数值"
            else if (!disposed) { choose(update(appearance, number)); dialog.dismiss() }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun chooseBackground() {
        showDialog(MaterialAlertDialogBuilder(context).setTitle("背景颜色")
            .setSingleChoiceItems(arrayOf("跟随应用", "自定义颜色"), if (appearance.backgroundColor == null) 0 else 1) { dialog, index ->
                dialog.dismiss()
                if (index == 0) choose(appearance.copy(backgroundColor = null)) else {
                    val picker = ColorPicker(context, appearance.backgroundColor ?: SURFACE)
                    showDialog(MaterialAlertDialogBuilder(context).setTitle("背景颜色").setView(ScrollView(context).apply { addView(picker) })
                        .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                            if (!disposed) choose(appearance.copy(backgroundColor = picker.selectedColor))
                        }.create())
                }
            }.setNegativeButton("取消", null).create())
    }

    private fun showFullPreview() {
        if (disposed || fullPreview != null) return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scene = MenuAppearancePreview(context, config.copy(menuAppearance = appearance), 1f)
        dialog.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND)
            addView(context.materialToolbar("菜单预览", { dialog.dismiss() }))
            addView(scene, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dialog.setOnDismissListener { fullPreview = null }
        fullPreview = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(BACKGROUND))
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.window?.setLayout(-1, -1)
    }
}
