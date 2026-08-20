package com.example.ui.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.api.ExtractorEngine
import com.example.data.db.AppDatabase
import com.example.data.model.BookmarkEntity
import com.example.data.model.ChannelDetails
import com.example.data.model.ClipboardDetectedVideo
import com.example.data.model.CommentItem
import com.example.data.model.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.HistoryEntity
import com.example.data.model.StreamDetails
import com.example.data.model.StreamItem
import com.example.data.model.SubscriptionEntity
import com.example.player.DownloadHelper
import com.example.player.MediaPlaybackManager
import com.example.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application.applicationContext)

    val historyDao = db.historyDao()
    val bookmarkDao = db.bookmarkDao()
    val subscriptionDao = db.subscriptionDao()
    val downloadDao = db.downloadDao()

    val extractor = ExtractorEngine()
    val playbackManager = MediaPlaybackManager(application.applicationContext)
    val downloadHelper = DownloadHelper(application.applicationContext, downloadDao)

    val playbackState: StateFlow<PlaybackState> = playbackManager.playbackState

    // Feeds state
    private val _trendingStreams = MutableStateFlow<List<StreamItem>>(emptyList())
    val trendingStreams: StateFlow<List<StreamItem>> = _trendingStreams.asStateFlow()

    private val _personalizedStreams = MutableStateFlow<List<StreamItem>>(emptyList())
    val personalizedStreams: StateFlow<List<StreamItem>> = _personalizedStreams.asStateFlow()

    private val _subscriptionStreams = MutableStateFlow<List<StreamItem>>(emptyList())
    val subscriptionStreams: StateFlow<List<StreamItem>> = _subscriptionStreams.asStateFlow()

    // Location / Content Region state (Persisted in SharedPreferences)
    private val prefs = getApplication<Application>().getSharedPreferences("pipestream_prefs", android.content.Context.MODE_PRIVATE)

    private val _contentRegion = MutableStateFlow(
        prefs.getString("content_region", null) ?: detectDefaultCountryCode()
    )
    val contentRegion: StateFlow<String> = _contentRegion.asStateFlow()

    private fun detectDefaultCountryCode(): String {
        return try {
            val tm = getApplication<Application>().getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase()
            val netCountry = tm?.networkCountryIso?.uppercase()
            val localeCountry = java.util.Locale.getDefault().country?.uppercase()

            when {
                !simCountry.isNullOrBlank() && simCountry.length == 2 -> simCountry
                !netCountry.isNullOrBlank() && netCountry.length == 2 -> netCountry
                !localeCountry.isNullOrBlank() && localeCountry.length == 2 -> localeCountry
                else -> "BD" // Default to Bangladesh / Asian Region if undetected
            }
        } catch (e: Exception) {
            "BD"
        }
    }

    val userCountryCode: String
        get() = _contentRegion.value

    val userCountryName: String
        get() = when (_contentRegion.value.uppercase()) {
            "BD" -> "Bangladesh"
            "IN" -> "India"
            "PK" -> "Pakistan"
            "US" -> "United States"
            "GB" -> "United Kingdom"
            "CA" -> "Canada"
            "AU" -> "Australia"
            "DE" -> "Germany"
            "JP" -> "Japan"
            "KR" -> "South Korea"
            "SA" -> "Saudi Arabia"
            "AE" -> "United Arab Emirates"
            else -> java.util.Locale("", _contentRegion.value).displayCountry.ifBlank { "Bangladesh" }
        }

    fun setContentRegion(regionCode: String) {
        val code = regionCode.uppercase()
        _contentRegion.value = code
        prefs.edit().putString("content_region", code).apply()
        loadCategoryFeed(_selectedCategory.value, forceRefresh = true)
        loadShorts(forceRefresh = true)
    }

    // Material You Dynamic Color & Theme State
    private val _dynamicColorEnabled = MutableStateFlow(
        prefs.getBoolean("dynamic_color_enabled", true)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setDynamicColorEnabled(enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
        prefs.edit().putBoolean("dynamic_color_enabled", enabled).apply()
    }

    private val _themeMode = MutableStateFlow(
        prefs.getString("theme_mode", "system") ?: "system" // "system", "dark", "light"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    // Default Quality preference
    private val _defaultQuality = MutableStateFlow(
        prefs.getString("default_quality", "720p") ?: "720p"
    )
    val defaultQuality: StateFlow<String> = _defaultQuality.asStateFlow()

    fun setDefaultQuality(quality: String) {
        _defaultQuality.value = quality
        prefs.edit().putString("default_quality", quality).apply()
    }

    // Autoplay next stream
    private val _autoPlayNext = MutableStateFlow(
        prefs.getBoolean("autoplay_next", true)
    )
    val autoPlayNext: StateFlow<Boolean> = _autoPlayNext.asStateFlow()

    fun setAutoPlayNext(enabled: Boolean) {
        _autoPlayNext.value = enabled
        prefs.edit().putBoolean("autoplay_next", enabled).apply()
    }

    // Cellular Data Saver
    private val _dataSaverEnabled = MutableStateFlow(
        prefs.getBoolean("data_saver_enabled", false)
    )
    val dataSaverEnabled: StateFlow<Boolean> = _dataSaverEnabled.asStateFlow()

    fun setDataSaverEnabled(enabled: Boolean) {
        _dataSaverEnabled.value = enabled
        prefs.edit().putBoolean("data_saver_enabled", enabled).apply()
    }

    // Pause Watch History
    private val _pauseWatchHistory = MutableStateFlow(
        prefs.getBoolean("pause_watch_history", false)
    )
    val pauseWatchHistory: StateFlow<Boolean> = _pauseWatchHistory.asStateFlow()

    fun setPauseWatchHistory(paused: Boolean) {
        _pauseWatchHistory.value = paused
        prefs.edit().putBoolean("pause_watch_history", paused).apply()
    }

    // Download Directory Configuration
    private val defaultDownloadPath: String by lazy {
        val app = getApplication<Application>()
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: File(app.filesDir, "downloads").absolutePath
    }

    private val _downloadDirectoryPath = MutableStateFlow(
        prefs.getString("download_directory_path", null) ?: defaultDownloadPath
    )
    val downloadDirectoryPath: StateFlow<String> = _downloadDirectoryPath.asStateFlow()

    private val _downloadDirectoryName = MutableStateFlow(
        prefs.getString("download_directory_name", "App Internal Storage (Downloads)") ?: "App Internal Storage (Downloads)"
    )
    val downloadDirectoryName: StateFlow<String> = _downloadDirectoryName.asStateFlow()

    fun setDownloadDirectory(path: String, displayName: String) {
        val cleanPath = path.trim()
        val dir = File(cleanPath)
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        } catch (e: Exception) {
            // best effort
        }
        _downloadDirectoryPath.value = cleanPath
        _downloadDirectoryName.value = displayName
        prefs.edit()
            .putString("download_directory_path", cleanPath)
            .putString("download_directory_name", displayName)
            .apply()
        showSnackbar("Download location updated: $displayName")
    }

    fun resetDownloadDirectory() {
        setDownloadDirectory(defaultDownloadPath, "App Internal Storage (Downloads)")
    }

    // Cache Size Calculation & Clear
    private val _appCacheSizeMb = MutableStateFlow("Calculating...")
    val appCacheSizeMb: StateFlow<String> = _appCacheSizeMb.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                var totalBytes = getDirSize(context.cacheDir)
                context.externalCacheDir?.let { totalBytes += getDirSize(it) }
                val mb = totalBytes / (1024.0 * 1024.0)
                _appCacheSizeMb.value = if (mb < 0.1) "< 1 MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
            } catch (e: Exception) {
                _appCacheSizeMb.value = "Unknown"
            }
        }
    }

    private fun getDirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) getDirSize(f) else f.length()
        }
        return size
    }

    fun clearAppCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                deleteDirContent(context.cacheDir)
                context.externalCacheDir?.let { deleteDirContent(it) }
                refreshCacheSize()
                showSnackbar("Cache successfully cleared")
            } catch (e: Exception) {
                showSnackbar("Failed to clear cache: ${e.localizedMessage}")
            }
        }
    }

    private fun deleteDirContent(dir: java.io.File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) deleteDirContent(f)
            f.delete()
        }
    }

    private val _hasEnoughActivity = MutableStateFlow(false)
    val hasEnoughActivity: StateFlow<Boolean> = _hasEnoughActivity.asStateFlow()

    private val _selectedCategory = MutableStateFlow("For You")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isTrendingLoading = MutableStateFlow(false)
    val isTrendingLoading: StateFlow<Boolean> = _isTrendingLoading.asStateFlow()

    private val _feedErrorMessage = MutableStateFlow<String?>(null)
    val feedErrorMessage: StateFlow<String?> = _feedErrorMessage.asStateFlow()

    // Shorts state
    private val _shortsStreams = MutableStateFlow<List<StreamItem>>(emptyList())
    val shortsStreams: StateFlow<List<StreamItem>> = _shortsStreams.asStateFlow()

    private val _isShortsLoading = MutableStateFlow(false)
    val isShortsLoading: StateFlow<Boolean> = _isShortsLoading.asStateFlow()

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<StreamItem>>(emptyList())
    val searchResults: StateFlow<List<StreamItem>> = _searchResults.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    private val _searchFilter = MutableStateFlow("all")
    val searchFilter: StateFlow<String> = _searchFilter.asStateFlow()

    // Active details state
    private val _activeStreamDetails = MutableStateFlow<StreamDetails?>(null)
    val activeStreamDetails: StateFlow<StreamDetails?> = _activeStreamDetails.asStateFlow()

    private val _comments = MutableStateFlow<List<CommentItem>>(emptyList())
    val comments: StateFlow<List<CommentItem>> = _comments.asStateFlow()

    private val _isDetailsLoading = MutableStateFlow(false)
    val isDetailsLoading: StateFlow<Boolean> = _isDetailsLoading.asStateFlow()

    private val _channelDetails = MutableStateFlow<ChannelDetails?>(null)
    val channelDetails: StateFlow<ChannelDetails?> = _channelDetails.asStateFlow()

    private val _isChannelLoading = MutableStateFlow(false)
    val isChannelLoading: StateFlow<Boolean> = _isChannelLoading.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    // UI overlays state
    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    // Clipboard link detection state
    private val _detectedClipboardVideo = MutableStateFlow<ClipboardDetectedVideo?>(null)
    val detectedClipboardVideo: StateFlow<ClipboardDetectedVideo?> = _detectedClipboardVideo.asStateFlow()
    private var lastDismissedClipboardText: String? = null

    private val _showDownloadSheet = MutableStateFlow(false)
    val showDownloadSheet: StateFlow<Boolean> = _showDownloadSheet.asStateFlow()

    private val _showQualityDialog = MutableStateFlow(false)
    val showQualityDialog: StateFlow<Boolean> = _showQualityDialog.asStateFlow()

    private val _showSpeedDialog = MutableStateFlow(false)
    val showSpeedDialog: StateFlow<Boolean> = _showSpeedDialog.asStateFlow()

    private val _showSleepTimerDialog = MutableStateFlow(false)
    val showSleepTimerDialog: StateFlow<Boolean> = _showSleepTimerDialog.asStateFlow()

    private val _snackBarMessage = MutableStateFlow<String?>(null)
    val snackBarMessage: StateFlow<String?> = _snackBarMessage.asStateFlow()

    // Room Flows
    val historyFlow: StateFlow<List<HistoryEntity>> = historyDao.getAllHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarksFlow: StateFlow<List<BookmarkEntity>> = bookmarkDao.getAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptionsFlow: StateFlow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadsFlow: StateFlow<List<DownloadEntity>> = downloadDao.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Fast instant load on start
        loadCategoryFeed("For You")
        loadShorts()
        refreshCacheSize()

        // Track playback position in history for Continue Watching
        viewModelScope.launch {
            playbackState.collect { state ->
                val current = state.currentStream
                if (!_pauseWatchHistory.value && current != null && state.currentPositionMs > 1000L) {
                    historyDao.insertOrUpdate(
                        HistoryEntity(
                            streamId = current.id,
                            title = current.title,
                            uploaderName = current.uploaderName,
                            uploaderAvatar = current.uploaderAvatar,
                            thumbnailUrl = "https://img.youtube.com/vi/${current.id}/hqdefault.jpg",
                            durationSeconds = current.durationSeconds,
                            lastPositionMs = state.currentPositionMs,
                            watchedAtTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // Reactive personalization updates when history or subscriptions change
        viewModelScope.launch {
            historyFlow.collect { history ->
                refreshPersonalizedContent(history, subscriptionsFlow.value, bookmarksFlow.value)
            }
        }

        viewModelScope.launch {
            subscriptionsFlow.collect { subs ->
                refreshSubscriptionsFeed(subs)
            }
        }
    }

    fun loadCategoryFeed(category: String, forceRefresh: Boolean = false) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _isTrendingLoading.value = true
            _feedErrorMessage.value = null
            if (category.equals("For You", ignoreCase = true)) {
                refreshPersonalizedContent(historyFlow.value, subscriptionsFlow.value, bookmarksFlow.value)
                val res = extractor.getTrending(region = userCountryCode, category = "All", forceRefresh = forceRefresh)
                res.onSuccess { list ->
                    _trendingStreams.value = list
                    _feedErrorMessage.value = null
                    // Pre-warm top 3 streams in background for instant 0-latency playback upon tap
                    launch(Dispatchers.IO) {
                        list.take(3).forEach { item ->
                            extractor.prewarmStreamDetails(item.id)
                        }
                    }
                }.onFailure {
                    _trendingStreams.value = emptyList()
                    _feedErrorMessage.value = it.localizedMessage ?: "Failed to fetch feed via NewPipeExtractor"
                    showSnackbar("Feed load notice: ${it.localizedMessage}")
                }
            } else {
                val res = extractor.getTrending(region = userCountryCode, category = category, forceRefresh = forceRefresh)
                res.onSuccess { list ->
                    _trendingStreams.value = list
                    _feedErrorMessage.value = null
                    launch(Dispatchers.IO) {
                        list.take(3).forEach { item ->
                            extractor.prewarmStreamDetails(item.id)
                        }
                    }
                }.onFailure {
                    _trendingStreams.value = emptyList()
                    _feedErrorMessage.value = it.localizedMessage ?: "Failed to fetch feed via NewPipeExtractor"
                    showSnackbar("Feed load notice: ${it.localizedMessage}")
                }
            }
            _isTrendingLoading.value = false
        }
    }

    fun loadShorts(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isShortsLoading.value = true
            val res = extractor.getShorts(forceRefresh = forceRefresh)
            res.onSuccess {
                _shortsStreams.value = it
            }.onFailure {
                if (_shortsStreams.value.isEmpty()) {
                    // Fallback to trending streams
                    _shortsStreams.value = _trendingStreams.value
                }
            }
            _isShortsLoading.value = false
        }
    }

    private fun refreshPersonalizedContent(
        history: List<HistoryEntity>,
        subs: List<SubscriptionEntity>,
        bookmarks: List<BookmarkEntity>
    ) {
        val isSufficient = (history.size >= 3) || subs.isNotEmpty() || (bookmarks.size >= 2)
        _hasEnoughActivity.value = isSufficient

        viewModelScope.launch {
            val personalized = extractor.getPersonalizedFeed(
                history = history,
                subscriptions = subs,
                bookmarks = bookmarks,
                countryCode = userCountryCode,
                countryName = userCountryName
            )
            if (personalized.isNotEmpty()) {
                _personalizedStreams.value = personalized
            }
        }
    }

    private fun refreshSubscriptionsFeed(subs: List<SubscriptionEntity>) {
        if (subs.isEmpty()) {
            _subscriptionStreams.value = emptyList()
            return
        }
        viewModelScope.launch {
            val list = mutableListOf<StreamItem>()
            subs.take(3).forEach { sub ->
                val res = extractor.search("${sub.channelName} new")
                res.onSuccess {
                    list.addAll(it.take(3))
                }
            }
            _subscriptionStreams.value = list.distinctBy { it.id }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            viewModelScope.launch {
                _searchSuggestions.value = extractor.getSearchSuggestions(query)
            }
        } else {
            _searchSuggestions.value = emptyList()
        }
    }

    fun executeSearch(query: String = _searchQuery.value, filter: String = _searchFilter.value) {
        if (query.isBlank()) return
        _searchQuery.value = query
        _searchFilter.value = filter
        _searchSuggestions.value = emptyList()
        viewModelScope.launch {
            _isSearchLoading.value = true
            val res = extractor.search(query, filter)
            res.onSuccess { list ->
                _searchResults.value = list
                launch(Dispatchers.IO) {
                    list.take(3).forEach { item ->
                        extractor.prewarmStreamDetails(item.id)
                    }
                }
            }.onFailure {
                _searchResults.value = emptyList()
                showSnackbar("Search failed: ${it.localizedMessage}")
            }
            _isSearchLoading.value = false
        }
    }

    private var streamExtractionJob: Job? = null
    private var bookmarkCheckJob: Job? = null

    fun selectAndPlayStream(streamItem: StreamItem, preferredQuality: String = "720p", audioOnly: Boolean = false) {
        _isPlayerExpanded.value = true
        _isDetailsLoading.value = true
        _comments.value = emptyList()

        // Generate immediate stream preview with metadata
        val initialDetails = StreamDetails(
            id = streamItem.id,
            title = streamItem.title,
            uploaderName = streamItem.uploaderName,
            uploaderAvatar = streamItem.uploaderAvatar,
            uploaderUrl = streamItem.uploaderUrl,
            durationSeconds = streamItem.durationSeconds,
            views = streamItem.views,
            uploadDate = streamItem.uploadedDate,
            description = "Loading stream...",
            isLive = streamItem.isLive,
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )
        _activeStreamDetails.value = initialDetails

        bookmarkCheckJob?.cancel()
        streamExtractionJob?.cancel()

        streamExtractionJob = viewModelScope.launch {
            // Check history for existing resume position
            val historyRecord = historyDao.getHistoryById(streamItem.id)
            val durationMs = streamItem.durationSeconds * 1000L
            val resumePositionMs = if (historyRecord != null && historyRecord.lastPositionMs > 2000L && 
                (durationMs <= 0 || historyRecord.lastPositionMs < (durationMs - 3000L))) {
                historyRecord.lastPositionMs
            } else {
                0L
            }

            playbackManager.prepareStream(initialDetails, audioOnly, startPositionMs = resumePositionMs)

            // Save/preserve in Watch History
            historyDao.insertOrUpdate(
                HistoryEntity(
                    streamId = streamItem.id,
                    title = streamItem.title,
                    uploaderName = streamItem.uploaderName,
                    uploaderAvatar = streamItem.uploaderAvatar,
                    thumbnailUrl = streamItem.thumbnailUrl,
                    durationSeconds = streamItem.durationSeconds,
                    lastPositionMs = resumePositionMs,
                    watchedAtTimestamp = System.currentTimeMillis()
                )
            )

            // Check bookmark status
            bookmarkCheckJob = launch {
                bookmarkDao.isBookmarked(streamItem.id).collect {
                    _isBookmarked.value = it
                }
            }

            android.util.Log.d("MainViewModel", "Requesting stream details for ID: ${streamItem.id}, resumePos: ${resumePositionMs}ms")
            val detailsRes = extractor.getStreamDetails(streamItem.id)
            detailsRes.onSuccess { details ->
                android.util.Log.d("MainViewModel", "Received stream details for ${streamItem.id}: videos=${details.videoStreams.size}, audios=${details.audioStreams.size}")
                var finalDetails = details
                
                // If related streams are empty, fetch related/trending streams for Up Next
                if (finalDetails.relatedStreams.isEmpty()) {
                    val related = extractor.getRelatedStreams(streamItem.id, details.uploaderName)
                    if (related.isNotEmpty()) {
                        finalDetails = finalDetails.copy(relatedStreams = related)
                    }
                }

                _activeStreamDetails.value = finalDetails
                playbackManager.playStream(finalDetails, preferredQuality, audioOnly, startPositionMs = resumePositionMs)

                // Load comments asynchronously in background
                launch {
                    val commentsRes = extractor.getComments(streamItem.id)
                    commentsRes.onSuccess { _comments.value = it }
                }

                // Check channel subscription
                launch {
                    subscriptionDao.isSubscribed(details.uploaderName).collect {
                        _isSubscribed.value = it
                    }
                }
            }.onFailure {
                val errorMsg = it.message ?: "Failed to extract stream"
                android.util.Log.e("MainViewModel", "Stream extraction failed for ${streamItem.id}: $errorMsg", it)
                showSnackbar("Stream extraction error: $errorMsg")
                playbackManager.setError("Stream extraction failed: $errorMsg")
                _activeStreamDetails.value = null
            }
            _isDetailsLoading.value = false
        }
    }

    fun playLocalDownloadedFile(download: DownloadEntity) {
        _isPlayerExpanded.value = true
        viewModelScope.launch {
            val historyRecord = historyDao.getHistoryById(download.streamId)
            val durationMs = download.durationSeconds * 1000L
            val resumePositionMs = if (historyRecord != null && historyRecord.lastPositionMs > 2000L &&
                (durationMs <= 0 || historyRecord.lastPositionMs < (durationMs - 3000L))) {
                historyRecord.lastPositionMs
            } else {
                0L
            }

            playbackManager.playLocalFile(
                title = download.title,
                uploader = download.uploaderName,
                localPath = download.localFilePath,
                durationSec = download.durationSeconds,
                streamId = download.streamId,
                isAudio = download.isAudioOnly,
                thumbnailUrl = download.thumbnailUrl,
                startPositionMs = resumePositionMs
            )

            // Save in Watch History
            historyDao.insertOrUpdate(
                HistoryEntity(
                    streamId = download.streamId,
                    title = download.title,
                    uploaderName = download.uploaderName,
                    uploaderAvatar = "",
                    thumbnailUrl = download.thumbnailUrl,
                    durationSeconds = download.durationSeconds,
                    lastPositionMs = resumePositionMs,
                    watchedAtTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun onClipboardTextDetected(text: String?) {
        if (text.isNullOrBlank()) return
        val trimmed = text.trim()
        if (trimmed == lastDismissedClipboardText) return
        val videoId = com.example.util.YouTubeUrlHelper.extractVideoId(trimmed) ?: return

        // If modal already shows this video or player is already active on it, ignore
        if (_detectedClipboardVideo.value?.videoId == videoId) return
        if (playbackState.value.currentStream?.id == videoId && _isPlayerExpanded.value) return

        val initial = ClipboardDetectedVideo(
            videoId = videoId,
            rawUrl = trimmed,
            title = "YouTube Video ($videoId)",
            thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            isLoading = true
        )
        _detectedClipboardVideo.value = initial

        viewModelScope.launch {
            val res = extractor.getStreamDetails(videoId)
            res.onSuccess { details ->
                if (_detectedClipboardVideo.value?.videoId == videoId) {
                    _detectedClipboardVideo.value = _detectedClipboardVideo.value?.copy(
                        title = details.title,
                        uploaderName = details.uploaderName,
                        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                        durationSeconds = details.durationSeconds,
                        details = details,
                        isLoading = false
                    )
                }
            }.onFailure {
                if (_detectedClipboardVideo.value?.videoId == videoId) {
                    _detectedClipboardVideo.value = _detectedClipboardVideo.value?.copy(isLoading = false)
                }
            }
        }
    }

    fun dismissClipboardModal() {
        val current = _detectedClipboardVideo.value
        if (current != null) {
            lastDismissedClipboardText = current.rawUrl
        }
        _detectedClipboardVideo.value = null
    }

    fun playDetectedClipboardVideo() {
        val current = _detectedClipboardVideo.value ?: return
        val item = StreamItem(
            id = current.videoId,
            title = current.title,
            uploaderName = current.uploaderName,
            uploaderAvatar = "",
            uploaderUrl = "",
            thumbnailUrl = current.thumbnailUrl,
            durationSeconds = current.durationSeconds,
            views = 0L,
            uploadedDate = ""
        )
        dismissClipboardModal()
        selectAndPlayStream(item)
    }

    fun downloadDetectedClipboardVideo() {
        val current = _detectedClipboardVideo.value ?: return
        val details = current.details
        if (details != null) {
            _activeStreamDetails.value = details
            _showDownloadSheet.value = true
            dismissClipboardModal()
        } else {
            viewModelScope.launch {
                showSnackbar("Fetching stream details for download...")
                val res = extractor.getStreamDetails(current.videoId)
                res.onSuccess { d ->
                    _activeStreamDetails.value = d
                    _showDownloadSheet.value = true
                    dismissClipboardModal()
                }.onFailure {
                    showSnackbar("Failed to prepare download: ${it.localizedMessage}")
                }
            }
        }
    }

    fun playVideoById(videoId: String, title: String? = null) {
        val item = StreamItem(
            id = videoId,
            title = title ?: "YouTube Video ($videoId)",
            uploaderName = "YouTube",
            uploaderAvatar = "",
            uploaderUrl = "",
            thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            durationSeconds = 0L,
            views = 0L,
            uploadedDate = ""
        )
        selectAndPlayStream(item)
    }

    fun playYouTubeUrl(urlOrText: String) {
        val videoId = com.example.util.YouTubeUrlHelper.extractVideoId(urlOrText)
        if (videoId != null) {
            playVideoById(videoId)
        } else {
            showSnackbar("Could not find a valid YouTube link")
        }
    }

    fun toggleBookmark() {
        val stream = _activeStreamDetails.value ?: return
        viewModelScope.launch {
            if (_isBookmarked.value) {
                bookmarkDao.deleteByStreamId(stream.id)
                _isBookmarked.value = false
                showSnackbar("Removed from Bookmarks")
            } else {
                bookmarkDao.insert(
                    BookmarkEntity(
                        streamId = stream.id,
                        title = stream.title,
                        uploaderName = stream.uploaderName,
                        uploaderAvatar = stream.uploaderAvatar,
                        thumbnailUrl = "https://img.youtube.com/vi/${stream.id}/hqdefault.jpg",
                        durationSeconds = stream.durationSeconds
                    )
                )
                _isBookmarked.value = true
                showSnackbar("Saved to Bookmarks")
            }
        }
    }

    fun toggleSubscription() {
        val stream = _activeStreamDetails.value ?: return
        viewModelScope.launch {
            if (_isSubscribed.value) {
                subscriptionDao.unsubscribe(stream.uploaderName)
                _isSubscribed.value = false
                showSnackbar("Unsubscribed from ${stream.uploaderName}")
            } else {
                subscriptionDao.subscribe(
                    SubscriptionEntity(
                        channelId = stream.uploaderName,
                        channelName = stream.uploaderName,
                        avatarUrl = stream.uploaderAvatar,
                        subscriberCount = stream.uploaderSubscribers
                    )
                )
                _isSubscribed.value = true
                showSnackbar("Subscribed to ${stream.uploaderName}")
            }
        }
    }

    fun playShort(streamItem: StreamItem) {
        _isDetailsLoading.value = true
        val initialDetails = StreamDetails(
            id = streamItem.id,
            title = streamItem.title,
            uploaderName = streamItem.uploaderName,
            uploaderAvatar = streamItem.uploaderAvatar,
            uploaderUrl = streamItem.uploaderUrl,
            durationSeconds = streamItem.durationSeconds,
            views = streamItem.views,
            uploadDate = streamItem.uploadedDate,
            description = "",
            isLive = false,
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )
        _activeStreamDetails.value = initialDetails
        playbackManager.prepareStream(initialDetails, false)

        streamExtractionJob?.cancel()
        streamExtractionJob = viewModelScope.launch {
            val detailsRes = extractor.getStreamDetails(streamItem.id)
            detailsRes.onSuccess { details ->
                _activeStreamDetails.value = details
                playbackManager.playStream(details, "720p", false)
            }.onFailure {
                playbackManager.setError("Stream extraction failed: ${it.message}")
            }
            _isDetailsLoading.value = false
        }
    }

    fun toggleBookmarkForStream(stream: StreamItem) {
        viewModelScope.launch {
            val isBookmarked = bookmarksFlow.value.any { it.streamId == stream.id }
            if (isBookmarked) {
                bookmarkDao.deleteByStreamId(stream.id)
                showSnackbar("Removed from Bookmarks")
            } else {
                bookmarkDao.insert(
                    BookmarkEntity(
                        streamId = stream.id,
                        title = stream.title,
                        uploaderName = stream.uploaderName,
                        uploaderAvatar = stream.uploaderAvatar,
                        thumbnailUrl = "https://img.youtube.com/vi/${stream.id}/hqdefault.jpg",
                        durationSeconds = stream.durationSeconds
                    )
                )
                showSnackbar("Saved to Bookmarks")
            }
        }
    }

    fun toggleSubscriptionForChannel(channelName: String, avatarUrl: String, uploaderUrl: String) {
        viewModelScope.launch {
            val isSubscribed = subscriptionsFlow.value.any { it.channelName == channelName }
            if (isSubscribed) {
                subscriptionDao.unsubscribe(channelName)
                showSnackbar("Unsubscribed from $channelName")
            } else {
                subscriptionDao.subscribe(
                    SubscriptionEntity(
                        channelId = channelName,
                        channelName = channelName,
                        avatarUrl = avatarUrl,
                        subscriberCount = 0L
                    )
                )
                showSnackbar("Subscribed to $channelName")
            }
        }
    }

    fun loadCommentsForStream(streamId: String) {
        viewModelScope.launch {
            _isDetailsLoading.value = true
            val commentsRes = extractor.getComments(streamId)
            commentsRes.onSuccess {
                _comments.value = it
            }
            _isDetailsLoading.value = false
        }
    }

    fun openChannel(channelId: String) {
        viewModelScope.launch {
            _isChannelLoading.value = true
            val res = extractor.getChannelDetails(channelId)
            res.onSuccess {
                _channelDetails.value = it
            }
            _isChannelLoading.value = false
        }
    }

    private fun resolveDownloadUrl(details: StreamDetails, quality: String, isAudioOnly: Boolean): String? {
        return if (isAudioOnly) {
            val audioMatch = details.audioStreams.find { it.quality.contains(quality, ignoreCase = true) && it.url.isNotBlank() }
            audioMatch?.url
                ?: details.audioStreams.firstOrNull { it.url.isNotBlank() }?.url
                ?: details.videoStreams.firstOrNull { !it.isVideoOnly && !it.format.contains("m3u8", ignoreCase = true) && !it.url.contains(".m3u8") }?.url
        } else {
            val nonHlsVideos = details.videoStreams.filter {
                !it.format.contains("m3u8", ignoreCase = true) && !it.url.contains(".m3u8") && it.url.isNotBlank()
            }

            // 1. Matched progressive stream (video + audio muxed)
            val progressiveMatch = nonHlsVideos.find { !it.isVideoOnly && it.quality.contains(quality, ignoreCase = true) }
            if (progressiveMatch != null) return progressiveMatch.url

            // 2. Any non-HLS progressive video
            val anyProgressive = nonHlsVideos.find { !it.isVideoOnly }
            if (anyProgressive != null) return anyProgressive.url

            // 3. Matched adaptive video stream (e.g. 1080p/720p)
            val adaptiveMatch = nonHlsVideos.find { it.quality.contains(quality, ignoreCase = true) }
            if (adaptiveMatch != null) return adaptiveMatch.url

            // 4. Any direct video stream
            nonHlsVideos.firstOrNull()?.url
        }
    }

    fun startDownload(quality: String, isAudioOnly: Boolean, explicitStream: StreamDetails? = null) {
        val stream = explicitStream ?: _activeStreamDetails.value ?: playbackState.value.currentStream ?: return
        _showDownloadSheet.value = false

        val metadataBytes = DownloadHelper.calculateExactMetadataBytes(
            durationSeconds = stream.durationSeconds,
            quality = quality,
            isAudioOnly = isAudioOnly,
            videoStreams = stream.videoStreams,
            audioStreams = stream.audioStreams
        )

        showSnackbar("Enqueuing download: ${stream.title.take(28)} (${quality})...")

        viewModelScope.launch {
            var currentDetails = stream
            if (currentDetails.videoStreams.isEmpty() && currentDetails.audioStreams.isEmpty()) {
                val res = extractor.getStreamDetails(stream.id)
                res.onSuccess { currentDetails = it }
            }

            var downloadUrl = resolveDownloadUrl(currentDetails, quality, isAudioOnly)

            if (downloadUrl.isNullOrBlank()) {
                val freshRes = extractor.getStreamDetails(stream.id)
                val freshDetails = freshRes.getOrNull()
                if (freshDetails != null) {
                    currentDetails = freshDetails
                    downloadUrl = resolveDownloadUrl(freshDetails, quality, isAudioOnly)
                }
            }

            if (downloadUrl.isNullOrBlank()) {
                showSnackbar("Could not resolve direct download link. Please try another format.")
                return@launch
            }

            // Probe exact byte length from server (no fallback guessing)
            val probedBytes = downloadHelper.probeExactStreamSizeBytes(
                url = downloadUrl,
                durationSeconds = stream.durationSeconds
            )
            val exactBytes = if (probedBytes > 0) probedBytes else metadataBytes

            downloadHelper.startDownload(
                streamId = stream.id,
                title = stream.title,
                uploader = stream.uploaderName,
                thumbnailUrl = "https://img.youtube.com/vi/${stream.id}/hqdefault.jpg",
                downloadUrl = downloadUrl,
                durationSeconds = stream.durationSeconds,
                quality = quality,
                isAudioOnly = isAudioOnly,
                explicitSizeBytes = exactBytes
            )
        }
    }

    fun retryDownload(download: DownloadEntity) {
        showSnackbar("Retrying download: ${download.title.take(28)}...")
        viewModelScope.launch {
            val detailsRes = extractor.getStreamDetails(download.streamId)
            val details = detailsRes.getOrNull()
            if (details == null) {
                showSnackbar("Could not refresh stream link for retry.")
                return@launch
            }

            val downloadUrl = resolveDownloadUrl(details, download.quality, download.isAudioOnly)
            if (downloadUrl.isNullOrBlank()) {
                showSnackbar("Download URL unavailable for this format.")
                return@launch
            }

            val probedBytes = downloadHelper.probeExactStreamSizeBytes(
                url = downloadUrl,
                durationSeconds = download.durationSeconds
            )
            val exactBytes = if (probedBytes > 0) probedBytes else if (download.totalBytes > 0) download.totalBytes else DownloadHelper.calculateExactMetadataBytes(
                durationSeconds = download.durationSeconds,
                quality = download.quality,
                isAudioOnly = download.isAudioOnly,
                videoStreams = details.videoStreams,
                audioStreams = details.audioStreams
            )

            downloadHelper.startDownload(
                streamId = download.streamId,
                title = download.title,
                uploader = download.uploaderName,
                thumbnailUrl = download.thumbnailUrl,
                downloadUrl = downloadUrl,
                durationSeconds = download.durationSeconds,
                quality = download.quality,
                isAudioOnly = download.isAudioOnly,
                explicitSizeBytes = exactBytes,
                existingDownloadId = download.id
            )
        }
    }

    fun pauseDownload(downloadId: String) {
        viewModelScope.launch {
            downloadHelper.pauseOrCancelDownload(downloadId)
            showSnackbar("Download paused")
        }
    }

    fun deleteDownload(downloadId: String) {
        viewModelScope.launch {
            downloadHelper.deleteDownload(downloadId)
            showSnackbar("Download removed")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.clearAll()
            showSnackbar("Watch history cleared")
        }
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
        if (expanded && _activeStreamDetails.value == null) {
            val stream = playbackState.value.currentStream
            if (stream != null) {
                _activeStreamDetails.value = stream
            }
        }
    }

    fun closeMiniPlayer() {
        playbackManager.closePlayback()
        _activeStreamDetails.value = null
        _isPlayerExpanded.value = false
    }

    fun setShowDownloadSheet(show: Boolean) {
        _showDownloadSheet.value = show
    }

    fun setShowQualityDialog(show: Boolean) {
        _showQualityDialog.value = show
    }

    fun setShowSpeedDialog(show: Boolean) {
        _showSpeedDialog.value = show
    }

    fun setShowSleepTimerDialog(show: Boolean) {
        _showSleepTimerDialog.value = show
    }

    fun showSnackbar(msg: String) {
        _snackBarMessage.value = msg
    }

    fun clearSnackbar() {
        _snackBarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager.release()
    }
}
