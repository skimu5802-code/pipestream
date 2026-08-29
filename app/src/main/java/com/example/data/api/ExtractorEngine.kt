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

                val rawItems: List<StreamItem>? = if (category.equals("All", ignoreCase = true) || category.equals("Trending", ignoreCase = true)) {
                    val kioskList = fetchNewPipeTrending()
                    val collected = mutableListOf<StreamItem>()
                    if (region.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangladesh trending new videos today 2025 2026")?.let { collected.addAll(it) }
                        fetchCategoryViaSearch("bangla new natok drama music 2025 2026")?.let { collected.addAll(it) }
                        fetchCategoryViaSearch("bangla entertainment tech news viral today")?.let { collected.addAll(it) }
                    } else {
                        fetchCategoryViaSearch("trending videos in $countryName today 2025 2026")?.let { collected.addAll(it) }
                        fetchCategoryViaSearch("top viral popular videos $countryName 2025 2026")?.let { collected.addAll(it) }
                    }
                    if (kioskList != null) collected.addAll(kioskList)
                    collected.distinctBy { it.id }.ifEmpty { kioskList }
                } else {
                    val query = when (category.lowercase()) {
                        "music" -> if (region.equals("BD", ignoreCase = true)) "bangla trending songs music official new 2025 2026" else "trending music official songs latest 2025 2026 $countryName"
                        "gaming" -> if (region.equals("BD", ignoreCase = true)) "bangla gaming streamer gameplay live 2025 2026" else "trending gaming gameplay latest 2025 2026 $countryName"
                        "news" -> if (region.equals("BD", ignoreCase = true)) "bangladesh news today live bulletin latest" else "latest news today highlights $countryName"
                        "tech" -> if (region.equals("BD", ignoreCase = true)) "bangla tech review smartphone gadgets latest 2025 2026" else "technology review latest gadgets 2025 2026"
                        "podcasts" -> if (region.equals("BD", ignoreCase = true)) "bangla podcast talk show full episode latest 2025 2026" else "popular podcast full episode latest 2025 2026 $countryName"
                        "natok & drama" -> "bangla new natok drama comedy full episode 2025 2026"
                        "islamic" -> "bangla new waz islamic discussion quran 2025 2026"
                        "live" -> if (region.equals("BD", ignoreCase = true)) "bangla news live stream today" else "live stream today $countryName"
                        else -> "$category trending latest 2025 2026 $countryName"
                    }
                    fetchCategoryViaSearch(query)
                }

                if (!rawItems.isNullOrEmpty()) {
                    val freshItems = filterAndRankByFreshness(rawItems, strictRecency = true)
                    trendingCache.put(cacheKey, freshItems)
                    return@withContext Result.success(freshItems)
                }
                return@withContext Result.failure(Exception("No streams returned by NewPipeExtractor for category $category in $region."))
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch error via NewPipeExtractor: ${e.message}")
                return@withContext Result.failure(Exception(e.message ?: "Could not fetch streams via NewPipeExtractor."))
            }
        }

    /**
     * Dynamic Smart Re-Ranking Algorithm (Local Intelligence)
     * Combines Recency, Channel Affinity, Topic Relevance, and Epsilon Exploration Jitter.
     */
    fun rankPersonalizedStreams(
        items: List<StreamItem>,
        history: List<HistoryEntity>,
        subscriptions: List<SubscriptionEntity>,
        bookmarks: List<BookmarkEntity>
    ): List<StreamItem> {
        if (items.isEmpty()) return items

        // 1. Build channel affinity frequency map
        val channelAffinity = mutableMapOf<String, Int>()
        subscriptions.forEach { sub ->
            val name = sub.channelName.trim().lowercase()
            if (name.isNotBlank()) channelAffinity[name] = (channelAffinity[name] ?: 0) + 5
        }
        history.forEach { h ->
            val name = h.uploaderName.trim().lowercase()
            if (name.isNotBlank()) channelAffinity[name] = (channelAffinity[name] ?: 0) + 2
        }
        bookmarks.forEach { b ->
            val name = b.uploaderName.trim().lowercase()
            if (name.isNotBlank()) channelAffinity[name] = (channelAffinity[name] ?: 0) + 3
        }

        // 2. Extract user topic keywords from watched history and bookmarks
        val userKeywords = mutableMapOf<String, Int>()
        val stopWords = setOf(
            "video", "official", "youtube", "the", "and", "new", "full", "hd", "2024", "2025", "2026",
            "bangla", "hindi", "english", "episode", "part", "song", "with", "for", "from"
        )
        (history.take(20).map { it.title } + bookmarks.map { it.title }).forEach { title ->
            val tokens = title.lowercase().replace(Regex("[^a-z0-9\\u0980-\\u09FF]"), " ").split("\\s+".toRegex())
            tokens.filter { it.length >= 3 && it !in stopWords }.forEach { word ->
                userKeywords[word] = (userKeywords[word] ?: 0) + 1
            }
        }

        val random = java.util.Random()

        // 3. Compute hybrid score for each item
        val scoredItems = items.map { item ->
            var score = 0

            // A. Recency & Freshness Base Score
            val date = item.uploadedDate.lowercase().trim()
            val freshnessScore = when {
                date.contains("second") || date.contains("minute") || date.contains("hour") || date.contains("just now") || item.isLive -> 1000
                date.contains("yesterday") || date.contains("1 day") || date.contains("2 day") || date.contains("3 day") || (date.contains("day") && !date.contains("year")) -> 750
                date.contains("1 week") || date.contains("2 week") || date.contains("3 week") || date.contains("4 week") || (date.contains("week") && !date.contains("year")) -> 550
                date.contains("1 month") || date.contains("2 month") || date.contains("3 month") -> 350
                date.contains("4 month") || date.contains("5 month") || date.contains("6 month") -> 200
                date.contains("1 year") -> -50
                date.contains("year") -> -600
                else -> 100
            }
            score += freshnessScore

            // B. Channel Affinity Boost
            val uploaderKey = item.uploaderName.trim().lowercase()
            channelAffinity[uploaderKey]?.let { affinityCount ->
                score += (affinityCount * 70).coerceAtMost(400)
            }

            // C. Topic & Title Keyword Relevance Boost
            val itemTokens = item.title.lowercase().replace(Regex("[^a-z0-9\\u0980-\\u09FF]"), " ").split("\\s+".toRegex())
            var keywordMatchCount = 0
            itemTokens.forEach { token ->
                userKeywords[token]?.let { weight ->
                    score += (weight * 40).coerceAtMost(200)
                    keywordMatchCount++
                }
            }

            // D. Exploration Epsilon / Dynamic Jitter (adds healthy 10-15% exploration variety)
            val jitter = random.nextInt(120)
            score += jitter

            // Bonus for live stream or fresh title indicators
            if (item.isLive || item.title.contains("2026") || item.title.contains("2025")) {
                score += 100
            }

            item to score
        }

        return scoredItems.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Infinite Pagination & Endless Dynamic Feed Engine
     * Fetches subsequent pages using query expansions and diverse category sub-genres.
     */
    suspend fun fetchNextFeedPage(
        category: String,
        pageIndex: Int,
        region: String = "BD",
        history: List<HistoryEntity> = emptyList(),
        subscriptions: List<SubscriptionEntity> = emptyList(),
        bookmarks: List<BookmarkEntity> = emptyList()
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val countryName = when (region.uppercase()) {
            "BD" -> "Bangladesh"
            "IN" -> "India"
            "PK" -> "Pakistan"
            "US" -> "United States"
            "GB" -> "United Kingdom"
            else -> region
        }

        val queries = mutableListOf<String>()
        val isBD = region.equals("BD", ignoreCase = true)

        when (category.lowercase()) {
            "for you", "all", "trending" -> {
                // Generate varied query seeds based on page index
                when (pageIndex % 6) {
                    0 -> {
                        if (isBD) {
                            queries.add("bangla trending viral video new 2025 2026")
                            queries.add("bangla entertainment comedy drama highlights")
                        } else {
                            queries.add("trending popular videos in $countryName 2025 2026")
                            queries.add("viral new hits today $countryName")
                        }
                    }
                    1 -> {
                        // User topic / creator expansion
                        subscriptions.take(3).forEach { queries.add("${it.channelName} new official 2025 2026") }
                        if (queries.isEmpty()) {
                            queries.add(if (isBD) "bangla new natok drama episode 2025 2026" else "top music and entertainment $countryName 2025 2026")
                        }
                    }
                    2 -> {
                        if (isBD) {
                            queries.add("bangla popular tech review smartphone gadgets 2025 2026")
                            queries.add("bangla talk show podcast full episode 2025 2026")
                        } else {
                            queries.add("technology gadgets reviews 2025 2026 $countryName")
                            queries.add("popular podcast highlights 2025 2026")
                        }
                    }
                    3 -> {
                        if (isBD) {
                            queries.add("bangla top music video official song 2025 2026")
                            queries.add("bangla funny viral clip comedy 2025 2026")
                        } else {
                            queries.add("trending music video official latest 2025 2026")
                            queries.add("comedy funny videos viral $countryName")
                        }
                    }
                    4 -> {
                        if (isBD) {
                            queries.add("bangla new short film drama episode 2025 2026")
                            queries.add("bangladesh latest special report news bulletin")
                        } else {
                            queries.add("new viral documentary special report $countryName")
                            queries.add("trending gaming live stream 2025 2026")
                        }
                    }
                    else -> {
                        if (isBD) {
                            queries.add("bangla new releases entertainment review today 2025 2026")
                            queries.add("bangla lifestyle vlog travel food tour 2025 2026")
                        } else {
                            queries.add("top vlog travel lifestyle highlights $countryName")
                            queries.add("popular new releases this week $countryName")
                        }
                    }
                }
            }
            "natok & drama" -> {
                when (pageIndex % 4) {
                    0 -> queries.add("bangla new natok comedy drama full episode 2025 2026")
                    1 -> queries.add("bangla telefilm popular drama new episodes 2025 2026")
                    2 -> queries.add("bangla romantic natok comedy drama serial 2025 2026")
                    else -> queries.add("bangla eid special natok superhit drama 2025 2026")
                }
            }
            "music" -> {
                when (pageIndex % 4) {
                    0 -> queries.add(if (isBD) "bangla new music video official song 2025 2026" else "top hit music songs official 2025 2026")
                    1 -> queries.add(if (isBD) "bangla acoustic unplugged live songs 2025 2026" else "top trending songs global hits 2025 2026")
                    2 -> queries.add(if (isBD) "bangla romantic lyrical song new album 2025 2026" else "new pop hiphop songs 2025 2026")
                    else -> queries.add(if (isBD) "bangla folk sufiyana fusion songs 2025 2026" else "chill lofi music playlist relaxing 2025 2026")
                }
            }
            "tech" -> {
                when (pageIndex % 4) {
                    0 -> queries.add(if (isBD) "bangla tech review smartphone unboxing 2025 2026" else "tech reviews smartphone unboxing 2025 2026")
                    1 -> queries.add(if (isBD) "bangla gadget review laptop camera pc build" else "gadgets laptop camera AI tech 2025 2026")
                    2 -> queries.add(if (isBD) "bangla best budget smartphone comparison 2025 2026" else "budget smartphone comparisons 2025 2026")
                    else -> queries.add(if (isBD) "bangla future technology AI coding tips" else "AI tools future tech trends 2025 2026")
                }
            }
            "gaming" -> {
                when (pageIndex % 4) {
                    0 -> queries.add(if (isBD) "bangla gaming live stream gameplay 2025 2026" else "trending gaming gameplay highlights 2025 2026")
                    1 -> queries.add(if (isBD) "bangla pubg gta freefire gameplay funny moments" else "gaming funny moments epic highlights 2025 2026")
                    2 -> queries.add(if (isBD) "bangla esports tournament highlights" else "esports tournament highlights finals 2025 2026")
                    else -> queries.add(if (isBD) "bangla horror gameplay pc gaming walkthorough" else "new game trailer gameplay walkthrough 2025 2026")
                }
            }
            "news" -> {
                when (pageIndex % 3) {
                    0 -> queries.add(if (isBD) "bangladesh news today live bulletin latest" else "latest news headlines today live $countryName")
                    1 -> queries.add(if (isBD) "bangla breaking news highlights talk show" else "world news analysis special report today")
                    else -> queries.add(if (isBD) "bangladesh international news today" else "breaking news live updates today")
                }
            }
            "podcasts" -> {
                when (pageIndex % 3) {
                    0 -> queries.add(if (isBD) "bangla podcast full episode talk show 2025 2026" else "popular podcast full episode 2025 2026")
                    1 -> queries.add(if (isBD) "bangla interview conversation podcast celebrity" else "deep conversation podcast interview 2025 2026")
                    else -> queries.add(if (isBD) "bangla motivational inspirational podcast" else "motivational podcast self improvement 2025 2026")
                }
            }
            "islamic" -> {
                when (pageIndex % 3) {
                    0 -> queries.add("bangla new waz islamic discussion quran 2025 2026")
                    1 -> queries.add("bangla islamic lecture quran tafseer full waz 2025 2026")
                    else -> queries.add("bangla beautiful quran recitation nasheed 2025 2026")
                }
            }
            else -> {
                queries.add("$category trending latest 2025 2026 $countryName")
                queries.add("$category new episodes popular 2025 2026")
            }
        }

        val results = mutableListOf<StreamItem>()
        coroutineScope {
            val defs = queries.map { q ->
                async { fetchCategoryViaSearch(q) }
            }
            defs.forEach { def ->
                def.await()?.let { results.addAll(it) }
            }
        }

        val freshRanked = filterAndRankByFreshness(results.distinctBy { it.id }, strictRecency = true)
        if (category.equals("For You", ignoreCase = true) && (history.isNotEmpty() || subscriptions.isNotEmpty())) {
            rankPersonalizedStreams(freshRanked, history, subscriptions, bookmarks)
        } else {
            freshRanked
        }
    }

    /**
     * YouTube-Style Recency & Freshness Scorer
     * Filters out stale content (e.g. 2+ or 4+ years old) and prioritizes recent, viral, and newly released videos.
     */
    fun filterAndRankByFreshness(items: List<StreamItem>, strictRecency: Boolean = true): List<StreamItem> {
        if (items.isEmpty()) return items

        fun calculateFreshnessScore(item: StreamItem): Int {
            val date = item.uploadedDate.lowercase().trim()
            val title = item.title.lowercase()

            // Calculate base date score
            var score = when {
                date.contains("second") || date.contains("minute") || date.contains("hour") || date.contains("just now") || item.isLive -> 1000
                date.contains("yesterday") || date.contains("1 day") || date.contains("2 day") || date.contains("3 day") || date.contains("4 day") || date.contains("5 day") || date.contains("6 day") || (date.contains("day") && !date.contains("year")) -> 800
                date.contains("1 week") || date.contains("2 week") || date.contains("3 week") || date.contains("4 week") || (date.contains("week") && !date.contains("year")) -> 650
                date.contains("1 month") || date.contains("2 month") || date.contains("3 month") -> 450
                date.contains("4 month") || date.contains("5 month") || date.contains("6 month") -> 300
                date.contains("7 month") || date.contains("8 month") || date.contains("9 month") || date.contains("10 month") || date.contains("11 month") -> 150
                date.contains("1 year") -> 0
                date.contains("2 year") -> -350
                date.contains("3 year") || date.contains("4 year") || date.contains("5 year") || date.contains("6 year") || date.contains("7 year") || date.contains("8 year") || date.contains("9 year") || date.contains("10 year") || date.contains("year") -> -900
                else -> 100 // Unknown / specific date format
            }

            // Boost for modern keywords in title
            if (title.contains("2026") || title.contains("2025") || title.contains("new episode") || title.contains("official trailer") || title.contains("new song") || title.contains("highlights") || title.contains("today")) {
                score += 150
            }

            // Penalize old years in title
            if (title.contains("2017") || title.contains("2018") || title.contains("2019") || title.contains("2020") || title.contains("2021") || title.contains("2022") || title.contains("2023")) {
                score -= 500
            }

            return score
        }

        val scored = items.map { item ->
            item to calculateFreshnessScore(item)
        }

        // Strict mode: Filter out stale videos (score < 0, i.e., > 1-2 years old)
        val freshList = scored.filter { it.second >= 0 }
        val effectiveList = if (strictRecency && freshList.size >= 5) {
            freshList
        } else {
            scored
        }

        return effectiveList
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
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
                        fetchCategoryViaSearch("bangladesh trending new videos drama songs 2025 2026")
                    } else {
                        fetchNewPipeTrending() ?: fetchCategoryViaSearch("trending in $countryName 2025 2026")
                    }
                }
                // 2. Popular & Viral hits in user's country
                val popularDef = async {
                    if (countryCode.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangla viral song drama tech entertainment 2025 2026")
                    } else {
                        fetchCategoryViaSearch("top popular songs and videos $countryName 2025 2026") ?: fetchCategoryViaSearch("viral videos $countryName")
                    }
                }
                // 3. Recent mixed videos across music, tech, entertainment
                val recentDef = async {
                    if (countryCode.equals("BD", ignoreCase = true)) {
                        fetchCategoryViaSearch("bangla new releases music review podcast today 2025 2026")
                    } else {
                        fetchCategoryViaSearch("latest music gaming tech $countryName 2025 2026") ?: fetchCategoryViaSearch("new releases today $countryName")
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

        val freshCombined = filterAndRankByFreshness(combined, strictRecency = true)
        if (freshCombined.isNotEmpty()) {
            searchCache.put(cacheKey, freshCombined)
        }
        freshCombined
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

        // 3. Concurrently fetch recommendations based on creators and watched topics with recency bias
        try {
            coroutineScope {
                val creatorDefs = creators.take(4).map { creator ->
                    async { fetchCategoryViaSearch("$creator latest new 2025 2026") ?: fetchCategoryViaSearch("$creator new") }
                }
                val topicDefs = topicQueries.take(3).map { topic ->
                    async { fetchCategoryViaSearch("$topic latest new 2025 2026") ?: fetchCategoryViaSearch(topic) }
                }

                creatorDefs.forEach { def ->
                    def.await()?.let { list ->
                        list.take(4).forEach { item ->
                            if (seenIds.add(item.id)) {
                                candidates.add(item)
                            }
                        }
                    }
                }

                topicDefs.forEach { def ->
                    def.await()?.let { list ->
                        list.take(4).forEach { item ->
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

        val rankedCandidates = rankPersonalizedStreams(candidates, history, subscriptions, bookmarks)
        filterAndRankByFreshness(rankedCandidates, strictRecency = true)
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
