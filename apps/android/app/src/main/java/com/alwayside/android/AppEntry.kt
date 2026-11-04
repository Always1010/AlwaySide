package com.alwayside.android

import android.content.Context
import android.content.Intent
import java.util.UUID

/** One-use in-process routes. Arbitrary launcher extras cannot trigger automatic queries. */
internal object AppEntry {
    data class Request(val page: AppPage, val conversationId: String? = null, val autoAskText: Boolean = false)
    private val pending = linkedMapOf<String, Request>()
    private const val TOKEN = "com.alwayside.android.INTERNAL_ROUTE"
    fun intent(context: Context, page: AppPage, id: String? = null, autoAskText: Boolean = false): Intent {
        val token = UUID.randomUUID().toString()
        pending[token] = Request(page, id, autoAskText)
        while(pending.size > 16) pending.remove(pending.keys.first())
        return Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(TOKEN, token)
    }
    fun take(intent: Intent): Request? {
        val token = intent.getStringExtra(TOKEN) ?: return null
        intent.removeExtra(TOKEN)
        return pending.remove(token)
    }
}
