package com.alwayside.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.io.File

/** One application-owned player. Screens subscribe without owning its lifetime. Main thread only. */
internal class ChatAudioPlayback private constructor(private val context: Context) {
    internal data class State(
        val preparing: Boolean = false,
        val playing: Boolean = false,
        val remaining: Float? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = InteractionPreferences(context)
    private val visibility = PlaybackVisibility(preferences.load().backgroundPlayback)
    private val observers = mutableSetOf<() -> Unit>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause(); abandonFocus() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    resumeOnFocus = resumeOnFocus || state.playing
                    pause(temporary = true)
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; hasFocus = true; startOrResume() }
            }
        }, handler).build()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "backgroundPlayback") {
            visibility.backgroundPlayback = preferences.load().backgroundPlayback
            if (visibility.shouldStop(conversationId)) stop()
            else if (conversationId != null) {
                if (visibility.backgroundPlayback || readingKey.isNotEmpty()) requestService() else {
                    releaseService()
                    if (waitingForService) { waitingForService = false; startOrResume() }
                }
            }
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            if (intent.action == Intent.ACTION_SCREEN_OFF && !visibility.backgroundPlayback) stop()
        }
    }
    private var player: MediaPlayer? = null
    private var pendingSeek: Int? = null
    var readingKey: String = ""; private set
    var mediaTitle: String = "原始音频"; private set
    var speed: Float = 1f; private set
    private var readingSpeed = 1f
    val currentFile: File? get() = file
    private var file: File? = null
    private var deleteWhenStopped = false
    private var resumeOnFocus = false
    private var hasFocus = false
    private var waitingForService = false
    private var serviceRequested = false
    private var service: AudioPlaybackService? = null
    private var state = State()
    var conversationId: String? = null
        private set
    var duration: Int = 0
        private set
    val position: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val currentState: State get() = state

    init {
        preferences.addListener(preferenceListener)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(noisyReceiver, filter)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!state.playing) return
            try {
                updatePosition(current, playing = true)
                handler.postDelayed(this, if (observers.isEmpty()) 1000 else 50)
            } catch (_: Exception) { failed() }
        }
    }

    fun stateFor(id: String): State = if (conversationId == id && readingKey.isEmpty()) state else State()
    fun isActive(id: String): Boolean = conversationId == id
    fun fileFor(id: String): File? = file?.takeIf { conversationId == id && readingKey.isEmpty() && it.isFile }
    fun mayPlay(id: String): Boolean = !visibility.shouldStop(id)
    fun addObserver(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    fun setVisible(owner: Any, id: String?, visible: Boolean, retaining: Boolean = false) {
        visibility.update(owner, id, visible)
        if (!retaining && visibility.shouldStop(conversationId)) stop()
        if (!retaining) PointReadingController.hiddenIfCreated()
    }

    fun play(id: String, source: File) {
        PointReadingController.stopIfCreated()
        stop(releaseHosting = false)
        speed = 1f
        conversationId = id
        file = source
        publish(State(preparing = true, remaining = 1f))
        startOrResume()
    }

    fun playReading(id: String, source: File, key: String, text: String) {
        stop(releaseHosting = false)
        speed = readingSpeed; readingKey = key; mediaTitle = "点读 · ${text.take(60)}"
        conversationId = id; file = source
        publish(State(preparing = true, remaining = 1f))
        startOrResume()
    }

    fun setReadingSpeed(value: Float) {
        if (readingKey.isEmpty() || value !in listOf(0.8f, 1f, 1.2f)) return
        speed = value; readingSpeed = value
        if (player != null && !state.preparing) runCatching {
            player?.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
            if (!state.playing) player?.pause()
        }
        publish(state)
        service?.updatePlayback()
    }

    fun toggle() {
        if (state.preparing || conversationId == null) return
        if (state.playing) pause() else startOrResume()
    }

    fun resume() { if (!state.playing && !state.preparing && conversationId != null) startOrResume() }

    /** Subtitles and the slider seek the original recording; reading audio has separate ownership. */
    fun seekTo(id: String, source: File, positionMs: Long) {
        if (conversationId != id || readingKey.isNotEmpty()) play(id, source)
        val target = positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (state.preparing || player == null) { pendingSeek = target; return }
        val current = player ?: return
        runCatching {
            current.seekTo(target.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST)
        }.onFailure { failed() }
    }

    private fun startOrResume() {
        if ((visibility.backgroundPlayback || readingKey.isNotEmpty()) && service?.hosting != true) {
            waitingForService = true
            requestService()
            return
        }
        waitingForService = false
        if (player == null) prepare() else startPrepared()
    }

    private fun prepare() {
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            // MediaPlayer duplicates the descriptor, so async prepare is safe after this stream closes.
            requireNotNull(file).inputStream().use { next.setDataSource(it.fd) }
            next.setOnPreparedListener { current ->
                if (player === current) {
                    duration = current.duration
                    if (duration > 0) startPrepared() else failed()
                }
            }
            next.setOnCompletionListener { if (player === it) stop() }
            next.setOnSeekCompleteListener { if (player === it) updatePosition(it, state.playing) }
            next.setOnErrorListener { current, _, _ -> if (player === current) failed(); true }
            next.prepareAsync()
        } catch (_: Exception) { failed() }
    }

    private fun startPrepared() {
        val current = player ?: return
        try {
            if (!hasFocus) {
                hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!hasFocus) { stop(); toast("暂时无法播放，请在其他音频结束后重试"); return }
            }
            if (readingKey.isNotEmpty()) current.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
            pendingSeek?.let { current.seekTo(it.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST); pendingSeek = null }
            current.start()
            updatePosition(current, playing = true)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (_: Exception) { failed() }
    }

    fun pause(temporary: Boolean = false) {
        if (!temporary) resumeOnFocus = false
        if (state.preparing) { stop(); return }
        val current = player ?: return
        try {
            if (state.playing) {
                current.pause()
                handler.removeCallbacks(ticker)
                updatePosition(current, playing = false)
            }
            if (!temporary) {
                abandonFocus()
                service?.pauseHosting()
            }
        } catch (_: Exception) { failed() }
    }

    fun stop(releaseHosting: Boolean = true) {
        handler.removeCallbacks(ticker)
        val previous = player
        player = null // Ignore callbacks from the released player.
        pendingSeek = null
        waitingForService = false
        resumeOnFocus = false
        abandonFocus()
        runCatching { previous?.release() }
        if (deleteWhenStopped) file?.delete()
        file = null; deleteWhenStopped = false
        conversationId = null; duration = 0
        readingKey = ""; mediaTitle = "原始音频"
        publish(State())
        if (releaseHosting) releaseService()
    }

    private fun abandonFocus() {
        if (hasFocus) { hasFocus = false; audioManager.abandonAudioFocusRequest(focus) }
    }

    private fun requestService() {
        if (serviceRequested || conversationId == null) return
        serviceRequested = true
        try { context.startForegroundService(Intent(context, AudioPlaybackService::class.java)) }
        catch (_: RuntimeException) { serviceRequested = false; stop(); toast("无法启动后台播放，请返回聊天后重试") }
    }

    /** Called only after the mediaPlayback service has entered the foreground (before audio focus). */
    fun serviceReady(host: AudioPlaybackService): Boolean {
        serviceRequested = false
        if (conversationId == null || (!visibility.backgroundPlayback && readingKey.isEmpty())) return false
        service = host
        if (waitingForService) startOrResume()
        return conversationId != null
    }

    fun serviceDestroyed(host: AudioPlaybackService) {
        if (service === host) { service = null; stop() }
    }

    private fun releaseService() {
        val previous = service
        service = null
        previous?.finishHosting()
    }

    private fun updatePosition(current: MediaPlayer, playing: Boolean) {
        val remaining = 1f - current.currentPosition.coerceIn(0, duration).toFloat() / duration
        publish(State(playing = playing, remaining = remaining))
    }

    private fun publish(next: State) {
        val changed = state.preparing != next.preparing || state.playing != next.playing ||
            (state.remaining == null) != (next.remaining == null)
        state = next
        observers.toList().forEach { it() }
        if (changed) service?.updatePlayback()
    }

    private fun failed() { stop(); toast("无法回听，音频可能已被删除或暂不可读") }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile private var instance: ChatAudioPlayback? = null
        fun get(context: Context): ChatAudioPlayback = instance ?: ChatAudioPlayback(context.applicationContext).also { instance = it }

        fun isUsingFile(file: File): Boolean = instance?.file?.absolutePath == file.absolutePath

        /** Temporary audio can outlive the overlay/chat that originally owned it. */
        fun deleteTemporaryAudio(file: File) {
            val current = instance
            if (isUsingFile(file)) current?.deleteWhenStopped = true else file.delete()
        }

        fun stopConversation(id: String? = null) {
            val current = instance ?: return
            val action = Runnable { if (id == null || current.conversationId == id) current.stop() }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else current.handler.post(action)
        }
    }
}
