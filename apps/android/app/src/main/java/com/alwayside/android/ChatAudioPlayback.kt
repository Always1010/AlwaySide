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
import java.util.concurrent.Executors

/** One application-owned player. Screens subscribe without owning its lifetime. Main thread only. */
internal class ChatAudioPlayback private constructor(private val context: Context) {
    internal data class State(
        val preparing: Boolean = false,
        val playing: Boolean = false,
        val remaining: Float? = null,
        val completed: Boolean = false,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = InteractionPreferences(context)
    private val controls = context.getSharedPreferences("audio_playback_controls", Context.MODE_PRIVATE)
    var repeatMode = AudioPlaybackMode.restore(controls.getString("singleRepeat", null), false); private set
    var queue: AudioPlaybackQueue? = null; private set
    val listPlayback: Boolean get() = queue != null
    val sourceTitle: String get() = queue?.let { "${it.source} · ${it.index + 1}/${it.items.size}" } ?: "单段播放"
    val canGoNext: Boolean get() = queue?.target(repeatMode) != null
    val canGoPrevious: Boolean get() = queue?.target(repeatMode, delta = -1) != null
    private val loader = Executors.newSingleThreadExecutor()
    @Volatile private var loadVersion = 0L
    private var loadingFile = false
    private var playWhenReady = true
    private val visibility = PlaybackVisibility(preferences.load().backgroundPlayback)
    private val usageToken = Any()
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
    var originalSpeed: Float = 1f; private set
    var playbackSession: Long = 0; private set
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
    val position: Int get() = if (state.completed) duration else runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val currentState: State get() = state

    fun refreshMediaControls() {
        observers.toList().forEach { it() }
        service?.updatePlayback()
    }

    fun setRepeatMode(value: AudioRepeat) {
        if (readingKey.isNotEmpty() || value !in AudioPlaybackMode.options(listPlayback)) return
        repeatMode = value
        controls.edit().putString(if (listPlayback) "queueRepeat" else "singleRepeat", value.name).apply()
        refreshMediaControls()
    }

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
                handler.postDelayed(this, if (observers.isNotEmpty()) 50 else if (SubtitleOverlayController.tracking) 100 else 1000)
            } catch (_: Exception) { failed() }
        }
    }

    fun stateFor(id: String): State = if (conversationId == id && readingKey.isEmpty()) state else State()
    fun isActive(id: String): Boolean = conversationId == id
    fun fileFor(id: String): File? = file?.takeIf { conversationId == id && readingKey.isEmpty() && it.isFile }
    fun mayPlay(id: String): Boolean = !visibility.shouldStop(id, allowLibrary = false)
    fun addObserver(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    fun setVisible(owner: Any, id: String?, visible: Boolean, retaining: Boolean = false, originalHost: Boolean = false) {
        ConversationUsage.update(owner, id.takeIf { visible })
        visibility.update(owner, id, visible)
        visibility.updatePlaybackHost(owner, visible && originalHost)
        if (!retaining && visibility.shouldStop(conversationId)) stop()
        if (!retaining) PointReadingController.hiddenIfCreated()
    }

    fun setLibraryVisible(owner: Any, visible: Boolean, retaining: Boolean = false) {
        visibility.updateLibrary(owner, visible)
        if (!retaining && visibility.shouldStop(conversationId)) stop()
        if (!retaining) PointReadingController.hiddenIfCreated()
    }

    fun play(id: String, source: File) {
        val index = queue?.items?.indexOfFirst { it.id == id } ?: -1
        if (index >= 0) { selectQueueItem(index, source); return }
        selectOriginal(id, source)
        startOrResume()
    }

    fun playQueue(items: List<AudioQueueItem>, source: String) {
        if (items.isEmpty()) { toast("此列表没有可播放的录音"); return }
        PointReadingController.stopIfCreated()
        stop(releaseHosting = false)
        queue = AudioPlaybackQueue(items, source)
        repeatMode = AudioPlaybackMode.restore(controls.getString("queueRepeat", null), true)
        selectQueueItem(0)
    }

    fun next() { queue?.target(repeatMode)?.let { selectQueueItem(it) } }
    fun previous() { queue?.target(repeatMode, delta = -1)?.let { selectQueueItem(it) } }
    fun selectQueueItem(index: Int, source: File? = null) {
        val selected = queue ?: return
        if (index !in selected.items.indices) return
        releaseTrack(abandon = false)
        selected.select(index)
        val item = requireNotNull(selected.current)
        conversationId = item.id; mediaTitle = item.title; readingKey = ""; speed = originalSpeed
        playbackSession++
        playWhenReady = true
        val version = loadVersion
        loadingFile = source == null
        file = source
        publish(State(preparing = true, remaining = 1f))
        service?.updatePlayback()
        if (source != null) { startOrResume(); return }
        // Protect only the selected item, never the entire snapshot; resolve files off the UI thread.
        loader.execute {
            if (version != loadVersion) return@execute
            val resolved = runCatching {
                val summary = HistoryStore(context).summary(item.id) ?: return@runCatching null
                ConversationMediaStore(context).loadAudio(item.id)?.let { it to summary.title }
            }.getOrNull()
            handler.post {
                if (version != loadVersion || queue !== selected || conversationId != item.id) return@post
                loadingFile = false
                if (resolved == null) { failed(); return@post }
                file = resolved.first
                mediaTitle = resolved.second.ifBlank { item.title }
                if (playWhenReady) startOrResume() else prepare(startPlaying = false)
            }
        }
    }

    /** Opening captions prepares the recording without starting sound or requesting audio focus. */
    fun prepareForSubtitles(id: String, source: File) {
        if (conversationId == id && readingKey.isEmpty()) return
        selectOriginal(id, source)
        prepare(startPlaying = false)
    }

    private fun selectOriginal(id: String, source: File) {
        PointReadingController.stopIfCreated()
        stop(releaseHosting = false)
        playbackSession++
        speed = originalSpeed
        repeatMode = AudioPlaybackMode.restore(controls.getString("singleRepeat", null), false)
        conversationId = id
        mediaTitle = HistoryStore(context).summary(id)?.title?.takeIf { it.isNotBlank() } ?: "原始音频"
        file = source
        publish(State(preparing = true, remaining = 1f))
    }

    fun playReading(id: String, source: File, key: String, text: String) {
        stop(releaseHosting = false)
        playbackSession++
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

    fun setOriginalSpeed(value: Float) {
        if (value !in listOf(.75f, 1f, 1.25f, 1.5f, 2f)) return
        originalSpeed = value
        if (readingKey.isNotEmpty()) return
        val previous = speed
        speed = value
        runCatching {
            if (player != null && !state.preparing) {
                player?.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
                if (!state.playing) player?.pause()
            }
        }.onFailure { speed = previous; originalSpeed = previous; toast("当前音频无法调整播放速度") }
        refreshMediaControls()
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
        if (state.preparing || player == null) {
            pendingSeek = target
            if (state.completed) startOrResume()
            return
        }
        val current = player ?: return
        runCatching {
            current.seekTo(target.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST)
        }.onFailure { failed() }
    }

    private fun startOrResume() {
        if (loadingFile) return
        if (state.completed) {
            if (queue != null && repeatMode != AudioRepeat.ONE && pendingSeek == null) { selectQueueItem(0); return }
            if (file?.isFile != true) { failed(); return }
            playbackSession++
            publish(State(preparing = true, remaining = 1f))
        }
        if ((visibility.backgroundPlayback || readingKey.isNotEmpty()) && service?.hosting != true) {
            waitingForService = true
            requestService()
            return
        }
        waitingForService = false
        if (player == null) prepare() else startPrepared()
    }

    private fun prepare(startPlaying: Boolean = true) {
        try {
            playWhenReady = startPlaying
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            // MediaPlayer duplicates the descriptor, so async prepare is safe after this stream closes.
            requireNotNull(file).inputStream().use { next.setDataSource(it.fd) }
            next.setOnPreparedListener { current ->
                if (player === current) {
                    duration = current.duration
                    if (duration > 0) queue?.acceptCurrent()
                    if (duration <= 0) failed()
                    else if (playWhenReady) startPrepared()
                    else {
                        pendingSeek?.let { current.seekTo(it.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST); pendingSeek = null }
                        updatePosition(current, playing = false)
                        service?.pauseHosting()
                    }
                }
            }
            next.setOnCompletionListener { if (player === it) completed() }
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
            if (readingKey.isNotEmpty() || speed != 1f) current.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
            pendingSeek?.let { current.seekTo(it.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST); pendingSeek = null }
            current.start()
            updatePosition(current, playing = true)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (_: Exception) { failed() }
    }

    fun pause(temporary: Boolean = false) {
        if (!temporary) resumeOnFocus = false
        if (state.preparing) {
            playWhenReady = false
            waitingForService = false
            // A late foreground-service callback must not undo an explicit pause while loading.
            if (!loadingFile && player == null && file != null) prepare(startPlaying = false)
            if (!temporary) { abandonFocus(); service?.pauseHosting() }
            return
        }
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
        releaseTrack()
        queue = null
        conversationId = null
        readingKey = ""; mediaTitle = "原始音频"
        publish(State())
        if (releaseHosting) releaseService()
    }

    private fun releaseTrack(abandon: Boolean = true) {
        loadVersion++
        loadingFile = false
        handler.removeCallbacks(ticker)
        val previous = player
        player = null // Ignore callbacks from the released player.
        pendingSeek = null
        waitingForService = false
        resumeOnFocus = false
        if (abandon) abandonFocus()
        runCatching { previous?.release() }
        if (deleteWhenStopped) file?.delete()
        file = null; deleteWhenStopped = false
        duration = 0
    }

    private fun completed() {
        if (readingKey.isNotEmpty()) { stop(); return }
        val next = queue?.target(repeatMode, automatic = true)
        if (repeatMode == AudioRepeat.ONE || (next != null && next == queue?.index)) {
            // Keep the subtitle session: an explicitly dismissed caption must stay dismissed.
            pendingSeek = 0
            startPrepared()
            return
        }
        if (next != null) { selectQueueItem(next); return }
        handler.removeCallbacks(ticker)
        val previous = player
        player = null
        runCatching { previous?.release() }
        abandonFocus()
        publish(State(remaining = 0f, completed = true))
        releaseService()
    }

    private fun abandonFocus() {
        if (hasFocus) { hasFocus = false; audioManager.abandonAudioFocusRequest(focus) }
    }

    private fun requestService() {
        if (serviceRequested || conversationId == null || state.completed) return
        serviceRequested = true
        try { context.startForegroundService(Intent(context, AudioPlaybackService::class.java)) }
        catch (_: RuntimeException) { serviceRequested = false; stop(); toast("无法启动后台播放，请返回聊天后重试") }
    }

    /** Called only after the mediaPlayback service has entered the foreground (before audio focus). */
    fun serviceReady(host: AudioPlaybackService): Boolean {
        serviceRequested = false
        if (conversationId == null || state.completed || (!visibility.backgroundPlayback && readingKey.isEmpty())) return false
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
        ConversationUsage.update(usageToken, conversationId.takeIf { (file != null || next.preparing) && !next.completed })
        val changed = state.preparing != next.preparing || state.playing != next.playing ||
            (state.remaining == null) != (next.remaining == null)
        state = next
        observers.toList().forEach { it() }
        // Caption window failures must never fail or stop the audio transport.
        runCatching { SubtitleOverlayController.playbackChanged(context) }
        if (changed) service?.updatePlayback()
    }

    private fun failed() {
        val selected = queue
        if (selected != null) {
            selected.rejectCurrent()
            val next = selected.target(if (repeatMode == AudioRepeat.ALL) AudioRepeat.ALL else AudioRepeat.NONE)
            if (next != null) {
                toast("已跳过不可播放的录音")
                // Queue a handoff so even immediate decoder failures cannot recurse through the snapshot.
                val version = loadVersion
                handler.post { if (queue === selected && loadVersion == version) selectQueueItem(next) }
                return
            }
        }
        stop(); toast("无法回听，音频可能已被删除或暂不可读")
    }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile private var instance: ChatAudioPlayback? = null
        fun get(context: Context): ChatAudioPlayback = instance ?: ChatAudioPlayback(context.applicationContext).also { instance = it }

        fun handoffVisibility(action: () -> Unit) {
            val current = instance
            if (current == null) { action(); return }
            try { current.visibility.handoff(action) } finally {
                if (current.visibility.shouldStop(current.conversationId)) current.stop()
                PointReadingController.hiddenIfCreated()
            }
        }

        fun isUsingFile(file: File): Boolean = instance?.let { !it.state.completed && it.file?.absolutePath == file.absolutePath } == true

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
