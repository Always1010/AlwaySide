package com.alwayside.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File

/** Keep a FileProvider export until the host returns from the chooser/receiving app. */
internal object StorageShare {
    fun protect(activity: Activity, file: File): () -> Unit {
        val token = Any()
        val runtime = StorageRuntime.get(activity)
        ConversationUsage.update(token, "@file:${runtime.path(file)}")
        var departed = false
        var finished = false
        lateinit var callback: Application.ActivityLifecycleCallbacks
        val release = {
            if (!finished) {
                finished = true
                ConversationUsage.update(token, null)
                activity.application.unregisterActivityLifecycleCallbacks(callback)
            }
        }
        callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(value: Activity) { if (value === activity) departed = true }
            override fun onActivityResumed(value: Activity) { if (value === activity && departed) release() }
            override fun onActivityDestroyed(value: Activity) { if (value === activity) release() }
            override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        }
        activity.application.registerActivityLifecycleCallbacks(callback)
        return release
    }
}
