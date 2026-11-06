package com.alwayside.android

import android.app.Activity
import android.app.DatePickerDialog
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun HistoryDateRange.displayLabel(): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return "${start.format(format)} — ${end.format(format)}"
}

internal fun Activity.showHistoryDateFilter(current: HistoryDateRange?, onApply: (HistoryDateRange?) -> Unit) {
    var start = current?.start
    var end = current?.end
    val dateRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val error = label("", 12f, true).apply { visibility = View.GONE }
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun pickDate(initial: LocalDate?, selected: (LocalDate) -> Unit) {
        val day = initial ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, date ->
            selected(LocalDate.of(year, month + 1, date))
        }, day.year, day.monthValue - 1, day.dayOfMonth).show()
    }

    fun renderDates() {
        dateRows.removeAllViews()
        dateRows.addView(settingsListRow("开始日期", start?.format(format) ?: "选择日期") {
            pickDate(start ?: end) { start = it; error.visibility = View.GONE; renderDates() }
        })
        dateRows.addView(settingsListRow("结束日期", end?.format(format) ?: "选择日期") {
            pickDate(end ?: start) { end = it; error.visibility = View.GONE; renderDates() }
        })
    }
    renderDates()

    val content = column().apply {
        setPadding(dp(12), 0, dp(12), dp(8))
        addView(dateRows)
        addView(error.apply { setPadding(dp(12), dp(8), dp(12), 0) })
    }
    val title = settingsSectionHeader("按时间筛选", help = "按时间筛选", onHelp = {
        MaterialAlertDialogBuilder(this).setTitle("按时间筛选")
            .setMessage("按对话列表中的最后更新时间筛选，包含开始日期和结束日期的整天，日期遵循设备时区。可与关键词搜索一起使用。")
            .setPositiveButton("知道了", null).show()
    })
    val dialog = MaterialAlertDialogBuilder(this).setCustomTitle(title).setView(content)
        .setNegativeButton("取消", null).setNeutralButton("清除", null)
        .setPositiveButton("应用", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            isEnabled = current != null
            setOnClickListener { dialog.dismiss(); onApply(null) }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val from = start
            val to = end
            val message = when {
                from == null || to == null -> "请选择开始日期和结束日期"
                to.isBefore(from) -> "结束日期不能早于开始日期"
                else -> null
            }
            if (message != null) {
                error.text = message; error.visibility = View.VISIBLE
                error.announceForAccessibility(message)
            } else if (from != null && to != null) {
                dialog.dismiss(); onApply(HistoryDateRange(from, to))
            }
        }
    }
    dialog.show()
}
