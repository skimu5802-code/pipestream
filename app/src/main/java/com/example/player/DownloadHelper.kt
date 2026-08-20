package com.example.player

import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.api.DownloaderImpl
import com.example.data.db.DownloadDao
import com.example.data.model.AudioStreamFormat
import com.example.data.model.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.VideoStreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadHelper(
    private val context: Context,
    private val downloadDao: DownloadDao
) {
    companion object {
        private const val TAG = "DownloadHelper"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        /**
         * Extracts exact Content-Length in bytes directly from stream URL (&clen= parameter)
         */
        fun extractExactBytesFromUrl(url: String): Long? {
            if (url.isBlank()) return null
            try {
                // 1. Uri query parameter
                val uri = android.net.Uri.parse(url)
                val clen = uri.getQueryParameter("clen")?.toLongOrNull()
                if (clen != null && clen > 0) return clen

                // 2. Regex fallback for encoded urls
                val match = Regex("[?&]clen=(\\d+)").find(url)
                val regexClen = match?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (regexClen != null && regexClen > 0) return regexClen
            } catch (e: Exception) {
                // Fallback
            }
            return null
        }

        /**
         * Resolves the exact byte size of a stream format using URL clen or metadata bitrate.
         */
        fun resolveExactFormatSize(
            qualityKey: String,
            isAudio: Boolean,
            durationSeconds: Long,
            videoStreams: List<VideoStreamFormat>,
            audioStreams: List<AudioStreamFormat>
        ): Long {
            val matchingUrl = if (isAudio) {
                audioStreams.find { it.quality.contains(qualityKey, ignoreCase = true) }?.url
                    ?: audioStreams.firstOrNull()?.url
            } else {
                videoStreams.find { it.quality.contains(qualityKey, ignoreCase = true) }?.url
                    ?: videoStreams.firstOrNull()?.url
            }

            if (!matchingUrl.isNullOrBlank()) {
                val clen = extractExactBytesFromUrl(matchingUrl)
                if (clen != null && clen > 0) {
                    return clen
                }
            }

            return calculateExactMetadataBytes(durationSeconds, qualityKey, isAudio, videoStreams, audioStreams)
        }

        fun formatBytesToMb(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            } else if (mb >= 1.0) {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            } else {
                val kb = bytes / 1024.0
                String.format(java.util.Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
            }
        }

        /**
         * Resolves the configured or custom download directory with safety checks.
         */
        fun resolveDownloadDirectory(context: Context, customPath: String? = null): File {
            if (!customPath.isNullOrBlank()) {
                try {
                    val customDir = File(customPath)
                    if (!customDir.exists()) {
                        customDir.mkdirs()
                    }
                    if (customDir.exists()) {
                        return customDir
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create or access custom directory: $customPath (${e.message})")
                }
            }
            val defaultDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            if (!defaultDir.exists()) defaultDir.mkdirs()
            return defaultDir
        }

        /**
         * Resolves the realistic byte size of a stream format using realistic bitrate and duration.
         */
        fun calculateExactMetadataBytes(
            durationSeconds: Long,
            quality: String,
            isAudioOnly: Boolean,
            videoStreams: List<VideoStreamFormat> = emptyList(),
            audioStreams: List<AudioStreamFormat> = emptyList()
        ): Long {
            val duration = if (durationSeconds > 0) durationSeconds else 210L
            val bytesPerSecond: Long = if (isAudioOnly) {
                val matchedAudio = audioStreams.find { it.quality.contains(quality, ignoreCase = true) }
                if (matchedAudio != null && matchedAudio.bitrate > 0) {
                    (matchedAudio.bitrate / 8).coerceAtLeast(12_000L)
                } else if (quality.contains("256")) {
                    32_000L // 256kbps = 32 KB/s
                } else if (quality.contains("64")) {
                    8_000L // 64kbps = 8 KB/s
                } else {
                    16_000L // Standard 128kbps AAC/M4A = 16 KB/s
                }
            } else {
                val matchedVideo = videoStreams.find { it.quality.contains(quality, ignoreCase = true) }
                if (matchedVideo != null && matchedVideo.bitrate > 0) {
                    (matchedVideo.bitrate / 8).coerceAtLeast(45_000L)
                } else when {
                    quality.contains("1080") -> 450_000L // ~3.6 Mbps = 450 KB/s
                    quality.contains("720") -> 250_000L // ~2.0 Mbps = 250 KB/s
                    quality.contains("480") -> 125_000L // ~1.0 Mbps = 125 KB/s
                    quality.contains("360") -> 65_000L  // ~520 kbps = 65 KB/s
                    quality.contains("240") -> 40_000L  // ~320 kbps = 40 KB/s
                    else -> 180_000L
                }
            }
            return (bytesPerSecond * duration).coerceAtLeast(150_000L)
        }
    }

    private val workManager by lazy { WorkManager.getInstance(context) }
    private val notificationManager by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val client: OkHttpClient by lazy {
        try {
            DownloaderImpl.getInstance().client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    /**
     * Probes the server directly to calculate the exact content byte length before download starts.
     * Accurately extracts &clen parameter and uses duration * bitrate to avoid YouTube's 14.9MB dummy chunk window.
     */
    suspend fun probeExactStreamSizeBytes(
        url: String,
        durationSeconds: Long = 0L,
        bitrate: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext 0L

        // 1. Direct query parameter inspection (&clen=12345678)
        val clenBytes = extractExactBytesFromUrl(url)
        if (clenBytes != null && clenBytes > 0) {
            Log.d(TAG, "Exact stream size resolved from clen parameter: $clenBytes bytes")
            return@withContext clenBytes
        }

        // 2. HTTP HEAD probe (only accept if not the dummy 15,624,192 chunk window)
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(headReq).execute().use { response ->
                if (response.isSuccessful) {
                    val cl = response.header("Content-Length")?.toLongOrNull()
                    // 15624192 is YouTube's internal 14.9MB chunk limit, ignore it for videos longer than 30s
                    if (cl != null && cl > 0 && (cl != 15624192L || durationSeconds < 30L)) {
                        Log.d(TAG, "Exact stream size resolved from HEAD Content-Length: $cl bytes")
                        return@withContext cl
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request probe failed: ${e.message}")
        }

        // 3. Bitrate * duration computation if network probe didn't return size
        if (bitrate > 0 && durationSeconds > 0) {
            val calculated = ((bitrate / 8.0) * durationSeconds).toLong()
            Log.d(TAG, "Exact stream size calculated from format bitrate and duration: $calculated bytes")
            return@withContext calculated
        }

        return@withContext 0L
    }

    /**
     * Enqueues a production-grade download task using WorkManager.
     */
    suspend fun startDownload(
        streamId: String,
        title: String,
        uploader: String,
        thumbnailUrl: String,
        downloadUrl: String,
        durationSeconds: Long,
        quality: String,
        isAudioOnly: Boolean,
        explicitSizeBytes: Long = 0L,
        existingDownloadId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val downloadId = existingDownloadId ?: UUID.randomUUID().toString()
        val extension = if (isAudioOnly) "m4a" else "mp4"
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
        val fileName = "${safeTitle}_${quality}_${streamId.take(6)}.$extension"

        val prefs = context.getSharedPreferences("pipestream_prefs", Context.MODE_PRIVATE)
        val savedCustomDir = prefs.getString("download_directory_path", null)
        val downloadsDir = resolveDownloadDirectory(context, savedCustomDir)
        val destinationFile = File(downloadsDir, fileName)
        val tempFile = File("${destinationFile.absolutePath}.tmp")

        // Resolve exact file size before starting (never use dummy 14.9MB)
        var exactBytes = explicitSizeBytes
        if (exactBytes <= 0L) {
            val clen = extractExactBytesFromUrl(downloadUrl)
            exactBytes = if (clen != null && clen > 0) clen else calculateExactMetadataBytes(
                durationSeconds = durationSeconds,
                quality = quality,
                isAudioOnly = isAudioOnly
            )
        }

        // Retain existing downloaded byte progress when resuming!
        val existingEntity = if (existingDownloadId != null) downloadDao.getDownloadById(existingDownloadId) else null
        val currentResumeOffset = if (tempFile.exists()) tempFile.length() else (existingEntity?.downloadedBytes ?: 0L)

        val entity = DownloadEntity(
            id = downloadId,
            streamId = streamId,
            title = title,
            uploaderName = uploader,
            thumbnailUrl = thumbnailUrl,
            localFilePath = destinationFile.absolutePath,
            durationSeconds = durationSeconds,
            totalBytes = exactBytes,
            downloadedBytes = currentResumeOffset,
            isAudioOnly = isAudioOnly,
            quality = quality,
            status = DownloadStatus.DOWNLOADING.name,
            speedBytesPerSecond = 0L,
            addedTimestamp = existingEntity?.addedTimestamp ?: System.currentTimeMillis()
        )

        downloadDao.insertOrUpdate(entity)

        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_DOWNLOAD_ID, downloadId)
            .putString(MediaDownloadWorker.KEY_STREAM_ID, streamId)
            .putString(MediaDownloadWorker.KEY_TITLE, title)
            .putString(MediaDownloadWorker.KEY_UPLOADER, uploader)
            .putString(MediaDownloadWorker.KEY_THUMBNAIL_URL, thumbnailUrl)
            .putString(MediaDownloadWorker.KEY_DOWNLOAD_URL, downloadUrl)
            .putString(MediaDownloadWorker.KEY_QUALITY, quality)
            .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO_ONLY, isAudioOnly)
            .putLong(MediaDownloadWorker.KEY_DURATION_SECONDS, durationSeconds)
            .putLong(MediaDownloadWorker.KEY_EXPECTED_BYTES, exactBytes)
            .putString(MediaDownloadWorker.KEY_LOCAL_FILE_PATH, destinationFile.absolutePath)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("download_$downloadId")
            .addTag("stream_$streamId")
            .build()

        workManager.enqueueUniqueWork(
            "work_download_$downloadId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Enqueued WorkManager download: $title (ID: $downloadId, Expected bytes: $exactBytes, Resume offset: $currentResumeOffset)")
        return@withContext downloadId
    }

    suspend fun pauseOrCancelDownload(downloadId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork("work_download_$downloadId")
        val entity = downloadDao.getDownloadById(downloadId)
        if (entity != null) {
            val tempFile = File("${entity.localFilePath}.tmp")
            val currentBytes = if (tempFile.exists()) tempFile.length() else entity.downloadedBytes
            downloadDao.insertOrUpdate(
                entity.copy(
                    status = DownloadStatus.PAUSED.name,
                    downloadedBytes = currentBytes,
                    speedBytesPerSecond = 0L
                )
            )
        }
        try {
            notificationManager.cancel(downloadId.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification on pause: ${e.message}")
        }
    }

    suspend fun deleteDownload(downloadId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork("work_download_$downloadId")
        try {
            notificationManager.cancel(downloadId.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification on delete: ${e.message}")
        }
        val entity = downloadDao.getDownloadById(downloadId)
        if (entity != null) {
            val file = File(entity.localFilePath)
            if (file.exists()) file.delete()
            val tempFile = File("${entity.localFilePath}.tmp")
            if (tempFile.exists()) tempFile.delete()
            downloadDao.deleteById(downloadId)
        }
    }
}
