package com.alwayside.android

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.roundToLong

/** Blocking, explicit one-part requests. Callers checkpoint each success and decide when to retry. */
internal class SubtitleEngine {
    fun parts(file: File, cancellation: RequestCancellation): List<SubtitleAudioPart> =
        sanitized(cancellation) {
            val frames = SubtitleWav.frames(file, cancellation)
            SubtitleWav.whisperParts(file, frames, cancellation)
        }

    fun transcribe(config: AppConfig, file: File, part: SubtitleAudioPart,
                   cancellation: RequestCancellation): List<SubtitleCue> = sanitized(cancellation) {
        SubtitleWire.validate(config)
        val totalFrames = SubtitleWav.frames(file, cancellation)
        if (part.startFrame < 0 || part.endFrame <= part.startFrame || part.endFrame > totalFrames ||
            part.endMs <= part.startMs ||
            (part.endFrame - part.startFrame) * 2 > RecordingLimits.UPLOAD_PCM_BYTES)
            throw ProviderException("字幕音频片段无效，请重新生成")
        val pcmBytes = ((part.endFrame - part.startFrame) * 2).toInt()
        val boundary = "alwayside-subtitle-${UUID.randomUUID()}"
        val prefix = SubtitleWire.prefix(boundary)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        RandomAccessFile(file, "r").use { source ->
            source.seek(44 + part.startFrame * 2)
            HttpTransport().request(config, "audio/transcriptions", "multipart/form-data; boundary=$boundary",
                prefix.size.toLong() + 44 + pcmBytes + suffix.size, cancellation, { output ->
                    output.write(prefix)
                    output.write(Wav.header(pcmBytes, RecordingLimits.SAMPLE_RATE))
                    val buffer = ByteArray(16_384)
                    var remaining = pcmBytes
                    while (remaining > 0) {
                        cancellation.throwIfCancelled()
                        val count = source.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count <= 0) throw ProviderException("录音文件已变化，请重新生成字幕")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    if (source.length() != 44 + totalFrames * 2)
                        throw ProviderException("录音文件已变化，请重新生成字幕")
                    output.write(suffix)
                }) { connection, deadline ->
                val response = connection.inputStream.use {
                    HttpTransport.readBounded(it, 2 * 1024 * 1024, cancellation, deadline)
                }
                cancellation.throwIfCancelled()
                SubtitleWire.cues(WireJson.decode(response), part)
            }
        }
    }

    private fun <T> sanitized(cancellation: RequestCancellation, block: () -> T): T {
        try {
            cancellation.throwIfCancelled()
            return block().also { cancellation.throwIfCancelled() }
        } catch (error: CancellationException) { throw error }
        catch (error: ProviderException) { cancellation.throwIfCancelled(); throw error }
        catch (_: SocketTimeoutException) {
            cancellation.throwIfCancelled()
            throw ProviderException("字幕请求超时，请手动重试", "请求超时，可重试")
        } catch (_: IOException) {
            cancellation.throwIfCancelled()
            throw ProviderException("字幕音频读取或网络连接失败，请检查后手动重试")
        } catch (_: Exception) {
            cancellation.throwIfCancelled()
            throw ProviderException("字幕处理失败，请检查录音或服务返回格式后手动重试")
        }
    }

    companion object {
        // Preserve the experiment's opaque version so existing Whisper checkpoints can resume.
        const val VERSION = "subtitle-v1-silero-6.2.3-pad160-pause300-target5-max12"
    }
}

/** Whisper multipart parameters and segment timestamps. */
internal object SubtitleWire {
    private const val MAX_CUE_CHARS = 4_000
    private const val TIMESTAMP_TOLERANCE_SECONDS = 0.25

    fun validate(config: AppConfig) {
        if (config.baseUrl != "https://api.openai.com/v1" ||
            config.apiProtocol !in setOf(ApiProtocol.RESPONSES, ApiProtocol.CHAT_COMPLETIONS))
            throw ProviderException("字幕实验仅支持 OpenAI 官方接口")
        if (config.apiKey.isBlank() || config.apiKey.length > 4096 || config.apiKey.any { it.code !in 33..126 })
            throw ProviderException("请为字幕实验选择有效的 OpenAI Key")
    }

