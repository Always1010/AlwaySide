package com.alwayside.android

import android.content.Context

/** Stores only tutorial progress. Never reads credentials or writes application settings/history. */
internal class GuidePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("local_simulation_guide", Context.MODE_PRIVATE)

    fun initialize(existingUser: Boolean) {
        if (!prefs.contains("status")) save(GuideProgress.initial(existingUser))
    }

    fun load(): GuideProgress = GuideProgress.restore(prefs.getString("status", null), prefs.getString("step", null),prefs.getStringSet("lessons",null))

    fun save(progress: GuideProgress) {
        prefs.edit().putInt("version", 2).putString("status", progress.status.name)
            .putString("step", progress.step.name).putStringSet("lessons",progress.completedLessons.map { it.name }.toSet()).apply()
    }
}
