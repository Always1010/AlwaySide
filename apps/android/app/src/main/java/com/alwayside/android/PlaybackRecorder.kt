package com.alwayside.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

internal data class AudioClip(val file: File,val seconds: Float,val peak: Double,val error: String?,val reachedLimit: Boolean=false)

/** Capture the playback source only. Permission does not select a microphone source. */
internal class PlaybackRecorder(private val context: Context,private val main: Handler) {
    @Volatile var seconds=0f
        private set
    @Volatile var level=0.0
        private set
    private val completion=RecordingCompletion()
    private var record: AudioRecord?=null
    private var thread: Thread?=null

    @SuppressLint("MissingPermission")
    fun start(projection: MediaProjection, maxMinutes: Int, onDone: (AudioClip?) -> Unit) {
        val maxBytes = RecordingLimits.pcmBytes(maxMinutes)
        val maxMillis = if (maxMinutes == 0) Long.MAX_VALUE else maxMinutes * 60_000L
        check(thread==null) { "录音仍在处理中" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "请先在主界面允许音频权限" }
        val config=AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
        val minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        check(minimum>0) { "设备不支持当前录音格式" }
        val audio=AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum*2,8192)).build()
        try {
            check(audio.state==AudioRecord.STATE_INITIALIZED) { "内部音频初始化失败" }
            audio.startRecording()
            check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "系统未开始录音" }
        } catch (e: Exception) { audio.release(); throw e }
        record=audio
        val file=File(context.cacheDir,"listening-${System.nanoTime()}.wav")
        ConversationUsage.update(this, "@recording")
        thread=Thread({
            var bytes=0
            var peak=0.0
            var failure: String?=null
            try {
                RandomAccessFile(file,"rw").use { out ->
                    out.write(ByteArray(44))
                    val data=ShortArray(4096)
                    val encoded=ByteArray(data.size*2)
                    val startedAt=SystemClock.elapsedRealtime()
                    while (!completion.shouldStop(bytes,SystemClock.elapsedRealtime()-startedAt,maxBytes,maxMillis)) {
                        val count=audio.read(data,0,minOf(data.size,(maxBytes-bytes)/2),AudioRecord.READ_NON_BLOCKING)
                        if (count<0) {
                            if (completion.reason==null) failure="音频读取中断（$count）"
                            break
                        }
                        if (count==0) { Thread.sleep(10); continue }
                        var power=0.0
                        for (i in 0 until count) {
                            val sample=data[i].toInt()
                            encoded[i*2]=(sample and 255).toByte()
                            encoded[i*2+1]=(sample shr 8).toByte()
                            power+=sample.toDouble()*sample
                        }
                        out.write(encoded,0,count*2); bytes+=count*2
                        seconds=bytes.toFloat()/(RATE*2)
                        level=sqrt(power/count)/32768.0
                        peak=maxOf(peak,level)
                    }
                    out.seek(0); out.write(Wav.header(bytes,RATE))
                }
            } catch (e: Exception) { failure="录音未完成：${e.javaClass.simpleName}" }
            finally {
                runCatching { audio.stop() }; audio.release()
            }
            val clip=if (completion.reason==RecordingEndReason.CANCELLED || bytes==0 || failure?.startsWith("录音未完成")==true) {
                file.delete(); null
            } else AudioClip(file,seconds,peak,failure,completion.reason==RecordingEndReason.LIMIT)
            main.post {
                record=null; thread=null
                try { onDone(clip) } finally { ConversationUsage.update(this, null) }
            }
        },"playback-capture").apply { start() }
    }
    fun stop(discardClip: Boolean=false) {
        if (thread == null) ConversationUsage.update(this, null)
        completion.stop(discardClip)
        // release() remains owned by the recording thread.
        runCatching { record?.stop() }
    }
    companion object { private const val RATE=RecordingLimits.SAMPLE_RATE }
}
