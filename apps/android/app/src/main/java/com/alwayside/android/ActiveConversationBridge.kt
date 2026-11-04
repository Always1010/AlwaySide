package com.alwayside.android

import android.graphics.Bitmap
import android.os.Looper
import java.io.File

/** Main-thread handoff. No service is started and no media is written to disk. */
internal data class HandedOffConversation(val session: ChatSession, val image: Bitmap?, val dispose: () -> Unit,
    val audio: AudioClip? = null, val draft: String = "")

internal object ActiveConversationBridge {
    var detachHandler: ((String) -> Bitmap?)? = null
    private val pending = linkedMapOf<String, HandedOffConversation>()
    private val retainedAudio = mutableSetOf<String>()
    fun detach(id: String): Bitmap? {
        check(Looper.myLooper() == Looper.getMainLooper())
        return detachHandler?.invoke(id)
    }
    fun offer(id: String, value: HandedOffConversation) {
        check(Looper.myLooper() == Looper.getMainLooper())
        pending.put(id, value)?.dispose?.invoke()
        while (pending.size > 4) pending.remove(pending.keys.first())?.dispose?.invoke()
    }
    fun takeSession(id: String): HandedOffConversation? = pending.remove(id)
    fun retainAudio(file: File) { retainedAudio.add(file.absolutePath) }
    fun releaseAudio(file: File) { retainedAudio.remove(file.absolutePath) }
    fun isAudioRetained(file: File): Boolean = file.absolutePath in retainedAudio
}
