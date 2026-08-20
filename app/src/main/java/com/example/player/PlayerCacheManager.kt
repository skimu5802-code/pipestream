package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Singleton media cache manager for ExoPlayer.
 * Caches streaming audio/video chunks directly to high-speed disk cache (up to 200MB),
 * enabling instant seeking backwards, zero-rebuffer replay, and pre-buffered stream starts.
 */
@OptIn(UnstableApi::class)
object PlayerCacheManager {

    private const val TAG = "PlayerCacheManager"
    private const val CACHE_FOLDER = "pipestream_media_cache"
    private const val MAX_CACHE_SIZE_BYTES = 200 * 1024 * 1024L // 200 MB

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache? {
        if (simpleCache == null) {
            try {
                val cacheDir = File(context.cacheDir, CACHE_FOLDER)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val dbProvider = databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
                    databaseProvider = it
                }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
                simpleCache = SimpleCache(cacheDir, evictor, dbProvider)
                Log.d(TAG, "SimpleCache initialized with size limit: ${MAX_CACHE_SIZE_BYTES / (1024 * 1024)} MB at ${cacheDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SimpleCache, using direct streaming: ${e.message}", e)
                simpleCache = null
            }
        }
        return simpleCache
    }

    /**
     * Builds a CacheDataSource Factory that seamlessly writes and reads from disk/memory cache
     * for HTTP/HTTPS streams while delegating local files (offline downloads) directly to native FileDataSource.
     */
    fun createCacheDataSourceFactory(
        context: Context,
        httpDataSourceFactory: DefaultHttpDataSource.Factory
    ): DataSource.Factory {
        return try {
            val cache = getCache(context)
            val httpProvider: DataSource.Factory = if (cache != null) {
                val cacheSinkFactory = CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)

                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setCacheWriteDataSinkFactory(cacheSinkFactory)
                    .setCacheReadDataSourceFactory(FileDataSource.Factory())
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                httpDataSourceFactory
            }

            // DefaultDataSource routes http/https to httpProvider (cached) and file:// directly to FileDataSource
            DefaultDataSource.Factory(context, httpProvider)
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to upstream DefaultDataSource factory: ${e.message}")
            DefaultDataSource.Factory(context, httpDataSourceFactory)
        }
    }

    /**
     * Releases cache on application destruction if needed.
     */
    @Synchronized
    fun releaseCache() {
        try {
            simpleCache?.release()
            simpleCache = null
            databaseProvider = null
            Log.d(TAG, "SimpleCache released successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing SimpleCache: ${e.message}")
        }
    }
}
