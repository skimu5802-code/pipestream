package com.example.data.api

import android.util.Log
import android.util.LruCache
import com.example.data.model.AudioStreamFormat
import com.example.data.model.BookmarkEntity
import com.example.data.model.ChannelDetails
import com.example.data.model.CommentItem
import com.example.data.model.HistoryEntity
import com.example.data.model.StreamChapter
import com.example.data.model.StreamDetails
import com.example.data.model.StreamItem
import com.example.data.model.SubscriptionEntity
import com.example.data.model.VideoStreamFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class ExtractorEngine {

    companion object {
        private const val TAG = "ExtractorEngine"
        const val EXTRACTOR_NAME = "NewPipeExtractor"
        const val EXTRACTOR_VERSION = "v0.26.5"
    }

    // Fast in-memory LRU cache for real extracted responses
    private val streamDetailsCache = LruCache<String, StreamDetails>(60)
    private val trendingCache = LruCache<String, List<StreamItem>>(20)
    private val searchCache = LruCache<String, List<StreamItem>>(40)

    init {
        try {
            NewPipe.init(DownloaderImpl.getInstance())
            Log.d(TAG, "NewPipeExtractor initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NewPipeExtractor: ${e.message}", e)
        }
    }

    suspend fun getTrending(region: String = "BD", category: String = "All", forceRefresh: Boolean = false): Result<List<StreamItem>> =
        withContext(Dispatchers.IO) {
            val cacheKey = "$region-$category"
            if (!forceRefresh) {
                trendingCache.get(cacheKey)?.let {
                    if (it.isNotEmpty()) return@withContext Result.success(it)
                }
            }

            try {
                val countryName = when (region.uppercase()) {
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
                    else -> region
                }

                val items: List<StreamItem>? = if (category.equals("All", ignoreCase = true) || category.equals("Trending", ignoreCase = true)) {
                    val kioskList = fetchNewPipeTrending()
                    if (!kioskList.isNullOrEmpty() && !region.equals("BD", ignoreCase = true)) {
                        kioskList
                    } else {
                        val collected = mutableListOf<StreamItem>()
                        if (region.equals("BD", ignoreCase = true)) {
                            fetchCategoryViaSearch("bangladesh trending new natok music songs")?.let { collected.addAll(it) }
                            fetchCategoryViaSearch("top trending videos bangladesh today")?.let { collected.addAll(it) }
                            fetchCategoryViaSearch("bangla entertainment tech news viral")?.let { collected.addAll(it) }
                        } else {
                            fetchCategoryViaSearch("trending in $countryName")?.let { collected.addAll(it) }
                            fetchCategoryViaSearch("top viral popular videos $countryName")?.let { collected.addAll(it) }
                        }
                        if (kioskList != null) collected.addAll(kioskList)
                        collected.distinctBy { it.id }.ifEmpty { kioskList }
                    }
                } else {
                    val query = when (category.lowercase()) {
                        "music" -> if (region.equals("BD", ignoreCase = true)) "bangla trending songs music official" else "trending music official songs $countryName"
                        "gaming" -> if (region.equals("BD", ignoreCase = true)) "bangla gaming streamer gameplay live" else "trending gaming gameplay $countryName"
                        "news" -> if (region.equals("BD", ignoreCase = true)) "bangladesh news today live bulletin" else "latest news today highlights $countryName"
                        "tech" -> if (region.equals("BD", ignoreCase = true)) "bangla tech review smartphone gadgets" else "technology review latest gadgets"
                        "podcasts" -> if (region.equals("BD", ignoreCase = true)) "bangla podcast talk show full episode" else "popular podcast full episode $countryName"
                        "natok & drama" -> "bangla new natok drama comedy full"
                        "islamic" -> "bangla waz islamic discussion quran"
                        else -> "$category trending $countryName"
                    }
                    fetchCategoryViaSearch(query)
                }

                if (!items.isNullOrEmpty()) {
                    trendingCache.put(cacheKey, items)
                    return@withContext Result.success(items)
                }
                return@withContext Result.failure(Exception("No streams returned by NewPipeExtractor for category $category in $region."))
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch error via NewPipeExtractor: ${e.message}")
                return@withContext Result.failure(Exception(e.message ?: "Could not fetch streams via NewPipeExtractor."))
            }
        }

    suspend fun getShorts(forceRefresh: Boolean = false): Result<List<StreamItem>> =
        withContext(Dispatchers.IO) {
            val cacheKey = "youtube_shorts_feed"
            if (!forceRefresh) {
                searchCache.get(cacheKey)?.let {
                    if (it.isNotEmpty()) return@withContext Result.success(it)
                }
            }

            try {
                val queries = listOf("#shorts trending", "#shorts viral", "shorts comedy", "youtube shorts")
                val results = mutableListOf<StreamItem>()
                for (query in queries) {
                    val list = fetchCategoryViaSearch(query)
                    if (!list.isNullOrEmpty()) {
                        results.addAll(list)
                    }
                    if (results.size >= 25) break
                }

                val distinctShorts = results.distinctBy { it.id }.filter {
                    it.durationSeconds in 1..90 || it.title.contains("#shorts", ignoreCase = true) || it.title.contains("shorts", ignoreCase = true)
                }.ifEmpty { results.distinctBy { it.id } }

                if (distinctShorts.isNotEmpty()) {
                    searchCache.put(cacheKey, distinctShorts)
                    return@withContext Result.success(distinctShorts)
                }
                return@withContext Result.failure(Exception("No shorts available at this time"))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load shorts: ${e.message}")
                return@withContext Result.failure(Exception(e.message ?: "Could not load shorts"))
            }
        }

    private fun fetchNewPipeTrending(): List<StreamItem>? {
        return try {
            val service = ServiceList.YouTube
            val kiosk = service.kioskList.defaultKioskExtractor
            kiosk.fetchPage()
            val items = kiosk.initialPage.items
            val realItems = mutableListOf<StreamItem>()
            for (item in items) {
                if (item is StreamInfoItem) {
                    val videoId = item.url.substringAfter("watch?v=", item.url.substringAfterLast("/"))
                    val avatarUrl = item.uploaderAvatars?.firstOrNull()?.url ?: ""
                    val thumbUrl = item.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    realItems.add(
                        StreamItem(
                            id = videoId,
                            title = item.name ?: "YouTube Stream",
                            uploaderName = item.uploaderName ?: "Creator",
                            uploaderUrl = item.uploaderUrl ?: "",
                            uploaderAvatar = avatarUrl,
                            durationSeconds = item.duration,
                            views = item.viewCount,
                            uploadedDate = item.textualUploadDate ?: "",
                            thumbnailUrl = thumbUrl,
                            isLive = false
                        )
                    )
                }
            }
            if (realItems.isNotEmpty()) realItems else null
        } catch (e: Exception) {
            Log.w(TAG, "NewPipe kiosk fetch error: ${e.message}")
            null
        }
    }

    private fun fetchCategoryViaSearch(query: String): List<StreamItem>? {
        return try {
            val service = ServiceList.YouTube
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            searchExtractor.fetchPage()
            val items = searchExtractor.initialPage.items
            val list = mutableListOf<StreamItem>()
            for (item in items) {
                if (item is StreamInfoItem) {
                    val videoId = item.url.substringAfter("watch?v=", item.url.substringAfterLast("/"))
                    val avatarUrl = item.uploaderAvatars?.firstOrNull()?.url ?: ""
                    val thumbUrl = item.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    list.add(
                        StreamItem(
                            id = videoId,
                            title = item.name ?: "YouTube Stream",
                            uploaderName = item.uploaderName ?: "Creator",
                            uploaderUrl = item.uploaderUrl ?: "",
                            uploaderAvatar = avatarUrl,
                            durationSeconds = item.duration,
                            views = item.viewCount,
                            uploadedDate = item.textualUploadDate ?: "",
                            thumbnailUrl = thumbUrl,
                            isLive = false
                        )
                    )
                }
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            Log.w(TAG, "NewPipe category search error: ${e.message}")
            null
        }
    }

    suspend fun getLocationBasedMixedFeed(
        countryCode: String = "BD",
        countryName: String = "Bangladesh"
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val cacheKey = "location_mixed_${countryCode}_$countryName"
        searchCache.get(cacheKey)?.let {
            if (it.isNotEmpty()) return@withContext it
        }

        val trendingItems = mutableListOf<StreamItem>()
        val popularItems = mutableListOf<StreamItem>()
        val recentItems = mutableListOf<StreamItem>()

        try {
            coroutineScope {
                // 1. Trending in user's country/region
                val trendingDef = async {
                    if (countryCode.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangladesh trending new videos drama songs")
                    } else {
                        fetchNewPipeTrending() ?: fetchCategoryViaSearch("trending in $countryName")
                    }
                }
                // 2. Popular & Viral hits in user's country
                val popularDef = async {
                    if (countryCode.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangla viral song drama tech entertainment")
                    } else {
                        fetchCategoryViaSearch("top popular songs and videos $countryName") ?: fetchCategoryViaSearch("viral videos $countryName")
                    }
                }
                // 3. Recent mixed videos across music, tech, entertainment
                val recentDef = async {
                    if (countryCode.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangla new releases music review podcast")
                    } else {
                        fetchCategoryViaSearch("latest music gaming tech $countryName") ?: fetchCategoryViaSearch("new releases today $countryName")
                    }
                }

                trendingDef.await()?.let { trendingItems.addAll(it) }
                popularDef.await()?.let { popularItems.addAll(it) }
                recentDef.await()?.let { recentItems.addAll(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location mixed feed fetch error: ${e.message}")
        }

        // Interleave trending, popular, and recent items for a balanced, high-retention feed
        val combined = mutableListOf<StreamItem>()
        val maxLen = maxOf(trendingItems.size, popularItems.size, recentItems.size)
        val seenIds = mutableSetOf<String>()

        for (i in 0 until maxLen) {
            if (i < trendingItems.size && seenIds.add(trendingItems[i].id)) {
                combined.add(trendingItems[i])
            }
            if (i < popularItems.size && seenIds.add(popularItems[i].id)) {
                combined.add(popularItems[i])
            }
            if (i < recentItems.size && seenIds.add(recentItems[i].id)) {
                combined.add(recentItems[i])
            }
        }

        if (combined.isNotEmpty()) {
            searchCache.put(cacheKey, combined)
        }
        combined
    }

    suspend fun getPersonalizedFeed(
        history: List<HistoryEntity>,
        subscriptions: List<SubscriptionEntity>,
        bookmarks: List<BookmarkEntity>,
        countryCode: String = "BD",
        countryName: String = "Bangladesh"
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<StreamItem>()
        val seenIds = mutableSetOf<String>()

        // 1. Gather creator names from subscriptions, recent history, and bookmarks
        val creators = mutableSetOf<String>()
        subscriptions.take(6).forEach { if (it.channelName.isNotBlank()) creators.add(it.channelName) }
        history.take(6).forEach { if (it.uploaderName.isNotBlank()) creators.add(it.uploaderName) }
        bookmarks.take(4).forEach { if (it.uploaderName.isNotBlank()) creators.add(it.uploaderName) }

        // 2. Extract key topic phrases from watch history titles (e.g. "Natok", "Review", "React", "Tutorial")
        val topicQueries = mutableListOf<String>()
        history.take(5).forEach { h ->
            val cleaned = h.title.replace(Regex("[\\[\\]\\|\\(\\)\\-\\:\\_]"), " ")
                .split(" ")
                .filter { it.length > 3 && !it.equals("video", true) && !it.equals("official", true) && !it.equals("youtube", true) }
            if (cleaned.isNotEmpty()) {
                val searchPhrase = cleaned.take(3).joinToString(" ")
                if (searchPhrase.isNotBlank()) topicQueries.add(searchPhrase)
            }
        }

        // 3. Concurrently fetch recommendations based on creators and watched topics
        try {
            coroutineScope {
                val creatorDefs = creators.take(4).map { creator ->
                    async { fetchCategoryViaSearch("$creator new") ?: fetchCategoryViaSearch(creator) }
                }
                val topicDefs = topicQueries.take(3).map { topic ->
                    async { fetchCategoryViaSearch(topic) }
                }

                creatorDefs.forEach { def ->
                    def.await()?.let { list ->
                        list.take(3).forEach { item ->
                            if (seenIds.add(item.id)) {
                                candidates.add(item)
                            }
                        }
                    }
                }

                topicDefs.forEach { def ->
                    def.await()?.let { list ->
                        list.take(3).forEach { item ->
                            if (seenIds.add(item.id)) {
                                candidates.add(item)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Personalized feed error via NewPipeExtractor: ${e.message}")
        }

        // 4. If user activity is nascent, enrich with top region mixed feed
        if (candidates.size < 12) {
            val locationMixed = getLocationBasedMixedFeed(countryCode, countryName)
            locationMixed.forEach { item ->
                if (seenIds.add(item.id)) {
                    candidates.add(item)
                }
            }
        }

        candidates.distinctBy { it.id }
    }

    suspend fun search(query: String, filter: String = "all"): Result<List<StreamItem>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext Result.success(emptyList())

            val cacheKey = "$cleanQuery-$filter"
            searchCache.get(cacheKey)?.let {
                if (it.isNotEmpty()) return@withContext Result.success(it)
            }

            try {
                val results = searchNewPipe(cleanQuery)
                if (!results.isNullOrEmpty()) {
                    searchCache.put(cacheKey, results)
                    return@withContext Result.success(results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search exception via NewPipeExtractor: ${e.message}")
            }

            Result.failure(Exception("No results found for \"$query\" via NewPipeExtractor."))
        }

    private fun searchNewPipe(query: String): List<StreamItem>? {
        return try {
            val service = ServiceList.YouTube
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            searchExtractor.fetchPage()
            val items = searchExtractor.initialPage.items
            val list = mutableListOf<StreamItem>()
            for (item in items) {
                if (item is StreamInfoItem) {
                    val videoId = item.url.substringAfter("watch?v=", item.url.substringAfterLast("/"))
                    val avatarUrl = item.uploaderAvatars?.firstOrNull()?.url ?: ""
                    val thumbUrl = item.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    list.add(
                        StreamItem(
                            id = videoId,
                            title = item.name ?: "YouTube Stream",
                            uploaderName = item.uploaderName ?: "Creator",
                            uploaderUrl = item.uploaderUrl ?: "",
                            uploaderAvatar = avatarUrl,
                            durationSeconds = item.duration,
                            views = item.viewCount,
                            uploadedDate = item.textualUploadDate ?: "",
                            thumbnailUrl = thumbUrl,
                            isLive = false
                        )
                    )
                }
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            Log.w(TAG, "NewPipe search error: ${e.message}")
            null
        }
    }

    suspend fun getSearchSuggestions(query: String): List<String> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()

            try {
                val service = ServiceList.YouTube
                val suggestions = service.suggestionExtractor?.suggestionList(cleanQuery)
                if (!suggestions.isNullOrEmpty()) {
                    return@withContext suggestions
                }
            } catch (e: Exception) {
                Log.d(TAG, "NewPipe search suggestions error: ${e.message}")
            }

            emptyList()
        }

    suspend fun prewarmStreamDetails(videoId: String) = withContext(Dispatchers.IO) {
        val cleanId = videoId.replace("/watch?v=", "").replace("watch?v=", "").trim()
        if (cleanId.isBlank() || streamDetailsCache.get(cleanId) != null) return@withContext
        try {
            val extracted = fetchNewPipeStreamDetails(cleanId)
            if (extracted != null && (extracted.videoStreams.isNotEmpty() || extracted.audioStreams.isNotEmpty())) {
                streamDetailsCache.put(cleanId, extracted)
                Log.d(TAG, "Pre-warmed stream details successfully cached for ID: $cleanId")
            }
        } catch (e: Exception) {
            // Silently ignore prewarm failures
        }
    }

    suspend fun getStreamDetails(videoId: String): Result<StreamDetails> =
        withContext(Dispatchers.IO) {
            val cleanId = videoId.replace("/watch?v=", "").replace("watch?v=", "").trim()
            if (cleanId.isBlank()) return@withContext Result.failure(Exception("Invalid video ID"))

            streamDetailsCache.get(cleanId)?.let {
                if (it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty()) {
                    return@withContext Result.success(it)
                }
            }

            // Pure NewPipeExtractor v0.26.5 extraction with automatic retry for handshake/visitor ID
            var extracted = fetchNewPipeStreamDetails(cleanId)
            if (extracted == null || (extracted.videoStreams.isEmpty() && extracted.audioStreams.isEmpty())) {
                delay(500)
                extracted = fetchNewPipeStreamDetails(cleanId)
            }

            if (extracted != null && (extracted.videoStreams.isNotEmpty() || extracted.audioStreams.isNotEmpty())) {
                streamDetailsCache.put(cleanId, extracted)
                return@withContext Result.success(extracted)
            }

            Result.failure(Exception("Could not extract stream using NewPipeExtractor. Please try again."))
        }

    private fun fetchNewPipeStreamDetails(videoId: String): StreamDetails? {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            Log.d(TAG, "Fetching StreamInfo for URL: $watchUrl")
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val videoStreams = mutableListOf<VideoStreamFormat>()
            val audioStreams = mutableListOf<AudioStreamFormat>()

            Log.d(TAG, "=== Extracted Stream Details for ID: $videoId ===")
            Log.d(TAG, "Title: ${streamInfo.name}, Duration: ${streamInfo.duration}s, Uploader: ${streamInfo.uploaderName}")
            Log.d(TAG, "Raw progressive video streams count: ${streamInfo.videoStreams?.size ?: 0}")
            Log.d(TAG, "Raw video-only streams count: ${streamInfo.videoOnlyStreams?.size ?: 0}")
            Log.d(TAG, "Raw audio streams count: ${streamInfo.audioStreams?.size ?: 0}")
            Log.d(TAG, "Raw HLS URL: ${streamInfo.hlsUrl}")

            // 1. Progressive Video Streams (audio + video muxed)
            streamInfo.videoStreams?.forEach { vs ->
                val streamUrl = vs.content ?: vs.url ?: ""
                if (streamUrl.isNotBlank()) {
                    val format = vs.getFormat()?.name ?: "mp4"
                    val quality = vs.resolution ?: "720p"
                    Log.d(TAG, "  [Progressive Video] Quality: $quality, Format: $format, Bitrate: ${vs.bitrate}, FPS: ${vs.fps}, URL: $streamUrl")
                    videoStreams.add(
                        VideoStreamFormat(
                            url = streamUrl,
                            quality = quality,
                            format = format,
                            width = vs.width,
                            height = vs.height,
                            bitrate = vs.bitrate.toLong(),
                            fps = vs.fps,
                            isVideoOnly = false
                        )
                    )
                }
            }

            // 2. Video-Only Streams (adaptive video tracks)
            streamInfo.videoOnlyStreams?.forEach { vs ->
                val streamUrl = vs.content ?: vs.url ?: ""
                if (streamUrl.isNotBlank()) {
                    val format = vs.getFormat()?.name ?: "mp4"
                    val quality = vs.resolution ?: "1080p"
                    Log.d(TAG, "  [Video-Only Stream] Quality: $quality, Format: $format, Bitrate: ${vs.bitrate}, FPS: ${vs.fps}, URL: $streamUrl")
                    videoStreams.add(
                        VideoStreamFormat(
                            url = streamUrl,
                            quality = quality,
                            format = format,
                            width = vs.width,
                            height = vs.height,
                            bitrate = vs.bitrate.toLong(),
                            fps = vs.fps,
                            isVideoOnly = true
                        )
                    )
                }
            }

            // 3. HLS Stream (if available)
            if (!streamInfo.hlsUrl.isNullOrBlank()) {
                Log.d(TAG, "  [HLS Adaptive Manifest] URL: ${streamInfo.hlsUrl}")
                videoStreams.add(
                    VideoStreamFormat(
                        url = streamInfo.hlsUrl!!,
                        quality = "Auto (HLS)",
                        format = "m3u8",
                        width = 1920,
                        height = 1080,
                        bitrate = 0,
                        fps = 30,
                        isVideoOnly = false
                    )
                )
            }

            // 4. Audio Streams
            streamInfo.audioStreams?.forEach { asStream ->
                val streamUrl = asStream.content ?: asStream.url ?: ""
                if (streamUrl.isNotBlank()) {
                    val format = asStream.getFormat()?.name ?: "m4a"
                    val quality = "${if (asStream.averageBitrate > 0) asStream.averageBitrate / 1000 else 128}kbps"
                    Log.d(TAG, "  [Audio Stream] Quality: $quality, Format: $format, Bitrate: ${asStream.averageBitrate}, Codec: ${asStream.codec}, URL: $streamUrl")
                    audioStreams.add(
                        AudioStreamFormat(
                            url = streamUrl,
                            quality = quality,
                            format = format,
                            bitrate = asStream.averageBitrate.toLong(),
                            codec = asStream.codec ?: "aac"
                        )
                    )
                }
            }

            // 5. Related Streams
            val relatedList = mutableListOf<StreamItem>()
            streamInfo.relatedItems?.forEach { item ->
                if (item is StreamInfoItem) {
                    val relId = item.url.substringAfter("watch?v=", item.url.substringAfterLast("/"))
                    val relAvatar = item.uploaderAvatars?.firstOrNull()?.url ?: ""
                    val relThumb = item.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$relId/hqdefault.jpg"
                    relatedList.add(
                        StreamItem(
                            id = relId,
                            title = item.name ?: "",
                            uploaderName = item.uploaderName ?: "",
                            uploaderUrl = item.uploaderUrl ?: "",
                            uploaderAvatar = relAvatar,
                            durationSeconds = item.duration,
                            views = item.viewCount,
                            uploadedDate = item.textualUploadDate ?: "",
                            thumbnailUrl = relThumb,
                            isLive = false
                        )
                    )
                }
            }

            // 6. Chapters if available
            val chaptersList = mutableListOf<StreamChapter>()
            // Build StreamDetails
            if (videoStreams.isNotEmpty() || audioStreams.isNotEmpty()) {
                val uploaderAvatar = streamInfo.uploaderAvatars?.firstOrNull()?.url ?: ""
                Log.d(TAG, "StreamDetails successfully built for $videoId (videos: ${videoStreams.size}, audios: ${audioStreams.size}, related: ${relatedList.size})")
                StreamDetails(
                    id = videoId,
                    title = streamInfo.name ?: "YouTube Video",
                    description = streamInfo.description?.content ?: "",
                    uploaderName = streamInfo.uploaderName ?: "Creator",
                    uploaderUrl = streamInfo.uploaderUrl ?: "",
                    uploaderAvatar = uploaderAvatar,
                    uploaderSubscribers = streamInfo.uploaderSubscriberCount,
                    views = streamInfo.viewCount,
                    likes = streamInfo.likeCount,
                    uploadDate = streamInfo.textualUploadDate ?: "",
                    durationSeconds = streamInfo.duration,
                    videoStreams = videoStreams,
                    audioStreams = audioStreams,
                    relatedStreams = relatedList,
                    chapters = chaptersList,
                    isLive = false
                )
            } else {
                Log.w(TAG, "No playable video or audio streams found for videoId: $videoId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe StreamInfo extraction error for $videoId: ${e.message}", e)
            null
        }
    }

    suspend fun getRelatedStreams(videoId: String, uploaderName: String = ""): List<StreamItem> =
        withContext(Dispatchers.IO) {
            if (uploaderName.isNotBlank()) {
                val searchRes = search(uploaderName)
                if (searchRes.isSuccess) {
                    val list = searchRes.getOrNull()?.filter { it.id != videoId } ?: emptyList()
                    if (list.isNotEmpty()) return@withContext list
                }
            }

            val trendingRes = getTrending()
            trendingRes.getOrNull()?.filter { it.id != videoId } ?: emptyList()
        }

    suspend fun getComments(videoId: String): Result<List<CommentItem>> =
        withContext(Dispatchers.IO) {
            val cleanId = videoId.replace("/watch?v=", "").replace("watch?v=", "").trim()
            try {
                val watchUrl = "https://www.youtube.com/watch?v=$cleanId"
                val commentsInfo = CommentsInfo.getInfo(ServiceList.YouTube, watchUrl)
                val list = mutableListOf<CommentItem>()
                commentsInfo.relatedItems?.forEach { c ->
                    if (c is CommentsInfoItem) {
                        list.add(
                            CommentItem(
                                id = c.url ?: "c_${System.currentTimeMillis()}",
                                author = c.uploaderName ?: "Anonymous",
                                authorAvatar = c.uploaderAvatars?.firstOrNull()?.url ?: "",
                                content = c.commentText?.content ?: c.commentText?.toString() ?: "",
                                likeCount = c.likeCount.toLong(),
                                timeAgo = c.textualUploadDate ?: ""
                            )
                        )
                    }
                }
                return@withContext Result.success(list)
            } catch (e: Exception) {
                Log.d(TAG, "NewPipeExtractor comments error: ${e.message}")
            }
            Result.success(emptyList())
        }

    suspend fun getChannelDetails(channelId: String): Result<ChannelDetails> =
        withContext(Dispatchers.IO) {
            val cleanChannelId = channelId.substringAfterLast("/")
            try {
                val channelUrl = if (channelId.startsWith("http")) channelId else "https://www.youtube.com/channel/$cleanChannelId"
                val channelInfo = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
                val avatar = channelInfo.avatars?.firstOrNull()?.url ?: ""
                val banner = channelInfo.banners?.firstOrNull()?.url ?: ""
                return@withContext Result.success(
                    ChannelDetails(
                        id = cleanChannelId,
                        name = channelInfo.name ?: "Channel",
                        avatarUrl = avatar,
                        bannerUrl = banner,
                        subscriberCount = channelInfo.subscriberCount,
                        description = channelInfo.description ?: "",
                        videos = emptyList()
                    )
                )
            } catch (e: Exception) {
                Log.d(TAG, "NewPipeExtractor channel error: ${e.message}")
            }
            Result.failure(Exception("Failed to fetch channel details via NewPipeExtractor."))
        }
}
