package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Created only by an enabled experiment's explicit action. All playback intent lives on main. */
internal class PointReadingController private constructor(private val context: Context) {
    data class State(val owner: String = "", val origin: ReadingOrigin = ReadingOrigin.CHAT,
        val text: String = "", val key: String = "", val generating: Boolean = false,
        val saved: Boolean = false, val error: String = "")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = InteractionPreferences(context)
    private val playback = ChatAudioPlayback.get(context)
    val cache = cacheFor(context)
    private var epoch = 0L
    private var cancellation: RequestCancellation? = null
    private var file: File? = null
    private var temporary: File? = null
    private val observers = mutableSetOf<() -> Unit>()
    var state = State(); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "chatPointReading" || key == "imagePointReading") {
            if (state.text.isNotEmpty() && !preferences.load().readingEnabled(state.origin)) stop()
            else publish()
        }
    }
    init { preferences.addListener(listener) }
    fun addObserver(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    private fun publish() = observers.toList().forEach { it() }

    fun read(owner: String, origin: ReadingOrigin, selected: String, toggle: Boolean = true) {
        if (!preferences.load().readingEnabled(origin)) return
        if (ProbeState.recording) { toast("请先结束内部录音，再使用点读"); return }
        val text = normalizeReadingText(selected)
        if (text.isBlank()) return
        if (text.length > 3500) { toast("点读内容过长，请选取较短的一段"); return }
        val voice = ReadingPreferences(context).voice()
        val key = voice.key(text)
        if (toggle && state.owner == owner && state.origin == origin && state.key == key) {
            if (state.generating) return // Repeated taps never duplicate a paid request.
            if (playback.readingKey == key && playback.isActive(owner)) { playback.toggle(); return }
            file?.takeIf(File::isFile)?.let { playback.playReading(owner, it, key, text); return }
        }
        stop()
        val token = ++epoch
        val cancel = RequestCancellation().also { cancellation = it }
        val ticket = cache.ticket(owner)
        state = State(owner, origin, text, key, generating = true)
        publish()
        worker.execute {
            var disposable: File? = null
            try {
                cancel.throwIfCancelled()
                var stored = cache.get(key, owner, ticket)
                if (stored != null && !validAudio(stored)) { cache.discard(key); stored = null }
                val saved: Boolean
                val target: File
                if (stored != null) { saved = true; target = stored }
                else {
                    val configuration = ReadingPreferences(context).request(context)
                    require(ReadingPreferences(context).voice() == voice) { "朗读配置已改变，请重试" }
                    val bytes = synthesize(configuration, voice, text, cancel)
                    cancel.throwIfCancelled()
                    disposable = File.createTempFile("point-reading-", ".mp3", context.cacheDir)
                    disposable.writeBytes(bytes)
                    require(validAudio(disposable)) { "服务未返回可播放的音频，请检查朗读接口与模型" }
                    val favorites = HistoryStore(context).index().conversations.filter { it.isFavorite }.map { it.id }.toSet()
                    cancel.throwIfCancelled()
                    require(cache.current(owner, ticket)) { "会话已删除，点读已取消" }
                    val archive = runCatching { cache.put(key, owner, ticket, bytes, favorites, playback.currentFile) }.getOrNull()
                    saved = archive != null
                    target = archive ?: disposable
                    if (saved) { disposable.delete(); disposable = null }
                }
                val transient = disposable
                main.post {
                    if (epoch != token || cancel.isCancelled || !preferences.load().readingEnabled(origin) ||
                        !cache.current(owner, ticket) || !playback.mayPlay(owner)) {
                        transient?.delete()
                    } else {
                        cancellation = null; file = target; temporary = transient
                        state = state.copy(generating = false, saved = saved)
                        publish()
                        playback.playReading(owner, target, key, text)
                        if (!saved) toast("本次音频未长期保存，请检查存储空间或清理点读缓存")
                    }
                }
            } catch (_: java.util.concurrent.CancellationException) {
                disposable?.delete()
                main.post {
                    if(epoch==token) {
                        cancellation=null;state=state.copy(generating=false,error="朗读已取消，请重试")
                        publish()
                    }
                }
            }
            catch (error: Exception) {
                disposable?.delete()
                main.post {
                    if (epoch == token && !cancel.isCancelled) {
                        cancellation = null
                        val message = when (error) {
                            is java.io.IOException -> "朗读请求失败，请检查网络后手动重试"
                            is ProviderException, is IllegalArgumentException, is IllegalStateException -> error.message.orEmpty().take(180)
                            else -> "朗读失败，请检查朗读服务配置后重试"
                        }
                        state = state.copy(generating = false, error = message)
                        publish(); toast(message)
                    }
                }
            }
        }
    }

    fun replay() {
        if (state.text.isNotBlank() && !state.generating) {
            if (ProbeState.recording) { toast("请先结束内部录音"); return }
            val source = file?.takeIf(File::isFile)
            if (source != null && preferences.load().readingEnabled(state.origin))
                playback.playReading(state.owner, source, state.key, state.text)
            else read(state.owner, state.origin, state.text, toggle = false)
        }
    }
    fun stop() {
        epoch++; cancellation?.cancel(); cancellation = null
        if (playback.readingKey.isNotEmpty()) playback.stop()
        temporary?.let { ChatAudioPlayback.deleteTemporaryAudio(it) }; temporary = null; file = null
        state = State(); publish()
    }
    fun hidden(owner: String) {
        if (state.owner == owner && !playback.mayPlay(owner)) stop()
    }
    fun deleted(owner: String) {
        cache.removeOwner(owner)
        main.post { if (state.owner == owner) stop() }
    }
    fun clear(keepFavorites: Boolean, finished: () -> Unit = {}) {
        stop()
        worker.execute {
            val success=runCatching {
                val protected = if (keepFavorites) HistoryStore(context).index().conversations.filter { it.isFavorite }.map { it.id }.toSet() else emptySet()
                cache.clear(protected)
            }.isSuccess
            main.post { if(!success)toast("无法清理点读缓存，请重试");finished() }
        }
    }

    private fun synthesize(config: AppConfig, voice: ReadingVoice, text: String, cancel: RequestCancellation): ByteArray {
        require(config.apiKey.length <= 4096 && config.apiKey.all { it.code in 33..126 }) { "朗读 Key 格式无效" }
        val body = WireJson.encode(mapOf("model" to voice.model, "voice" to voice.voice,
            "input" to text, "response_format" to "mp3")).toByteArray(Charsets.UTF_8)
        return HttpTransport().request(config, "audio/speech", "application/json", body.size.toLong(), cancel,
            { it.write(body) }, accept = "audio/mpeg") { connection, deadline ->
            require(connection.contentType.orEmpty().substringBefore(';').trim().lowercase() in
                setOf("audio/mpeg", "audio/mp3", "application/octet-stream")) { "朗读接口没有返回 MP3 音频" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    cancel.throwIfCancelled()
                    require(System.nanoTime() <= deadline) { "朗读请求超时，请手动重试" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= PointReadingCache.MAX_AUDIO_BYTES) { "朗读音频过大，请缩短内容" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
    private fun validAudio(source: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.path)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } catch (_: Exception) { false } finally { runCatching { retriever.release() } }
    }
    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    companion object {
        // get() always supplies applicationContext; no Activity or overlay is retained here.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: PointReadingController? = null
        private val stores = java.util.concurrent.ConcurrentHashMap<String,PointReadingCache>()
        private fun cacheFor(context: Context): PointReadingCache {
            val directory=File(context.noBackupFilesDir,"point_reading_audio")
            return stores.computeIfAbsent(directory.absolutePath) { PointReadingCache(directory) }
        }
        fun get(context: Context): PointReadingController = instance ?: PointReadingController(context.applicationContext).also { instance = it }
        fun stopIfCreated() { instance?.stop() }
        fun stopOriginIfCreated(owner: String, origin: ReadingOrigin) {
            instance?.let { if(it.state.owner==owner && it.state.origin==origin)it.stop() }
        }
        fun hiddenIfCreated() { instance?.let { it.hidden(it.state.owner) } }
        fun delete(context: Context, owner: String) {
            instance?.let { it.deleted(owner); return }
            cacheFor(context).removeOwner(owner)
        }
        fun clearAll(context: Context) {
            instance?.let { current -> current.main.post { current.stop() }; current.cache.clear(); return }
            cacheFor(context).clear()
        }
    }
}
