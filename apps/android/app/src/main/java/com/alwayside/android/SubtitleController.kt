package com.alwayside.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture

/** Explicit subtitle actions and opted-in audio questions share one job per recording. */
internal class SubtitleController private constructor(private val context: Context) {
    internal data class Key(val owner: String, val method: SubtitleMethod)
    internal data class Record(
        val published: SubtitleBundle? = null,
        val draft: SubtitleBundle? = null,
        val busy: Boolean = false,
        val status: String = "尚未制作",
        val error: String = "",
        val loaded: Boolean = false,
    ) {
        val playable: SubtitleBundle? get() = published ?: draft?.takeIf { it.transcriptionComplete }
    }
    private data class Job(val key: Key, val input: InputStream, val length: Long,
        val config: AppConfig, val recreate: Boolean, val revision: Long,
        val cancellation: RequestCancellation = RequestCancellation(),
        val batchedTranslation: Boolean = false,
        val original: CompletableFuture<SubtitleBundle> = CompletableFuture())

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val translator = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadExecutor()
    private val preferences = SubtitlePreferences(context)
    private val archive = archiveFor(context)
    private val records = linkedMapOf<Key, Record>()
    private val observers = mutableSetOf<() -> Unit>()
    private val jobs = ConcurrentHashMap<Key, Job>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        jobs.values.filter { !preferences.enabled(it.key.method) || !configurationCurrent(it) }
            .forEach { it.cancellation.cancel() }
        publish()
    }
    private val apiListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        jobs.values.filterNot(::configurationCurrent).forEach { it.cancellation.cancel() }
    }
    init {
        preferences.addListener(listener)
        context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(apiListener)
    }

    fun record(owner: String, method: SubtitleMethod) = records[Key(owner, method)] ?: Record()
    fun addObserver(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    private fun publish() { observers.toList().forEach { it() } }

    fun refresh(owner: String) {
        SubtitleMethod.entries.forEach { method ->
            val key = Key(owner, method)
            if (jobs.containsKey(key)) return@forEach
            val ticket = synchronized(fileLock) { revision(key) }
            reader.execute {
                val result = runCatching { archive.load(owner, method) to archive.loadDraft(owner, method) }
                main.post {
                    if (jobs.containsKey(key) || synchronized(fileLock) { revision(key) } != ticket) return@post
                    records[key] = result.fold({ (saved, draft) ->
                        Record(saved, draft, status = when {
                            draft != null -> if (draft.transcriptionComplete) "英文已保存，可继续中文翻译" else "制作已中断，可继续"
                            saved != null -> "双语字幕已保存"
                            else -> "尚未制作"
                        }, loaded = true)
                    }, { Record(status = "无法读取字幕", error = "字幕文件无法读取，请重试或重新制作", loaded = true) })
                    trimRecords(); publish()
                }
            }
        }
    }

    /** Opening the input now also keeps temporary audio readable if its original owner closes. */
    fun enqueue(snapshot: StoredConversation, source: File, method: SubtitleMethod, recreate: Boolean = false) {
        enqueueJob(snapshot, source, method, recreate, preferences.request())
    }

    private fun enqueueJob(snapshot: StoredConversation, source: File, method: SubtitleMethod,
        recreate: Boolean, config: AppConfig, batchedTranslation: Boolean = preferences.batchedTranslation()): Job {
        synchronized(ConversationUsage.lock) {
        check(!StorageRuntime.get(context).catalog.isDeleted(snapshot.id)) { "这段会话已删除" }
        check(preferences.enabled(method)) { "请先在实验室开启此字幕方案" }
        val key = Key(snapshot.id, method)
        check(!jobs.containsKey(key)) { "此字幕正在制作，请勿重复提交" }
        check(jobs.size < 8) { "已有较多字幕任务，请等待完成后再添加" }
        require(source.isFile && source.length() > 44) { "原始音频不可用，无法制作字幕" }
        val history = HistoryStore(context)
        if (history.summary(snapshot.id) == null) history.save(snapshot)
        val input = source.inputStream()
        val job = Job(key, input, source.length(), config, recreate,
            synchronized(fileLock) { invalidate(key); revision(key) }, batchedTranslation = batchedTranslation)
        jobs[key] = job
        ConversationUsage.update(job, key.owner)
        records[key] = record(key.owner, method).copy(busy = true, status = "已排队", error = "", loaded = true)
        publish()
        worker.execute { execute(job) }
        return job
        }
    }

    /** Capture settings at submit time; no upload starts until the chat worker asks for the original. */
    fun questionRequest(snapshot: StoredConversation, source: File): AudioQuestionSubtitles? {
        if (!preferences.questionSubtitles()) return null
        check(preferences.enabled(SubtitleMethod.WHISPER)) { "请先在实验室开启 Whisper，或关闭音频提问同时生成字幕" }
        val config = preferences.request()
        val batched = preferences.batchedTranslation()
        return object : AudioQuestionSubtitles {
            override val cacheKey = digest(listOf(config.credentialId, config.credentialRevision.toString(),
                config.endpointId, SubtitleEngine.VERSION).joinToString("\u0000"))
            override fun isCurrent() = preferences.questionSubtitles() && preferences.enabled(SubtitleMethod.WHISPER) &&
                runCatching { val current = preferences.request()
                    current.credentialId == config.credentialId && current.credentialRevision == config.credentialRevision &&
                        current.endpointId == config.endpointId && current.apiKey == config.apiKey &&
                        current.model == config.model && current.apiProtocol == config.apiProtocol
                }.getOrDefault(false)

            override fun transcribe(cancellation: RequestCancellation): String {
                val selected = CompletableFuture<Job>()
                main.post {
                    try {
                        cancellation.throwIfCancelled()
                        check(isCurrent()) { "字幕设置已改变，请重新发送问题" }
                        val key = Key(snapshot.id, SubtitleMethod.WHISPER)
                        val existing = jobs[key]
                        val job = if (existing != null) {
                            check(!existing.cancellation.isCancelled && existing.config == config) { "此录音的字幕任务正在结束或配置不同，请稍后重试" }
                            existing
                        } else enqueueJob(snapshot, source, SubtitleMethod.WHISPER, false, config,
                            batchedTranslation = batched)
                        selected.complete(job)
                    } catch (error: Exception) { selected.completeExceptionally(error) }
                }
                val enabled = { preferences.questionSubtitles() && preferences.enabled(SubtitleMethod.WHISPER) }
                val job = try { awaitSubtitleOriginal(selected, cancellation, enabled) }
                catch (error: Exception) {
                    selected.thenAccept { if (cancellation.isCancelled || Thread.currentThread().isInterrupted || !isCurrent()) it.cancellation.cancel() }
                    throw error
                }
                return try { SubtitleQuestionTranscript.text(
                    awaitSubtitleOriginal(job.original, cancellation) { enabled() && !job.cancellation.isCancelled }) }
                catch (error: Exception) {
                    if (cancellation.isCancelled || Thread.currentThread().isInterrupted || !isCurrent()) job.cancellation.cancel()
                    throw error
                }
            }
        }
    }

    fun cancel(owner: String, method: SubtitleMethod) {
        jobs[Key(owner, method)]?.cancellation?.cancel()
    }

    fun delete(owner: String, method: SubtitleMethod) {
        val key = Key(owner, method)
        jobs[key]?.cancellation?.cancel()
        synchronized(fileLock) { invalidate(key); archive.delete(owner, method) }
        records[key] = Record(loaded = true)
        publish()
    }

    private fun execute(job: Job) {
        var lastSaved: SubtitleBundle? = null
        try {
            checkCurrent(job)
            update(job, "正在保留本段录音…")
            // Source retention is explicitly included in the generation action, independent of the global switch.
            job.input.use { input ->
                val store = ConversationMediaStore(context)
                synchronized(fileLock) {
                    checkCurrent(job)
                    if (store.loadAudio(job.key.owner) == null) {
                        store.saveAudio(job.key.owner, object : InputStream() {
                            override fun read(): Int { checkCurrent(job); return input.read() }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                checkCurrent(job); return input.read(b, off, len)
                            }
                        }, job.length)
                    }
                }
            }
            val file = ConversationMediaStore(context).loadAudio(job.key.owner) ?: error("原始音频已被删除")
            val audioHash = audioHash(file, job.cancellation)
            val duration = (file.length() - 44) * 1000 / (RecordingLimits.SAMPLE_RATE * 2)
            val signature = digest(listOf(audioHash, job.key.method.model, SubtitleEngine.VERSION,
                job.config.credentialId, job.config.credentialRevision.toString(), job.config.endpointId).joinToString("\u0000"))
            val draft = if (!job.recreate) archive.loadDraft(job.key.owner, job.key.method) else null
            val saved = if (!job.recreate) archive.load(job.key.owner, job.key.method) else null
            var bundle = listOfNotNull(draft, saved).firstOrNull { it.audioHash == audioHash && it.requestSignature == signature }
                ?: SubtitleBundle(audioHash, job.key.method, duration, signature)
            lastSaved = bundle
            if (bundle.translationModel != job.config.model) bundle = bundle.copy(translations = emptyMap(), translationModel = job.config.model)
            if (!bundle.transcriptionComplete) {
                update(job, "正在分析音频…", bundle)
                val engine = SubtitleEngine()
                val parts = engine.parts(file, job.cancellation)
                require(parts.isNotEmpty()) { "未检测到可用语音，请检查录音" }
                require(bundle.completedParts in 0..parts.size && (bundle.totalParts == 0 || bundle.totalParts == parts.size)) {
                    "字幕分片记录不匹配，请重新制作"
                }
                bundle = bundle.copy(totalParts = parts.size, translationModel = job.config.model)
                for (index in bundle.completedParts until parts.size) {
                    checkCurrent(job)
                    checkCredential(job)
                    update(job, "正在转写 ${index + 1}/${parts.size}", bundle)
                    val next = engine.transcribe(job.config, file, parts[index], job.cancellation)
                    checkCredential(job)
                    val cues = bundle.cues + next.mapIndexed { i, cue -> cue.copy(id = bundle.cues.size + i + 1) }
                    bundle = bundle.copy(cues = cues, completedParts = index + 1, updatedAt = System.currentTimeMillis())
                    checkpoint(job, bundle)
                    lastSaved = bundle
                }
                require(bundle.cues.isNotEmpty()) { "未识别到可用的音频文字" }
                bundle = bundle.copy(transcriptionComplete = true)
                checkpoint(job, bundle)
                lastSaved = bundle
            }
            checkCurrent(job)
            checkCredential(job)
            job.original.complete(bundle)
            val original = bundle
            translator.execute { translate(job, original) }
        } catch (error: Exception) {
            fail(job, lastSaved, error)
        } finally { runCatching { job.input.close() } }
    }

    private fun translate(job: Job, original: SubtitleBundle) {
        var bundle = original
        var lastSaved = original
        try {
            while (!bundle.bilingualComplete) {
                checkCurrent(job)
                checkCredential(job)
                val batch = SubtitleTranslation.nextBatch(bundle.cues, bundle.translations, job.batchedTranslation)
                require(batch.isNotEmpty()) { "字幕翻译检查点无效，请重新制作" }
                update(job, "正在翻译 ${bundle.translations.size}/${bundle.cues.size}", bundle)
                val response = AiProvider(maxTextChars = SubtitleTranslation.MAX_TEXT_CHARS,
                    timeoutSeconds = SubtitleTranslation.TIMEOUT_SECONDS).respond(
                    job.config.copy(instructions = SubtitleTranslation.INSTRUCTIONS,
                    webSearch = false, continuationMessages = emptyList(),
                    maxOutputTokens = if (job.batchedTranslation) 8192 else SubtitleTranslation.WHOLE_OUTPUT_TOKENS), emptyList(),
                    WireJson.encode(SubtitleTranslation.request(bundle.cues, batch, job.batchedTranslation)), null, job.cancellation, {})
                checkCredential(job)
                val parsed = try { WireJson.decode(response.trim()) }
                    catch (_: Exception) { throw ProviderException("中文翻译格式不完整，英文已保存，请重试翻译") }
                val translations = SubtitleTranslation.parse(parsed, batch)
                bundle = bundle.copy(translations = bundle.translations + translations, updatedAt = System.currentTimeMillis())
                checkpoint(job, bundle)
                lastSaved = bundle
            }
            checkCredential(job)
            synchronized(fileLock) { checkCurrent(job); archive.publish(job.key.owner, bundle); registerFiles(job.key.owner) }
            finish(job, Record(published = bundle, status = "双语字幕已保存", loaded = true))
        } catch (error: Exception) {
            fail(job, lastSaved, error)
        }
    }

    private fun fail(job: Job, lastSaved: SubtitleBundle?, error: Exception) {
        job.original.completeExceptionally(error)
        if (error is CancellationException) {
            finish(job, recordForFailure(lastSaved, "制作已中断，可手动继续"))
        } else {
            val message = when (error) {
                is ProviderException -> error.message.orEmpty().take(180)
                is IllegalArgumentException, is IllegalStateException -> error.message.orEmpty().take(180)
                is java.io.IOException -> "制作失败，请检查网络或存储后手动继续"
                else -> "字幕制作失败，已保留成功步骤，请手动继续"
            }
            finish(job, recordForFailure(lastSaved, message))
        }
    }

    private fun recordForFailure(bundle: SubtitleBundle?, error: String): Record = Record(
        draft = bundle?.takeIf { it.completedParts > 0 }, status = if (bundle?.transcriptionComplete == true)
            "英文已保存，中文尚未完成" else "制作未完成", error = error, loaded = true)

    private fun checkCredential(job: Job) {
        if (!configurationCurrent(job)) {
            job.cancellation.cancel(); throw CancellationException("字幕配置已改变")
        }
    }

    private fun configurationCurrent(job: Job): Boolean = runCatching {
        val current = preferences.request()
        current.credentialId == job.config.credentialId && current.credentialRevision == job.config.credentialRevision &&
            current.endpointId == job.config.endpointId && current.apiKey == job.config.apiKey &&
            current.model == job.config.model && current.apiProtocol == job.config.apiProtocol
    }.getOrDefault(false)

    private fun checkCurrent(job: Job) {
        job.cancellation.throwIfCancelled()
        if (!preferences.enabled(job.key.method) || revision(job.key) != job.revision) throw CancellationException()
    }

    private fun checkpoint(job: Job, bundle: SubtitleBundle) {
        synchronized(fileLock) { checkCurrent(job); archive.saveDraft(job.key.owner, bundle); registerFiles(job.key.owner) }
        update(job, if (bundle.transcriptionComplete) "英文字幕已保存" else "转写进度已保存", bundle)
    }

    private fun update(job: Job, status: String, draft: SubtitleBundle? = null) {
        main.post {
            if (jobs[job.key] !== job || job.cancellation.isCancelled) return@post
            records[job.key] = record(job.key.owner, job.key.method).copy(busy = true, status = status,
                draft = draft ?: record(job.key.owner, job.key.method).draft, error = "", loaded = true)
            publish()
        }
    }

    private fun registerFiles(owner: String) {
        val folder = File(context.noBackupFilesDir, "conversation_subtitles/${digest(owner)}")
        folder.walkTopDown().filter(File::isFile).forEach { StorageRuntime.get(context).register(it, "subtitles", owner) }
    }

    private fun finish(job: Job, result: Record) {
        main.post {
            if (!jobs.remove(job.key, job)) return@post
            ConversationUsage.update(job, null)
            if (revision(job.key) != job.revision) return@post
            val previous = record(job.key.owner, job.key.method)
            records[job.key] = result.copy(published = result.published ?: previous.published)
            trimRecords(); publish()
        }
    }

    private fun trimRecords() {
        while (records.size > 16) {
            val key = records.keys.firstOrNull { !jobs.containsKey(it) } ?: break
            records.remove(key)
        }
    }

    companion object {
        @Volatile private var instance: SubtitleController? = null
        private val fileLock = Any()
        private val revisions = ConcurrentHashMap<Key, Long>()
        private fun revision(key: Key) = revisions.computeIfAbsent(key) { 0L }
        private fun invalidate(key: Key) { revisions[key] = revision(key) + 1 }
        fun get(context: Context): SubtitleController = instance ?: synchronized(fileLock) {
            instance ?: SubtitleController(context.applicationContext).also { instance = it }
        }
        fun archiveFor(context: Context) = SubtitleArchive(File(context.noBackupFilesDir, "conversation_subtitles"))
        fun deleteConversation(context: Context, owner: String) {
            instance?.jobs?.values?.filter { it.key.owner == owner }?.forEach { it.cancellation.cancel() }
            synchronized(fileLock) {
                SubtitleMethod.entries.forEach { invalidate(Key(owner, it)) }
                archiveFor(context).delete(owner)
            }
            instance?.let { controller -> controller.main.post {
                SubtitleMethod.entries.forEach { controller.records.remove(Key(owner, it)) }
                controller.publish()
            } }
        }
        fun clear(context: Context) {
            instance?.jobs?.values?.forEach { it.cancellation.cancel() }
            synchronized(fileLock) {
                (revisions.keys + instance?.jobs?.keys.orEmpty()).forEach { invalidate(it) }
                archiveFor(context).clear()
            }
            instance?.let { controller -> controller.main.post { controller.records.clear(); controller.publish() } }
        }
        internal fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun audioHash(file: File, cancellation: RequestCancellation): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    cancellation.throwIfCancelled()
                    val count = input.read(bytes)
                    if (count < 0) break
                    hash.update(bytes, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
