package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.R
import com.example.data.api.DownloaderImpl
import com.example.data.api.ExtractorEngine
import com.example.data.db.AppDatabase
import com.example.data.model.DownloadEntity
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MediaDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MediaDownloadWorker"
        const val CHANNEL_ID = "pipestream_download_channel"
        const val CHANNEL_NAME = "PipeStream Downloads"

        const val KEY_DOWNLOAD_ID = "key_download_id"
        const val KEY_STREAM_ID = "key_stream_id"
        const val KEY_TITLE = "key_title"
        const val KEY_UPLOADER = "key_uploader"
        const val KEY_THUMBNAIL_URL = "key_thumbnail_url"
        const val KEY_DOWNLOAD_URL = "key_download_url"
        const val KEY_QUALITY = "key_quality"
        const val KEY_IS_AUDIO_ONLY = "key_is_audio_only"
        const val KEY_DURATION_SECONDS = "key_duration_seconds"
        const val KEY_EXPECTED_BYTES = "key_expected_bytes"
        const val KEY_LOCAL_FILE_PATH = "key_local_file_path"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    private val downloadDao = AppDatabase.getInstance(context).downloadDao()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val client: OkHttpClient by lazy {
        try {
            DownloaderImpl.getInstance().client.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return@withContext Result.failure()
        val streamId = inputData.getString(KEY_STREAM_ID) ?: ""
        val title = inputData.getString(KEY_TITLE) ?: "Media Download"
        val uploader = inputData.getString(KEY_UPLOADER) ?: ""
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL) ?: ""
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val quality = inputData.getString(KEY_QUALITY) ?: "High"
        val isAudioOnly = inputData.getBoolean(KEY_IS_AUDIO_ONLY, false)
        val durationSeconds = inputData.getLong(KEY_DURATION_SECONDS, 0L)
        val initialExpectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L)
        val localFilePath = inputData.getString(KEY_LOCAL_FILE_PATH) ?: ""

        val notificationId = downloadId.hashCode()

        createNotificationChannel()

        val destFile = File(localFilePath)
        destFile.parentFile?.mkdirs()
        val tempFile = File("${localFilePath}.tmp")

        var resumeOffset = 0L
        if (tempFile.exists()) {
            resumeOffset = tempFile.length()
        }

        var totalBytes = initialExpectedBytes

        // Initial foreground notification
        try {
            val initialNotif = buildProgressNotification(
                title = title,
                progressPercent = if (totalBytes > 0) ((resumeOffset * 100) / totalBytes).toInt().coerceIn(0, 100) else 0,
                transferredBytes = resumeOffset,
                totalBytes = totalBytes,
                speedBytesPerSec = 0L,
                notificationId = notificationId
            )
            setForeground(createForegroundInfo(initialNotif, notificationId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info: ${e.message}")
        }

        var currentEntity = downloadDao.getDownloadById(downloadId) ?: DownloadEntity(
            id = downloadId,
            streamId = streamId,
            title = title,
            uploaderName = uploader,
            thumbnailUrl = thumbnailUrl,
            localFilePath = localFilePath,
            durationSeconds = durationSeconds,
            totalBytes = totalBytes,
            downloadedBytes = resumeOffset,
            isAudioOnly = isAudioOnly,
            quality = quality,
            status = DownloadStatus.DOWNLOADING.name,
            speedBytesPerSecond = 0L
        )

        downloadDao.insertOrUpdate(
            currentEntity.copy(
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = resumeOffset,
                totalBytes = totalBytes,
                speedBytesPerSecond = 0L
            )
        )

        var currentDownloaded = resumeOffset
        var activeDownloadUrl = downloadUrl
        var isFullStreamCompleted = false
        var consecutiveEmptyChunks = 0
        val maxEmptyRetries = 5

        try {
            var lastDbUpdateMs = System.currentTimeMillis()
            var lastSpeedCalcMs = System.currentTimeMillis()
            var bytesSinceSpeedCalc = 0L
            var currentSpeedBps = 0L

            val buffer = ByteArray(64 * 1024)
            val chunkSize = 5 * 1024 * 1024L // 5MB unthrottled streaming chunks

            while (!isFullStreamCompleted && !isStopped && consecutiveEmptyChunks < maxEmptyRetries) {
                if (totalBytes > 0 && currentDownloaded >= totalBytes) {
                    isFullStreamCompleted = true
                    break
                }

                // Strip any static range query parameter that would conflict with HTTP Range headers
                val cleanUrl = activeDownloadUrl.replace(Regex("[?&]range=[^&]*"), "")

                // Request next 5MB chunk
                val chunkEnd = if (totalBytes > 0) minOf(currentDownloaded + chunkSize - 1, totalBytes - 1) else (currentDownloaded + chunkSize - 1)
                val rangeHeaderValue = if (totalBytes > 0 && chunkEnd >= currentDownloaded) {
                    "bytes=$currentDownloaded-$chunkEnd"
                } else {
                    "bytes=$currentDownloaded-"
                }

                val requestBuilder = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")
                    .header("Range", rangeHeaderValue)

                var response = client.newCall(requestBuilder.build()).execute()

                // Check for URL expiration (403/410) and refresh URL if possible
                if ((response.code == 403 || response.code == 410) && streamId.isNotBlank()) {
                    response.close()
                    Log.d(TAG, "Download URL expired (HTTP ${response.code}), refreshing stream details for ID: $streamId")
                    try {
                        val refreshedDetails = ExtractorEngine().getStreamDetails(streamId).getOrNull()
                        val refreshedUrl = if (refreshedDetails != null) {
                            if (isAudioOnly) {
                                refreshedDetails.audioStreams.find { it.quality.contains(quality, ignoreCase = true) }?.url
                                    ?: refreshedDetails.audioStreams.firstOrNull()?.url
                            } else {
                                refreshedDetails.videoStreams.find { it.quality.contains(quality, ignoreCase = true) }?.url
                                    ?: refreshedDetails.videoStreams.firstOrNull()?.url
                            }
                        } else null

                        if (!refreshedUrl.isNullOrBlank()) {
                            activeDownloadUrl = refreshedUrl
                            val refreshedCleanUrl = activeDownloadUrl.replace(Regex("[?&]range=[^&]*"), "")
                            val retryRequest = Request.Builder()
                                .url(refreshedCleanUrl)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "*/*")
                                .header("Connection", "keep-alive")
                                .header("Range", rangeHeaderValue)
                            response = client.newCall(retryRequest.build()).execute()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to refresh expired download URL: ${e.message}")
                    }
                }

                if (response.code == 416) {
                    // 416 Range Not Satisfiable -> Reached end of file
                    response.close()
                    if (currentDownloaded > 0) {
                        isFullStreamCompleted = true
                    }
                    break
                }

                if (!response.isSuccessful && response.code != 206) {
                    Log.e(TAG, "Server responded with HTTP ${response.code} for $cleanUrl")
                    response.close()
                    downloadDao.insertOrUpdate(
                        currentEntity.copy(
                            status = DownloadStatus.FAILED.name,
                            downloadedBytes = currentDownloaded,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = 0L
                        )
                    )
                    showFailureNotification(title, notificationId)
                    return@withContext Result.failure()
                }

                val body = response.body
                if (body == null) {
                    Log.e(TAG, "Empty response body for $title")
                    response.close()
                    consecutiveEmptyChunks++
                    continue
                }

                // Determine accurate total byte length from Content-Range or clen
                val urlClen = DownloadHelper.extractExactBytesFromUrl(activeDownloadUrl)
                val contentRange = response.header("Content-Range")
                val contentLength = body.contentLength()
                val isPartial = response.code == 206

                var actualStartOffset = 0L
                if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
                    val totalPart = contentRange.substringAfterLast("/").trim()
                    val parsedTotal = totalPart.toLongOrNull()
                    // 15624192 is YouTube's internal 14.9MB chunk window, only accept if video is very short
                    if (parsedTotal != null && parsedTotal > 0 && (parsedTotal != 15624192L || durationSeconds < 30L)) {
                        totalBytes = parsedTotal
                    }

                    if (contentRange.contains("-")) {
                        val startStr = contentRange.substringAfter("bytes").trim().substringBefore("-").trim()
                        actualStartOffset = startStr.toLongOrNull() ?: 0L
                    }
                }

                if (urlClen != null && urlClen > 0) {
                    totalBytes = urlClen
                } else if (totalBytes <= 0) {
                    totalBytes = if (initialExpectedBytes > 0) initialExpectedBytes else if (isPartial && contentLength > 0) currentDownloaded + contentLength else contentLength
                }

                // If server sent full 200 stream from byte 0 instead of partial range, reset pointer
                if (!isPartial && response.code == 200 && currentDownloaded > 0 && actualStartOffset == 0L) {
                    currentDownloaded = 0L
                }

                val inputStream: InputStream = body.byteStream()
                val randomAccessFile = java.io.RandomAccessFile(tempFile, "rw")
                randomAccessFile.seek(currentDownloaded)

                var bytesRead: Int
                var bytesInThisChunk = 0L

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            Log.d(TAG, "Worker stopped/paused for $title at $currentDownloaded bytes")
                            randomAccessFile.close()
                            inputStream.close()
                            response.close()

                            downloadDao.insertOrUpdate(
                                currentEntity.copy(
                                    downloadedBytes = currentDownloaded,
                                    totalBytes = totalBytes,
                                    status = DownloadStatus.PAUSED.name,
                                    speedBytesPerSecond = 0L
                                )
                            )
                            notificationManager.cancel(notificationId)
                            return@withContext Result.success()
                        }

                        randomAccessFile.write(buffer, 0, bytesRead)
                        currentDownloaded += bytesRead
                        bytesInThisChunk += bytesRead
                        bytesSinceSpeedCalc += bytesRead

                        val now = System.currentTimeMillis()
                        val timeDiffSpeed = now - lastSpeedCalcMs
                        if (timeDiffSpeed >= 600) {
                            currentSpeedBps = ((bytesSinceSpeedCalc * 1000.0) / timeDiffSpeed).toLong()
                            bytesSinceSpeedCalc = 0L
                            lastSpeedCalcMs = now
                        }

                        if (now - lastDbUpdateMs >= 300) {
                            lastDbUpdateMs = now
                            val curTotal = if (totalBytes > currentDownloaded) totalBytes else currentDownloaded
                            val progressPercent = if (curTotal > 0) ((currentDownloaded * 100) / curTotal).toInt().coerceIn(0, 100) else 0

                            downloadDao.insertOrUpdate(
                                currentEntity.copy(
                                    totalBytes = curTotal,
                                    downloadedBytes = currentDownloaded,
                                    status = DownloadStatus.DOWNLOADING.name,
                                    speedBytesPerSecond = currentSpeedBps
                                )
                            )

                            try {
                                val updatedNotif = buildProgressNotification(
                                    title = title,
                                    progressPercent = progressPercent,
                                    transferredBytes = currentDownloaded,
                                    totalBytes = curTotal,
                                    speedBytesPerSec = currentSpeedBps,
                                    notificationId = notificationId
                                )
                                notificationManager.notify(notificationId, updatedNotif)
                            } catch (e: Exception) {
                                Log.w(TAG, "Notification notify failed: ${e.message}")
                            }
                        }
                    }
                } finally {
                    try {
                        randomAccessFile.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing RandomAccessFile: ${e.message}")
                    }
                    try {
                        inputStream.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing inputStream: ${e.message}")
                    }
                    try {
                        response.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing response: ${e.message}")
                    }
                }

                if (bytesInThisChunk > 0) {
                    consecutiveEmptyChunks = 0
                } else {
                    consecutiveEmptyChunks++
                }

                // Check if totalBytes reached
                if (totalBytes > 0 && currentDownloaded >= totalBytes) {
                    isFullStreamCompleted = true
                    break
                }
            }

            val isFullyDone = isFullStreamCompleted || (totalBytes > 0 && currentDownloaded >= totalBytes) || (totalBytes <= 0 && currentDownloaded > 500_000 && consecutiveEmptyChunks >= maxEmptyRetries)

            if (tempFile.exists() && tempFile.length() > 0 && isFullyDone) {
                if (destFile.exists()) destFile.delete()
                val renameSuccess = tempFile.renameTo(destFile)
                val finalFileLength = if (renameSuccess) destFile.length() else tempFile.length()

                Log.d(TAG, "Download finished completely: $title, length: $finalFileLength bytes")

                downloadDao.insertOrUpdate(
                    currentEntity.copy(
                        totalBytes = finalFileLength,
                        downloadedBytes = finalFileLength,
                        status = DownloadStatus.COMPLETED.name,
                        speedBytesPerSecond = 0L
                    )
                )

                showCompletedNotification(title, notificationId)
                return@withContext Result.success(workDataOf("filePath" to destFile.absolutePath))
            } else {
                Log.w(TAG, "Download paused or incomplete for $title: downloaded $currentDownloaded / $totalBytes bytes")
                downloadDao.insertOrUpdate(
                    currentEntity.copy(
                        totalBytes = totalBytes,
                        downloadedBytes = currentDownloaded,
                        status = if (isStopped) DownloadStatus.PAUSED.name else DownloadStatus.FAILED.name,
                        speedBytesPerSecond = 0L
                    )
                )
                if (!isStopped) {
                    showFailureNotification(title, notificationId)
                }
                return@withContext if (isStopped) Result.success() else Result.failure()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download exception for $title: ${e.message}", e)
            downloadDao.insertOrUpdate(
                currentEntity.copy(
                    status = if (isStopped) DownloadStatus.PAUSED.name else DownloadStatus.FAILED.name,
                    speedBytesPerSecond = 0L
                )
            )
            if (!isStopped) {
                showFailureNotification(title, notificationId)
            } else {
                notificationManager.cancel(notificationId)
            }
            return@withContext if (isStopped) Result.success() else Result.failure()
        }
    }

    private fun createForegroundInfo(notification: Notification, notificationId: Int): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time download progress and speed"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        title: String,
        progressPercent: Int,
        transferredBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        notificationId: Int
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val transferredMb = transferredBytes / (1024.0 * 1024.0)
        val totalMb = totalBytes / (1024.0 * 1024.0)
        val speedStr = formatSpeed(speedBytesPerSec)

        val progressInfo = if (totalBytes > 0) {
            String.format(java.util.Locale.US, "%.1f / %.1f MB • %s • %d%%", transferredMb, totalMb, speedStr, progressPercent)
        } else {
            String.format(java.util.Locale.US, "%.1f MB downloaded • %s", transferredMb, speedStr)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(progressInfo)
            .setSubText("Downloading")
            .setProgress(100, progressPercent, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun showCompletedNotification(title: String, notificationId: Int) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(notificationId, notif)
    }

    private fun showFailureNotification(title: String, notificationId: Int) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("Tap to retry in Library: $title")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(notificationId, notif)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val kbps = bytesPerSec / 1024.0
        return if (kbps >= 1024) {
            String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", kbps)
        }
    }
}
