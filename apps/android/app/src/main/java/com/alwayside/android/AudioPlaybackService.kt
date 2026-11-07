package com.alwayside.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder

/** Hosts only user-started playback, independently of screen capture and the floating assistant. */
@Suppress("DEPRECATION")
internal class AudioPlaybackService : Service() {
    private lateinit var playback: ChatAudioPlayback
    private lateinit var session: MediaSession
    var hosting: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        playback = ChatAudioPlayback.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "音频播放", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
        session = MediaSession(this, "conversation-audio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { playback.resume() }
                override fun onPause() { playback.pause() }
                override fun onStop() { playback.stop() }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == STOP) playback.stop()
                }
            })
            setSessionActivity(openConversation())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Even a cancelled, queued start must satisfy startForegroundService's deadline.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        hosting = true
        if (!playback.serviceReady(this)) { finishHosting(); return START_NOT_STICKY }
        when (intent?.action) {
            PAUSE -> playback.pause()
            PLAY -> playback.resume()
            STOP -> playback.stop()
        }
        if (playback.conversationId != null) updatePlayback()
        if (!playback.currentState.playing && !playback.currentState.preparing) pauseHosting()
        return START_NOT_STICKY
    }

    fun updatePlayback() {
        val state = playback.currentState
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, playback.mediaTitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, playback.duration.toLong()).build())
        val status = when {
            state.preparing -> PlaybackState.STATE_BUFFERING
            state.playing -> PlaybackState.STATE_PLAYING
            state.remaining != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .addCustomAction(STOP, "停止", R.drawable.ic_line_stop)
            .setState(status, playback.position.toLong(), if (state.playing) playback.speed else 0f).build())
        if (playback.conversationId != null)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    fun pauseHosting() {
        stopForeground(STOP_FOREGROUND_DETACH)
        hosting = false
    }

    fun finishHosting() {
        hosting = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    // A task swipe removes the UI; explicit Stop, completion and process termination end playback.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playback.currentState.playing && !playback.currentState.preparing) playback.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        playback.serviceDestroyed(this)
        session.isActive = false; session.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val state = playback.currentState
        val pause = state.playing || state.preparing
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_line_audio)
            .setContentTitle(playback.mediaTitle)
            .setContentText(if (state.preparing) "正在读取音频" else if (state.playing) "正在播放" else "已暂停")
            .setContentIntent(openConversation())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setOngoing(state.playing || state.preparing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(
                if (pause) R.drawable.ic_line_pause else R.drawable.ic_line_play,
                if (pause) "暂停" else "播放", command(if (pause) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_line_stop, "停止", command(STOP)).build())
            .setDeleteIntent(command(STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getForegroundService(this, 0,
        Intent(this, AudioPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openConversation(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL = "audio_playback"
        private const val NOTIFICATION_ID = 102
        private const val PLAY = "com.alwayside.android.audio.PLAY"
        private const val PAUSE = "com.alwayside.android.audio.PAUSE"
        private const val STOP = "com.alwayside.android.audio.STOP"
        private const val OPEN = "com.alwayside.android.audio.OPEN"

        fun takeEntry(intent: Intent, context: android.content.Context): AppEntry.Request? {
            if (intent.action != OPEN) return null
            intent.action = null
            val id = ChatAudioPlayback.get(context).conversationId ?: return null
            return AppEntry.Request(AppPage.CHAT, id)
        }
    }
}
