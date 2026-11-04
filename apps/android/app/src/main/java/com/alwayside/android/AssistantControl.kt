package com.alwayside.android

import android.content.Context
import android.content.Intent

/** Keep an explicit stop across activity recreation and future launcher visits. */
internal object AssistantControl {
    private fun preferences(context: Context) =
        context.getSharedPreferences("assistant_onboarding", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean("assistant_enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("assistant_enabled", enabled).apply()
    }

    fun stop(context: Context) {
        setEnabled(context, false)
        context.stopService(Intent(context, AssistantService::class.java))
    }
}
