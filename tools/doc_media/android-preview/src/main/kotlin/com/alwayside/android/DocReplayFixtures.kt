package com.alwayside.android

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Preview-only data adapters. No production stores, providers or network clients are staged. */
internal data class StoredConversation(val id: String, val title: String)

internal class ConversationMediaStore(context: Context) {
    fun loadAudio(owner: String): File? = DocReplayFixtures.audio
}

internal object DocReplayFixtures {
    var audio: File? = null
    var bundle: SubtitleBundle? = null
    fun reset() { audio = null; bundle = null }
}

internal class SubtitlePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("doc_subtitles", Context.MODE_PRIVATE)
    fun display() = SubtitleDisplay.entries.firstOrNull { it.name == prefs.getString("display", null) }
        ?: SubtitleDisplay.BILINGUAL
    fun saveDisplay(mode: SubtitleDisplay) { check(prefs.edit().putString("display", mode.name).commit()) }
    fun enabled(method: SubtitleMethod) = false
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

internal class SubtitleController {
    data class Record(val published: SubtitleBundle? = DocReplayFixtures.bundle,
        val draft: SubtitleBundle? = null, val busy: Boolean = false, val loaded: Boolean = true,
        val status: String = "已保存字幕 · 预设内容", val error: String = "") {
        val playable get() = published ?: draft
    }
    private val observers = linkedSetOf<() -> Unit>()
    fun record(owner: String, method: SubtitleMethod) = Record()
    fun addObserver(observer: () -> Unit) { observers += observer; observer() }
    fun removeObserver(observer: () -> Unit) { observers -= observer }
    fun refresh(owner: String) { observers.toList().forEach { it() } }
    fun cancel(owner: String, method: SubtitleMethod) = Unit
    fun delete(owner: String, method: SubtitleMethod): Unit = error("Preview cannot change saved subtitles")
    fun enqueue(recording: StoredConversation, source: File, method: SubtitleMethod, recreate: Boolean): Unit =
        error("Documentation preview cannot generate or upload subtitles")
    companion object {
        private val singleton = SubtitleController()
        fun get(context: Context) = singleton
    }
}

/** Deterministic local playback state drives the unmodified production presentation views. */
internal class ChatAudioPlayback {
    data class State(val preparing: Boolean = false, val playing: Boolean = false, val remaining: Float? = null)
    var position = 0; private set
    var duration = 78000; private set
    var originalSpeed = 1f; private set
    val readingKey = ""
    private var playing = false
    private var owner = ""
    private var lastElapsed = 0L
    private val observers = linkedSetOf<() -> Unit>()
    val currentState get() = State(playing = playing,
        remaining = if (owner.isEmpty()) null else (1f - position.toFloat() / duration).coerceIn(0f, 1f))
    fun reset(nextDuration: Int) {
        position = 0; duration = nextDuration.coerceAtLeast(1); playing = false
        owner = ""; originalSpeed = 1f; lastElapsed = 0L; changed()
    }
    fun isActive(id: String) = id == owner
    fun addObserver(observer: () -> Unit) { observers += observer; observer() }
    fun removeObserver(observer: () -> Unit) { observers -= observer }
    fun toggle() { playing = !playing; changed() }
    fun setOriginalSpeed(value: Float) { originalSpeed = value; changed() }
    fun seekTo(id: String, source: File, target: Long) {
        owner = id; position = target.toInt().coerceIn(0, duration); changed()
    }
    fun play(id: String, source: File) { owner = id; if (position >= duration) position = 0; playing = true; changed() }
    fun advance(elapsed: Long) {
        val delta = (elapsed - lastElapsed).coerceAtLeast(0)
        lastElapsed = elapsed
        if (playing) {
            position = (position + (delta * originalSpeed).toInt()).coerceAtMost(duration)
            if (position == duration) playing = false
            changed()
        }
    }
    private fun changed() { observers.toList().forEach { it() } }
    companion object {
        private val singleton = ChatAudioPlayback()
        fun get(context: Context) = singleton
    }
}

internal object SubtitleOverlayController {
    fun panelVisible(activity: Activity, token: Any, owner: String, visible: Boolean) = Unit
    // Overlay permission is deliberately absent; this preview demonstrates the real full panel.
    fun restore(context: Context) = false
}
