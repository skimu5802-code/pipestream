package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackNotificationService : Service() {

    companion object {
        private const val TAG = "PlaybackNotifService"
        const val CHANNEL_ID = "pipestream_playback_channel"
        const val CHANNEL_NAME = "PipeStream Media Playback"
        const val NOTIFICATION_ID = 2001

        const val ACTION_PLAY = "com.example.pipestream.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.pipestream.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.example.pipestream.ACTION_TOGGLE"
        const val ACTION_SEEK_BACK = "com.example.pipestream.ACTION_SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.example.pipestream.ACTION_SEEK_FORWARD"
        const val ACTION_STOP = "com.example.pipestream.ACTION_STOP"
        const val ACTION_UPDATE = "com.example.pipestream.ACTION_UPDATE"

        const val EXTRA_EXPAND_PLAYER = "extra_expand_player"

        fun startNotificationService(context: Context) {
            val intent = Intent(context, PlaybackNotificationService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting notification service: ${e.message}")
            }
        }

        fun stopNotificationService(context: Context) {
            try {
                val intent = Intent(context, PlaybackNotificationService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping notification service: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null
    private var lastThumbnailUrl: String? = null
    private var cachedThumbnailBitmap: Bitmap? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        observePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = MediaPlaybackManager.activeInstance

        when (intent?.action) {
            ACTION_PLAY -> manager?.let { if (!it.player.isPlaying) it.togglePlayPause() }
            ACTION_PAUSE -> manager?.let { if (it.player.isPlaying) it.togglePlayPause() }
            ACTION_TOGGLE -> manager?.togglePlayPause()
            ACTION_SEEK_BACK -> manager?.seekRelative(-10000)
            ACTION_SEEK_FORWARD -> manager?.seekRelative(10000)
            ACTION_STOP -> {
                manager?.player?.pause()
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                // Handled below
            }
        }

        // Immediately start foreground synchronously to avoid ForegroundServiceDidNotStartInTimeException
        val stream = manager?.playbackState?.value?.currentStream
        val isPlaying = manager?.playbackState?.value?.isPlaying ?: false
        val isBuffering = manager?.playbackState?.value?.isBuffering ?: false
        val initialNotif = buildNotificationSync(
            title = stream?.title ?: "Playing Media",
            uploader = stream?.uploaderName ?: "PipeStream",
            isPlaying = isPlaying,
            bitmap = cachedThumbnailBitmap
        )
        startServiceInForeground(initialNotif)

        if (isPlaying || isBuffering || manager?.player?.playWhenReady == true) {
            acquireLocks()
        }

        updateNotificationAsync()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PipeStream:PlaybackServiceWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(4 * 60 * 60 * 1000L) // 4 hours timeout safety
                    Log.d(TAG, "WakeLock acquired for background playback")
                }
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm?.createWifiLock(mode, "PipeStream:PlaybackServiceWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "WifiLock acquired for continuous background streaming")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}")
        }
    }

    private fun startServiceInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed startServiceInForeground: ${e.message}")
        }
    }

    private fun observePlaybackState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            val manager = MediaPlaybackManager.activeInstance
            if (manager == null) {
                stopForegroundService()
                return@launch
            }

            manager.playbackState.collectLatest { state ->
                if (state.currentStream == null) {
                    stopForegroundService()
                } else {
                    val shouldHoldLocks = state.isPlaying || state.isBuffering || (manager.player.playWhenReady && !state.isEnded)
                    if (shouldHoldLocks) {
                        acquireLocks()
                    } else {
                        releaseLocks()
                    }
                    updateNotificationAsync()
                }
            }
        }
    }

    private fun updateNotificationAsync() {
        val manager = MediaPlaybackManager.activeInstance
        if (manager == null) {
            stopForegroundService()
            return
        }

        val state = manager.playbackState.value
        val stream = state.currentStream
        if (stream == null) {
            stopForegroundService()
            return
        }

        serviceScope.launch {
            val streamId = stream.id
            val thumbUrl = "https://i.ytimg.com/vi/$streamId/hqdefault.jpg"
            if (thumbUrl != lastThumbnailUrl || cachedThumbnailBitmap == null) {
                cachedThumbnailBitmap = withContext(Dispatchers.IO) {
                    try {
                        val loader = ImageLoader(this@PlaybackNotificationService)
                        val request = ImageRequest.Builder(this@PlaybackNotificationService)
                            .data(thumbUrl)
                            .allowHardware(false)
                            .build()
                        val result = loader.execute(request)
                        (result.drawable as? BitmapDrawable)?.bitmap
                    } catch (e: Exception) {
                        null
                    }
                }
                lastThumbnailUrl = thumbUrl
            }

            val notification = buildNotificationSync(
                title = stream.title,
                uploader = stream.uploaderName,
                isPlaying = state.isPlaying,
                bitmap = cachedThumbnailBitmap
            )

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotificationSync(
        title: String,
        uploader: String,
        isPlaying: Boolean,
        bitmap: Bitmap?
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_EXPAND_PLAYER, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification Action PendingIntents
        val toggleIntent = Intent(this, PlaybackNotificationService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seekBackIntent = Intent(this, PlaybackNotificationService::class.java).apply { action = ACTION_SEEK_BACK }
        val seekBackPendingIntent = PendingIntent.getService(
            this, 2, seekBackIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seekForwardIntent = Intent(this, PlaybackNotificationService::class.java).apply { action = ACTION_SEEK_FORWARD }
        val seekForwardPendingIntent = PendingIntent.getService(
            this, 3, seekForwardIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PlaybackNotificationService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 4, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopPendingIntent)

        val session = MediaPlaybackManager.activeInstance?.mediaSession
        session?.sessionCompatToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(uploader)
            .setSubText(if (isPlaying) "Playing" else "Paused")
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                R.drawable.ic_replay_10,
                "Rewind",
                seekBackPendingIntent
            )
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                if (isPlaying) "Pause" else "Play",
                togglePendingIntent
            )
            .addAction(
                R.drawable.ic_forward_10,
                "Forward",
                seekForwardPendingIntent
            )
            .addAction(
                R.drawable.ic_close,
                "Close",
                stopPendingIntent
            )
            .setStyle(mediaStyle)

        bitmap?.let {
            builder.setLargeIcon(it)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PipeStream background audio and video playback controls"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        stateObserverJob?.cancel()
    }
}
