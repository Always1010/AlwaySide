package com.alwayside.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.util.UUID

/** Explicit Android text-selection/share entry. Never reads the clipboard or original document. */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This action asks about the selection; it never replaces text in the originating app.
        setResult(RESULT_CANCELED)
        if (savedInstanceState != null) { finish(); return }
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
            val openedOverlay = Settings.canDrawOverlays(this) && runCatching {
                startForegroundService(Intent(this, AssistantService::class.java)
                    .setAction(AssistantService.TEXT).putExtra("selectedText", text))
            }.isSuccess
            if (!openedOverlay) openInApp(text)
        } catch (error: Exception) {
            toast(error.message?.take(140) ?: "无法打开选中文字，请重试")
        } finally { finish() }
    }

    private fun openInApp(text: String) {
        val record = StoredConversation(
            id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis(),
            title = text.take(60), messages = emptyList(), evidence = "系统选中文字",
            kind = "text", status = "已接收选中文字 · 等待提问", sourceText = text,
        )
        HistoryStore(this).save(record)
        startActivity(Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ChatActivity.EXTRA_ID, record.id).putExtra(ChatActivity.EXTRA_AUTO_TEXT, true))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    companion object { private const val MAX_TEXT = 12000 }
}
