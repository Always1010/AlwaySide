package com.alwayside.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** Explicit Android text-selection/share entry. Never reads the clipboard or original document. */
class ProcessTextActivity : ThemedActivity() {
    private var confirmation: AlertDialog? = null
    private var information: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This action asks about the selection; it never replaces text in the originating app.
        setResult(RESULT_CANCELED)
        try {
            require(intent.type == "text/plain") { "仅支持选中文字" }
            val supplied = when (intent.action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                else -> null
            }
            require(supplied != null) { "没有收到选中文字" }
            val text = supplied.take(MAX_TEXT).toString()
                .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "").trim()
            require(text.isNotBlank()) { "选中文字为空" }
            if (supplied.length > MAX_TEXT) toast("选中文字较长，已取前 12000 字")
            confirmProcessing(text)
        } catch (error: Exception) {
            toast(error.message?.take(140) ?: "无法打开选中文字，请重试")
            finish()
        }
    }

    private fun confirmProcessing(text: String) {
        val preview = column().apply {
            addView(label(text.take(240) + if (text.length > 240) "…" else "", 14f))
            addView(iconButton(R.drawable.ic_line_info, "查看处理方式") {
                MaterialAlertDialogBuilder(this@ProcessTextActivity).setTitle("处理方式")
                    .setMessage("确认后按当前默认设置打开对话或提问。自动提问会将这段文字发送到配置的 API 服务，可能产生费用。")
                    .setPositiveButton("知道了", null).create().also {
                        information?.dismiss(); information = it; it.show()
                    }
            })
        }
        confirmation = MaterialAlertDialogBuilder(this).setTitle("处理这段文字？")
            .setView(preview).setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("确认处理") { _, _ ->
                try {
                    // Exported Intents never authorize networking; only this explicit button does.
                    val openedOverlay = Settings.canDrawOverlays(this) && runCatching {
                        startForegroundService(Intent(this, AssistantService::class.java)
                            .setAction(AssistantService.TEXT).putExtra("selectedText", text))
                    }.isSuccess
                    if (!openedOverlay) openInApp(text)
                } catch (error: Exception) {
                    toast(error.message?.take(140) ?: "无法打开选中文字，请重试")
                } finally { finish() }
            }.setOnCancelListener { finish() }.create().also {
                it.show()
                it.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
            }
    }

    override fun onDestroy() {
        confirmation?.dismiss(); confirmation = null
        information?.dismiss(); information = null
        super.onDestroy()
    }

    private fun openInApp(text: String) {
        val record = StoredConversation(
            id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis(),
            title = text.take(60), messages = emptyList(), evidence = "系统选中文字",
            kind = "text", status = "已接收选中文字 · 等待提问", sourceText = text,
        )
        HistoryStore(this).save(record)
        startActivity(AppEntry.intent(this,AppPage.CHAT,record.id,autoAskText=true))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    companion object { private const val MAX_TEXT = 12000 }
}
