package com.example.player

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.example.data.cache.PlaybackUrlCache
import com.example.data.model.AudioStreamFormat
import com.example.data.model.StreamDetails
import com.example.data.model.VideoStreamFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackState(
    val currentStream: StreamDetails? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasFirstFrameRendered: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val selectedQuality: String = "720p",
    val isAudioOnly: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isEnded: Boolean = false,
    val sleepTimerMinutesRemaining: Int? = null,
    val sleepTimerSecondsRemaining: Int? = null,
    val sleepTimerOption: String? = null,
    val stopAtEndOfTrack: Boolean = false,
    val errorMessage: String? = null,
    val isLocalFile: Boolean = false
)

@OptIn(UnstableApi::class)
class MediaPlaybackManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaPlaybackManager"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        @Volatile
        var activeInstance: MediaPlaybackManager? = null
            private set

        fun getInstance(context: Context): MediaPlaybackManager {
            return activeInstance ?: synchronized(this) {
                activeInstance ?: MediaPlaybackManager(context.applicationContext).also {
                    activeInstance = it
                }
            }
        }
    }

    var isAppInForeground: Boolean = true
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PipeStream:MediaPlaybackManagerWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(12 * 60 * 60 * 1000L) // 12 hours safety
                    Log.d(TAG, "WakeLock acquired in MediaPlaybackManager")
                }
            }

            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "PipeStream:MediaPlaybackManagerWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "WifiLock acquired in MediaPlaybackManager")
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
                    Log.d(TAG, "WakeLock released in MediaPlaybackManager")
                }
            }
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released in MediaPlaybackManager")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}")
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        val currentStream = _playbackState.value.currentStream
        val currentPos = player.currentPosition

        // When moving to the background or turning screen off
        if (!inForeground) {
            if (currentStream != null && currentStream.id.isNotBlank()) {
                PlaybackPositionWorker.enqueue(
                    context = context,
                    streamId = currentStream.id,
                    title = currentStream.title,
                    uploader = currentStream.uploaderName,
                    avatar = currentStream.uploaderAvatar,
                    thumbnail = currentStream.description.takeIf { it.startsWith("http") } ?: "",
                    durationSec = currentStream.durationSeconds,
                    positionMs = currentPos,
                    isLocal = _playbackState.value.isLocalFile
                )
            }
            if (_playbackState.value.isPlaying || player.playWhenReady) {
                acquireLocks()
                PlaybackNotificationService.startNotificationService(context)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var streamRetryAttempt = 0
    private var streamRefreshAttempt = 0

    private val loadErrorHandlingPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val attempt = loadErrorInfo.errorCount
            return (1000L * (1 shl (attempt - 1).coerceAtMost(3))).coerceAtMost(5000L)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            return 8 // Allow up to 8 retries for transient mobile network/socket interruptions
        }
    }

    // HTTP Data Source with custom User-Agent and robust keep-alive timeouts for screen-off playback
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(20000)
        .setReadTimeoutMs(30000)
        .setAllowCrossProtocolRedirects(true)
        .setKeepPostFor302Redirects(true)
        .setDefaultRequestProperties(
            mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "User-Agent" to USER_AGENT
            )
        )

    // High-speed Disk & Memory Caching Data Source
    private val dataSourceFactory = PlayerCacheManager.createCacheDataSourceFactory(context, httpDataSourceFactory)

    // Direct local File DataSource for downloaded offline media (never touches network stack or caches)
    private val localFileDataSourceFactory = FileDataSource.Factory()

    private val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
        .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_SEF_DATA)
        .setFragmentedMp4ExtractorFlags(
            androidx.media3.extractor.mp4.FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS
        )

    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    private val localMediaSourceFactory = DefaultMediaSourceFactory(localFileDataSourceFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

    // Advanced steady pre-buffer LoadControl for continuous seamless screen-off playback
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            30000,  // minBufferMs (30s)
            60000,  // maxBufferMs (60s continuous refresh)
            500,    // bufferForPlaybackMs (instant fast startup)
            1000    // bufferForPlaybackAfterRebufferMs (1000ms recovery)
        )
        .setBackBuffer(
            15000,  // backBufferDurationMs
            true    // retainBackBufferFromKeyframe
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val wakeMode = if (androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WAKE_LOCK
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        C.WAKE_MODE_NETWORK
    } else {
        C.WAKE_MODE_NONE
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(10000)
        .setSeekForwardIncrementMs(10000)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(wakeMode)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        .build()

    val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setId("PipeStreamMediaSession")
        .build()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _playbackState.value = _playbackState.value.copy(
                isBuffering = false,
                hasFirstFrameRendered = true
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.value = _playbackState.value.copy(
                isPlaying = isPlaying,
                hasFirstFrameRendered = if (isPlaying) true else _playbackState.value.hasFirstFrameRendered
            )
            if (isPlaying) {
                acquireLocks()
                PlaybackNotificationService.startNotificationService(context)
                startProgressTracker()
            } else {
                if (!player.playWhenReady) {
                    releaseLocks()
                }
                stopProgressTracker()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    acquireLocks()
                    PlaybackNotificationService.startNotificationService(context)
                    _playbackState.value = _playbackState.value.copy(isBuffering = true, isEnded = false)
                }
                Player.STATE_READY -> {
                    streamRetryAttempt = 0
                    if (player.playWhenReady) {
                        acquireLocks()
                        PlaybackNotificationService.startNotificationService(context)
                    }
                    _playbackState.value = _playbackState.value.copy(
                        isBuffering = false,
                        isEnded = false,
                        errorMessage = null,
                        durationMs = player.duration.coerceAtLeast(0)
                    )
                }
                Player.STATE_ENDED -> {
                    releaseLocks()
                    val stopOnEnd = _playbackState.value.stopAtEndOfTrack
                    _playbackState.value = _playbackState.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        isEnded = true,
                        currentPositionMs = player.duration,
                        sleepTimerMinutesRemaining = if (stopOnEnd) null else _playbackState.value.sleepTimerMinutesRemaining,
                        sleepTimerSecondsRemaining = if (stopOnEnd) null else _playbackState.value.sleepTimerSecondsRemaining,
                        sleepTimerOption = if (stopOnEnd) null else _playbackState.value.sleepTimerOption,
                        stopAtEndOfTrack = false
                    )
                    if (!stopOnEnd && _playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
                        player.seekTo(0)
                        player.play()
                    }
                }
                Player.STATE_IDLE -> {
                    _playbackState.value = _playbackState.value.copy(isBuffering = false)
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "ExoPlayer Error: ${error.message} (code: ${error.errorCodeName})", error)
            
            val current = _playbackState.value.currentStream
            if (current != null) {
                // If local file failed (incomplete download or corrupt file), seamlessly fall back to online stream
                if (_playbackState.value.isLocalFile && current.id.isNotBlank()) {
                    Log.w(TAG, "Local file error (${error.errorCodeName}). Attempting seamless online streaming fallback for ID: ${current.id}")
                    val savedPos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    scope.launch {
                        try {
                            val engine = com.example.data.api.ExtractorEngine()
                            val detailsResult = engine.getStreamDetails(current.id)
                            val details = detailsResult.getOrNull()
                            if (details != null && (details.videoStreams.isNotEmpty() || details.audioStreams.isNotEmpty())) {
                                withContext(Dispatchers.Main) {
                                    playStream(details, "720p", _playbackState.value.isAudioOnly, savedPos)
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Online fallback failed: ${e.message}")
                        }
                        withContext(Dispatchers.Main) {
                            _playbackState.value = _playbackState.value.copy(
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = "Local file is incomplete. Tap Retry to play online."
                            )
                        }
                    }
                    return
                }

                streamRetryAttempt++
                
                // Try next available valid stream in extracted video or audio streams
                val candidateStreams = if (_playbackState.value.isAudioOnly) {
                    current.audioStreams.map { it.url } + current.videoStreams.filter { !it.isVideoOnly }.map { it.url }
                } else {
                    current.videoStreams.map { it.url }
                }.filter { it.isNotBlank() }

                if (streamRetryAttempt < candidateStreams.size) {
                    val nextStream = candidateStreams[streamRetryAttempt]
                    Log.i(TAG, "Trying alternate stream URL (attempt $streamRetryAttempt of ${candidateStreams.size})...")
                    playDirectUrl(nextStream, current.id, player.currentPosition)
                    return
                }

                // If candidate URLs exhausted, automatically re-extract fresh stream URLs (e.g. for expired YouTube session tokens)
                if (streamRefreshAttempt < 2 && current.id.isNotBlank()) {
                    streamRefreshAttempt++
                    Log.w(TAG, "Refreshing stream URLs via ExtractorEngine (attempt $streamRefreshAttempt)...")
                    val savedPos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    _playbackState.value = _playbackState.value.copy(isBuffering = true)
                    scope.launch {
                        try {
                            val engine = com.example.data.api.ExtractorEngine()
                            val detailsResult = engine.getStreamDetails(current.id)
                            val details = detailsResult.getOrNull()
                            if (details != null && (details.videoStreams.isNotEmpty() || details.audioStreams.isNotEmpty())) {
                                withContext(Dispatchers.Main) {
                                    playStream(details, "720p", _playbackState.value.isAudioOnly, savedPos)
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Stream refresh failed: ${e.message}")
                        }
                        withContext(Dispatchers.Main) {
                            _playbackState.value = _playbackState.value.copy(
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = "Playback error (${error.errorCodeName}). Tap Retry to reconnect."
                            )
                        }
                    }
                    return
                }
            }

            _playbackState.value = _playbackState.value.copy(
                isBuffering = false,
                isPlaying = false,
                errorMessage = "Playback error (${error.errorCodeName}). Tap Retry to reconnect."
            )
        }
    }

    init {
        activeInstance = this
        player.addListener(playerListener)
    }

    private fun resolveBestStreamUrl(details: StreamDetails, preferredQuality: String, audioOnly: Boolean): Pair<String, String?>? {
        // Fast Cache Check for instant playback
        val cached = PlaybackUrlCache.getResolvedUrl(details.id, preferredQuality, audioOnly)
        if (cached != null) {
            Log.d(TAG, "resolveBestStreamUrl: Cache HIT for ID ${details.id} ($preferredQuality, audioOnly=$audioOnly)")
            return cached
        }

        // Returns Pair(videoOrAudioUrl, optionalAudioUrlToMerge)
        val resolved: Pair<String, String?>? = if (audioOnly) {
            val audioUrl = details.audioStreams.firstOrNull { it.url.isNotBlank() }?.url
            if (!audioUrl.isNullOrBlank()) {
                Log.d(TAG, "resolveBestStreamUrl: selected audio stream: $audioUrl")
                Pair(audioUrl, null)
            } else {
                val progressiveVideo = details.videoStreams.firstOrNull { !it.isVideoOnly && it.url.isNotBlank() }?.url
                if (!progressiveVideo.isNullOrBlank()) {
                    Log.d(TAG, "resolveBestStreamUrl: selected fallback progressive video for audio: $progressiveVideo")
                    Pair(progressiveVideo, null)
                } else {
                    val anyStream = details.videoStreams.firstOrNull { it.url.isNotBlank() }?.url
                    Log.d(TAG, "resolveBestStreamUrl: selected any video stream for audio: $anyStream")
                    anyStream?.let { Pair(it, null) }
                }
            }
        } else {
            // 1. Direct match with preferred quality on progressive (muxed audio+video) stream
            val progressiveMatch = details.videoStreams.firstOrNull { 
                !it.isVideoOnly && it.url.isNotBlank() && it.quality.contains(preferredQuality, ignoreCase = true) 
            }
            if (progressiveMatch != null) {
                Log.d(TAG, "resolveBestStreamUrl: matched progressive stream (${progressiveMatch.quality}, ${progressiveMatch.format}): ${progressiveMatch.url}")
                Pair(progressiveMatch.url, null)
            } else {
                // 2. Any progressive (audio + video muxed) format
                val anyProgressive = details.videoStreams.firstOrNull { !it.isVideoOnly && it.url.isNotBlank() }
                if (anyProgressive != null) {
                    Log.d(TAG, "resolveBestStreamUrl: selected progressive stream (${anyProgressive.quality}, ${anyProgressive.format}): ${anyProgressive.url}")
                    Pair(anyProgressive.url, null)
                } else {
                    // 3. HLS manifest (auto adaptive stream with audio+video)
                    val hls = details.videoStreams.firstOrNull { 
                        it.format.contains("m3u8", ignoreCase = true) || it.url.contains(".m3u8") 
                    }
                    if (hls != null) {
                        Log.d(TAG, "resolveBestStreamUrl: selected HLS adaptive stream: ${hls.url}")
                        Pair(hls.url, null)
                    } else {
                        // 4. Video-Only stream merged with separate audio stream
                        val videoOnlyMatch = details.videoStreams.firstOrNull { 
                            it.isVideoOnly && it.url.isNotBlank() && it.quality.contains(preferredQuality, ignoreCase = true)
                        } ?: details.videoStreams.firstOrNull { it.isVideoOnly && it.url.isNotBlank() }

                        val bestAudio = details.audioStreams.firstOrNull { it.url.isNotBlank() }

                        if (videoOnlyMatch != null) {
                            if (bestAudio != null) {
                                Log.d(TAG, "resolveBestStreamUrl: selected Video-Only (${videoOnlyMatch.quality}) + Audio (${bestAudio.quality}) for merge:\n  Video: ${videoOnlyMatch.url}\n  Audio: ${bestAudio.url}")
                                Pair(videoOnlyMatch.url, bestAudio.url)
                            } else {
                                Log.d(TAG, "resolveBestStreamUrl: selected Video-Only stream (no audio track): ${videoOnlyMatch.url}")
                                Pair(videoOnlyMatch.url, null)
                            }
                        } else if (bestAudio != null) {
                            // 5. Audio-only fallback if no video found
                            Log.d(TAG, "resolveBestStreamUrl: fallback to audio stream: ${bestAudio.url}")
                            Pair(bestAudio.url, null)
                        } else {
                            Log.w(TAG, "resolveBestStreamUrl: NO playable stream found in details for ID: ${details.id}")
                            null
                        }
                    }
                }
            }
        }

        // Cache the resolved direct stream URL for instant replay
        if (resolved != null) {
            PlaybackUrlCache.putResolvedUrl(
                videoId = details.id,
                quality = preferredQuality,
                isAudioOnly = audioOnly,
                primaryUrl = resolved.first,
                audioUrl = resolved.second
            )
        }

        return resolved
    }

    private fun playDirectUrl(streamUrl: String, mediaId: String, startPositionMs: Long = 0, audioUrlToMerge: String? = null) {
        try {
            Log.d(TAG, "playDirectUrl -> Loading Media (mediaId: $mediaId, startPos: ${startPositionMs}ms)")
            Log.d(TAG, "  Primary URL: $streamUrl")
            if (audioUrlToMerge != null) {
                Log.d(TAG, "  Merged Audio URL: $audioUrlToMerge")
            }

            _playbackState.value = _playbackState.value.copy(
                isBuffering = true,
                hasFirstFrameRendered = false,
                errorMessage = null
            )

            try {
                player.stop()
                player.clearMediaItems()
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing player media items: ${e.message}")
            }

            val parsedUri = if (streamUrl.startsWith("/")) {
                Uri.fromFile(java.io.File(streamUrl))
            } else {
                Uri.parse(streamUrl)
            }

            val currentStream = _playbackState.value.currentStream
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(currentStream?.title ?: "Playing Media")
                .setArtist(currentStream?.uploaderName ?: "PipeStream")
                .setArtworkUri(
                    if (currentStream != null && currentStream.id.isNotBlank()) {
                        Uri.parse("https://i.ytimg.com/vi/${currentStream.id}/hqdefault.jpg")
                    } else null
                )
                .build()

            val isLocal = streamUrl.startsWith("/") || streamUrl.startsWith("file:")
            val activeFactory = if (isLocal) localMediaSourceFactory else mediaSourceFactory

            if (audioUrlToMerge != null) {
                val videoSource = activeFactory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(parsedUri)
                        .setMediaId("${mediaId}_video")
                        .setMediaMetadata(metadata)
                        .build()
                )
                val audioUri = if (audioUrlToMerge.startsWith("/")) {
                    Uri.fromFile(java.io.File(audioUrlToMerge))
                } else {
                    Uri.parse(audioUrlToMerge)
                }
                val audioFactory = if (audioUrlToMerge.startsWith("/") || audioUrlToMerge.startsWith("file:")) localMediaSourceFactory else mediaSourceFactory
                val audioSource = audioFactory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(audioUri)
                        .setMediaId("${mediaId}_audio")
                        .setMediaMetadata(metadata)
                        .build()
                )
                val mergedSource = androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)
                player.setMediaSource(mergedSource, startPositionMs)
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(parsedUri)
                    .setMediaId(mediaId)
                    .setMediaMetadata(metadata)
                    .build()
                val mediaSource = activeFactory.createMediaSource(mediaItem)
                player.setMediaSource(mediaSource, startPositionMs)
            }

            player.prepare()
            player.playWhenReady = true
            PlaybackNotificationService.startNotificationService(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media item: ${e.message}", e)
            _playbackState.value = _playbackState.value.copy(
                isBuffering = false,
                errorMessage = "Failed to load stream: ${e.message}"
            )
        }
    }

    init {
        activeInstance = this
        player.addListener(playerListener)
    }

    fun prepareStream(details: StreamDetails, audioOnly: Boolean = false, startPositionMs: Long = 0) {
        Log.d(TAG, "prepareStream called for ID: ${details.id}, Title: '${details.title}', startPos: ${startPositionMs}ms")
        try {
            player.stop()
            player.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping player in prepareStream: ${e.message}")
        }
        stopProgressTracker()
        _playbackState.value = _playbackState.value.copy(
            currentStream = details,
            selectedQuality = if (audioOnly) "Audio Only" else "Auto",
            isAudioOnly = audioOnly,
            isPlaying = false,
            errorMessage = null,
            isBuffering = true,
            hasFirstFrameRendered = false,
            currentPositionMs = startPositionMs,
            durationMs = details.durationSeconds * 1000L,
            isLocalFile = false
        )
    }

    fun setError(message: String) {
        Log.e(TAG, "setError called: $message")
        _playbackState.value = _playbackState.value.copy(
            isBuffering = false,
            isPlaying = false,
            errorMessage = message
        )
    }

    fun playStream(details: StreamDetails, preferredQuality: String = "720p", audioOnly: Boolean = false, startPositionMs: Long = 0) {
        streamRetryAttempt = 0
        streamRefreshAttempt = 0
        Log.d(TAG, "playStream called for ID: ${details.id}, Quality: $preferredQuality, AudioOnly: $audioOnly, startPosition: ${startPositionMs}ms")
        Log.d(TAG, "Streams available: ${details.videoStreams.size} video, ${details.audioStreams.size} audio")

        _playbackState.value = _playbackState.value.copy(
            currentStream = details,
            selectedQuality = if (audioOnly) "Audio Only" else preferredQuality,
            isAudioOnly = audioOnly,
            errorMessage = null,
            isBuffering = true,
            hasFirstFrameRendered = false,
            currentPositionMs = startPositionMs,
            durationMs = details.durationSeconds * 1000L,
            isLocalFile = false
        )

        val resolved = resolveBestStreamUrl(details, preferredQuality, audioOnly)
        if (resolved != null) {
            playDirectUrl(resolved.first, details.id, startPositionMs, resolved.second)
        } else {
            Log.w(TAG, "No playable stream format found for video: ${details.id}")
            _playbackState.value = _playbackState.value.copy(
                isBuffering = false,
                errorMessage = "No playable stream format found. Tap Retry to reconnect."
            )
        }
    }

    fun playLocalFile(
        title: String,
        uploader: String,
        localPath: String,
        durationSec: Long,
        streamId: String,
        isAudio: Boolean = false,
        thumbnailUrl: String = "",
        startPositionMs: Long = 0L
    ) {
        val file = java.io.File(localPath)
        val isAudioTrack = isAudio || localPath.endsWith(".m4a", ignoreCase = true) || localPath.endsWith(".mp3", ignoreCase = true) || localPath.endsWith(".opus", ignoreCase = true)

        // If local file is missing or suspiciously tiny (< 100KB), trigger online streaming immediately
        if (!file.exists() || file.length() < 100 * 1024L) {
            if (streamId.isNotBlank()) {
                Log.w(TAG, "Local file missing or incomplete (${file.length()} bytes), streaming online for ID: $streamId")
                scope.launch {
                    try {
                        val engine = com.example.data.api.ExtractorEngine()
                        val details = engine.getStreamDetails(streamId).getOrNull()
                        if (details != null) {
                            withContext(Dispatchers.Main) {
                                playStream(details, "720p", isAudioTrack, startPositionMs)
                            }
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Online fallback launch error: ${e.message}")
                    }
                }
            }
        }

        val details = StreamDetails(
            id = streamId,
            title = title,
            uploaderName = uploader,
            uploaderAvatar = "",
            durationSeconds = durationSec,
            description = "Playing downloaded offline file.",
            videoStreams = if (!isAudioTrack) listOf(VideoStreamFormat(url = localPath, quality = "Offline", format = "mp4")) else emptyList(),
            audioStreams = if (isAudioTrack) listOf(AudioStreamFormat(url = localPath, quality = "Offline Audio", format = "m4a", bitrate = 128000)) else emptyList()
        )

        _playbackState.value = _playbackState.value.copy(
            currentStream = details,
            selectedQuality = if (isAudioTrack) "Offline Audio" else "Offline File",
            isAudioOnly = isAudioTrack,
            errorMessage = null,
            isBuffering = true,
            hasFirstFrameRendered = isAudioTrack,
            isLocalFile = true
        )

        playDirectUrl(localPath, streamId, startPositionMs)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val duration = if (player.duration > 0) player.duration else (_playbackState.value.durationMs)
        val validTarget = positionMs.coerceIn(0, duration.coerceAtLeast(0))
        player.seekTo(validTarget)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = validTarget)
    }

    fun seekRelative(offsetMs: Long) {
        val target = (player.currentPosition + offsetMs).coerceIn(0, player.duration.coerceAtLeast(0))
        seekTo(target)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    fun changeQuality(quality: String) {
        val current = _playbackState.value.currentStream ?: return
        val currentPos = player.currentPosition
        _playbackState.value = _playbackState.value.copy(selectedQuality = quality)
        val resolved = resolveBestStreamUrl(current, quality, _playbackState.value.isAudioOnly)
        if (resolved != null) {
            playDirectUrl(resolved.first, current.id, currentPos, resolved.second)
        }
    }

    fun toggleAudioOnly() {
        val current = _playbackState.value.currentStream ?: return
        val newAudioOnly = !_playbackState.value.isAudioOnly
        val currentPos = player.currentPosition
        _playbackState.value = _playbackState.value.copy(
            isAudioOnly = newAudioOnly,
            selectedQuality = if (newAudioOnly) "Audio Only" else "720p"
        )
        val resolved = resolveBestStreamUrl(current, "720p", newAudioOnly)
        if (resolved != null) {
            playDirectUrl(resolved.first, current.id, currentPos, resolved.second)
        }
    }

    fun retryPlayback() {
        val current = _playbackState.value.currentStream ?: return
        _playbackState.value = _playbackState.value.copy(
            isBuffering = true,
            errorMessage = null
        )

        if (_playbackState.value.isLocalFile && current.id.isNotBlank()) {
            scope.launch {
                try {
                    val engine = com.example.data.api.ExtractorEngine()
                    val detailsResult = engine.getStreamDetails(current.id)
                    val details = detailsResult.getOrNull()
                    if (details != null && (details.videoStreams.isNotEmpty() || details.audioStreams.isNotEmpty())) {
                        withContext(Dispatchers.Main) {
                            playStream(details, "720p", _playbackState.value.isAudioOnly, _playbackState.value.currentPositionMs)
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry online resolution failed: ${e.message}")
                }
                withContext(Dispatchers.Main) {
                    val resolved = resolveBestStreamUrl(current, _playbackState.value.selectedQuality, _playbackState.value.isAudioOnly)
                    if (resolved != null) {
                        playDirectUrl(resolved.first, current.id, _playbackState.value.currentPositionMs, resolved.second)
                    } else {
                        _playbackState.value = _playbackState.value.copy(
                            isBuffering = false,
                            errorMessage = "Could not resolve playable stream. Please retry."
                        )
                    }
                }
            }
            return
        }

        val resolved = resolveBestStreamUrl(current, _playbackState.value.selectedQuality, _playbackState.value.isAudioOnly)
        if (resolved != null) {
            playDirectUrl(resolved.first, current.id, _playbackState.value.currentPositionMs, resolved.second)
        } else {
            _playbackState.value = _playbackState.value.copy(
                isBuffering = false,
                errorMessage = "Could not resolve playable stream. Please retry."
            )
        }
    }

    fun setSleepTimer(minutes: Int?, optionLabel: String? = null, endOfTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        if (endOfTrack) {
            _playbackState.value = _playbackState.value.copy(
                sleepTimerMinutesRemaining = null,
                sleepTimerSecondsRemaining = null,
                sleepTimerOption = "End of current stream",
                stopAtEndOfTrack = true
            )
            return
        }

        if (minutes == null || minutes <= 0) {
            _playbackState.value = _playbackState.value.copy(
                sleepTimerMinutesRemaining = null,
                sleepTimerSecondsRemaining = null,
                sleepTimerOption = null,
                stopAtEndOfTrack = false
            )
            return
        }

        val totalSeconds = minutes * 60
        val label = optionLabel ?: "$minutes min"

        _playbackState.value = _playbackState.value.copy(
            sleepTimerMinutesRemaining = minutes,
            sleepTimerSecondsRemaining = totalSeconds,
            sleepTimerOption = label,
            stopAtEndOfTrack = false
        )

        sleepTimerJob = scope.launch {
            var remainingSec = totalSeconds
            while (remainingSec > 0 && isActive) {
                delay(1000)
                remainingSec--
                val remainingMins = (remainingSec + 59) / 60
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerSecondsRemaining = if (remainingSec > 0) remainingSec else null,
                    sleepTimerMinutesRemaining = if (remainingSec > 0) remainingMins else null,
                    sleepTimerOption = if (remainingSec > 0) label else null
                )
            }
            if (isActive) {
                player.pause()
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerMinutesRemaining = null,
                    sleepTimerSecondsRemaining = null,
                    sleepTimerOption = null,
                    stopAtEndOfTrack = false
                )
            }
        }
    }

    fun extendSleepTimer(extraMinutes: Int) {
        val currentSec = _playbackState.value.sleepTimerSecondsRemaining ?: 0
        val newTotalSec = currentSec + (extraMinutes * 60)
        val newMins = (newTotalSec + 59) / 60
        setSleepTimer(newMins, "${newMins} min")
    }

    fun cancelSleepTimer() {
        setSleepTimer(null)
    }

    fun toggleRepeatMode() {
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0)
                    )
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
    }

    /**
     * Completely stops playback, clears active stream state, and removes any active foreground notification.
     */
    fun closePlayback() {
        val currentStream = _playbackState.value.currentStream
        val currentPos = player.currentPosition
        if (currentStream != null && currentStream.id.isNotBlank()) {
            PlaybackPositionWorker.enqueue(
                context = context,
                streamId = currentStream.id,
                title = currentStream.title,
                uploader = currentStream.uploaderName,
                avatar = currentStream.uploaderAvatar,
                thumbnail = currentStream.description.takeIf { it.startsWith("http") } ?: "",
                durationSec = currentStream.durationSeconds,
                positionMs = currentPos,
                isLocal = _playbackState.value.isLocalFile
            )
        }

        stopProgressTracker()
        sleepTimerJob?.cancel()
        releaseLocks()
        try {
            player.pause()
            player.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing player during close: ${e.message}")
        }
        _playbackState.value = PlaybackState()
        try {
            PlaybackNotificationService.stopNotificationService(context)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping notification service: ${e.message}")
        }
    }

    fun release() {
        stopProgressTracker()
        sleepTimerJob?.cancel()
        releaseLocks()
        player.removeListener(playerListener)
        try {
            mediaSession.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mediaSession: ${e.message}")
        }
        player.release()
        if (activeInstance == this) {
            activeInstance = null
        }
    }
}
