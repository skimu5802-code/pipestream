package com.example.data.cache

import android.util.Log
import android.util.LruCache
import com.example.data.model.StreamDetails
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance Playback URL and StreamDetails cache.
 * Keeps extracted streaming URLs and stream metadata in-memory with TTL (Time To Live)
 * for instant 0ms playback initiation on repeat taps, related stream switching, and pre-buffered items.
 */
object PlaybackUrlCache {

    private const val TAG = "PlaybackUrlCache"
    // YouTube stream URLs are generally valid for ~6 hours; we set safe TTL to 4 hours (14,400,000 ms)
    private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L

    data class CachedPlaybackInfo(
        val details: StreamDetails,
        val timestamp: Long = System.currentTimeMillis(),
        // Map of "quality_audioOnly" to Pair(primaryUrl, optionalAudioUrl)
        val resolvedUrls: ConcurrentHashMap<String, Pair<String, String?>> = ConcurrentHashMap()
    ) {
        val isExpired: Boolean
            get() = (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS
    }

    private val cache = LruCache<String, CachedPlaybackInfo>(100)

    fun put(videoId: String, details: StreamDetails) {
        val cleanId = cleanId(videoId)
        if (cleanId.isBlank()) return
        val existing = cache.get(cleanId)
        if (existing != null && !existing.isExpired) {
            val updated = existing.copy(details = details, timestamp = System.currentTimeMillis())
            cache.put(cleanId, updated)
        } else {
            cache.put(cleanId, CachedPlaybackInfo(details = details))
        }
        Log.d(TAG, "Cached playback details for ID: $cleanId (cache size: ${cache.size()})")
    }

    fun get(videoId: String): StreamDetails? {
        val cleanId = cleanId(videoId)
        val entry = cache.get(cleanId) ?: return null
        if (entry.isExpired) {
            cache.remove(cleanId)
            Log.d(TAG, "Cache expired for ID: $cleanId")
            return null
        }
        Log.d(TAG, "Cache HIT for ID: $cleanId -> Instant playback ready")
        return entry.details
    }

    fun putResolvedUrl(
        videoId: String,
        quality: String,
        isAudioOnly: Boolean,
        primaryUrl: String,
        audioUrl: String? = null
    ) {
        val cleanId = cleanId(videoId)
        val entry = cache.get(cleanId) ?: return
        val key = makeKey(quality, isAudioOnly)
        entry.resolvedUrls[key] = Pair(primaryUrl, audioUrl)
        Log.d(TAG, "Cached direct URL for $cleanId ($key)")
    }

    fun getResolvedUrl(
        videoId: String,
        quality: String,
        isAudioOnly: Boolean
    ): Pair<String, String?>? {
        val cleanId = cleanId(videoId)
        val entry = cache.get(cleanId) ?: return null
        if (entry.isExpired) {
            cache.remove(cleanId)
            return null
        }
        val key = makeKey(quality, isAudioOnly)
        return entry.resolvedUrls[key]
    }

    fun hasValidCache(videoId: String): Boolean {
        val cleanId = cleanId(videoId)
        val entry = cache.get(cleanId) ?: return false
        return !entry.isExpired && (entry.details.videoStreams.isNotEmpty() || entry.details.audioStreams.isNotEmpty())
    }

    fun clear() {
        cache.evictAll()
    }

    private fun makeKey(quality: String, isAudioOnly: Boolean): String {
        return "${quality.lowercase()}_${if (isAudioOnly) "audio" else "video"}"
    }

    private fun cleanId(id: String): String {
        return id.replace("/watch?v=", "").replace("watch?v=", "").trim()
    }
}