    fun prefix(boundary: String): ByteArray {
        val fields = linkedMapOf("model" to SubtitleMethod.WHISPER.model,
            "response_format" to "verbose_json", "language" to "en", "timestamp_granularities[]" to "segment")
        return buildString {
            fields.forEach { (name, value) ->
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"subtitle.wav\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
    }

    fun cues(response: Map<String, Any?>, part: SubtitleAudioPart): List<SubtitleCue> {
        if (part.startMs < 0 || part.endMs <= part.startMs) invalid()
        val text = response["text"] as? String ?: invalid()
        if (text.length > ChatPrompt.MAX_TRANSCRIPT_CHARS) invalid()
        val segments = response["segments"] as? List<*> ?: invalid()
        if (segments.size > 10_000 || (segments.isEmpty() && text.isNotBlank())) invalid()
        val result = mutableListOf<SubtitleCue>()
        var previousStart = part.startMs
        for (raw in segments) {
            val segment = raw as? Map<*, *> ?: invalid()
            val words = segment["text"] as? String ?: invalid()
            if (words.length > ChatPrompt.MAX_TRANSCRIPT_CHARS) invalid()
            val content = clean(words)
            if (content.length > MAX_CUE_CHARS) invalid()
            // Empty decoder segments carry no subtitle, and can have zero or absent timestamps.
            if (content.isEmpty()) continue
            val start = (segment["start"] as? Number)?.toDouble() ?: invalid()
            val end = (segment["end"] as? Number)?.toDouble() ?: invalid()
            if (!start.isFinite() || !end.isFinite() || end <= start) invalid()
            if (start < -TIMESTAMP_TOLERANCE_SECONDS) invalid()
            // Intersect provider times with actual uploaded PCM bounds. Do not shift overlapping
            // speech to the previous cue's end: nested segments would collapse to zero duration.
            val startMs = part.startMs + (start * 1000).coerceIn(0.0,
                (part.endMs - part.startMs).toDouble()).roundToLong()
            val endMs = part.startMs + (end * 1000).coerceIn(0.0,
                (part.endMs - part.startMs).toDouble()).roundToLong()
            if (endMs <= startMs || startMs < previousStart) invalid()
            result += SubtitleCue(result.size + 1, startMs, endMs, content)
            previousStart = startMs
        }
        if (result.isEmpty() && text.isNotBlank()) invalid()
        return result
    }

    private fun clean(text: String) = text.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
    private fun invalid(): Nothing = throw ProviderException("字幕转写返回格式或时间戳无效，请手动重试")
}

/** Recorder format validation and bounded silence search; never allocate the whole recording. */
internal object SubtitleWav {
    fun frames(file: File, cancellation: RequestCancellation): Long {
        cancellation.throwIfCancelled()
        val size = file.length()
        if (!file.isFile || size <= 44 || size > RecordingLimits.MAX_PCM_BYTES.toLong() + 44 || (size - 44) % 2 != 0L)
            throw ProviderException("录音为空或 WAV 文件大小无效")
        RandomAccessFile(file, "r").use { source ->
            val header = ByteArray(44).also(source::readFully)
            if (!header.contentEquals(Wav.header((size - 44).toInt(), RecordingLimits.SAMPLE_RATE)))
                throw ProviderException("字幕需要 PCM16 单声道 48 kHz 录音，请重新录制")
        }
        return (size - 44) / 2
    }

    fun whisperParts(file: File, frames: Long, cancellation: RequestCancellation): List<SubtitleAudioPart> {
        val result = mutableListOf<SubtitleAudioPart>()
        val limit = RecordingLimits.UPLOAD_PCM_BYTES / 2L
        RandomAccessFile(file, "r").use { source ->
            var start = 0L
            while (start < frames) {
                cancellation.throwIfCancelled()
                val end = if (frames - start <= limit) frames else quietBoundary(source, start, limit, cancellation)
                result += SubtitleAudioPart(start, end)
                start = end
            }
            if (source.length() != 44 + frames * 2) throw ProviderException("录音文件已变化，请重新生成字幕")
        }
        return result
    }

    private fun quietBoundary(source: RandomAccessFile, start: Long, limit: Long,
                              cancellation: RequestCancellation): Long {
        val windowFrames = RecordingLimits.SAMPLE_RATE / 50
        val buffer = ByteArray(windowFrames * 2)
        var offset = maxOf(0, limit - RecordingLimits.SAMPLE_RATE * 5L)
        var quietStart = offset
        var boundary = limit
        source.seek(44 + (start + offset) * 2)
        while (offset < limit) {
            cancellation.throwIfCancelled()
            val frames = minOf(windowFrames.toLong(), limit - offset).toInt()
            source.readFully(buffer, 0, frames * 2)
            var energy = 0L
            for (i in 0 until frames * 2 step 2) {
                val sample = ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                energy += sample.toLong() * sample
            }
            offset += frames
            if (energy <= 327L * 327 * frames) {
                if (offset - quietStart >= RecordingLimits.SAMPLE_RATE / 5)
                    boundary = quietStart + (offset - quietStart) / 2
            } else quietStart = offset
        }
        return start + boundary
    }
}
