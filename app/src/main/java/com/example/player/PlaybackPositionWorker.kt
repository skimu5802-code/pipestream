package com.example.player

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryEntity

/**
 * CoroutineWorker responsible for saving playback position into Room database
 * when media playback changes or the app enters background.
 * Works seamlessly with both online extracted streams and offline downloaded media.
 */
class PlaybackPositionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PlaybackPositionWorker"
        private const val UNIQUE_WORK_NAME = "save_playback_position_work"

        const val KEY_STREAM_ID = "key_stream_id"
        const val KEY_TITLE = "key_title"
        const val KEY_UPLOADER = "key_uploader"
        const val KEY_AVATAR = "key_avatar"
        const val KEY_THUMBNAIL = "key_thumbnail"
        const val KEY_DURATION_SEC = "key_duration_sec"
        const val KEY_POSITION_MS = "key_position_ms"
        const val KEY_IS_LOCAL = "key_is_local"

        /**
         * Enqueues an immediate one-time background task using WorkManager to persist playback position.
         */
        fun enqueue(
            context: Context,
            streamId: String,
            title: String,
            uploader: String,
            avatar: String = "",
            thumbnail: String = "",
            durationSec: Long = 0L,
            positionMs: Long = 0L,
            isLocal: Boolean = false
        ) {
            if (streamId.isBlank()) return

            val inputData = Data.Builder()
                .putString(KEY_STREAM_ID, streamId)
                .putString(KEY_TITLE, title)
                .putString(KEY_UPLOADER, uploader)
                .putString(KEY_AVATAR, avatar)
                .putString(KEY_THUMBNAIL, thumbnail)
                .putLong(KEY_DURATION_SEC, durationSec)
                .putLong(KEY_POSITION_MS, positionMs)
                .putBoolean(KEY_IS_LOCAL, isLocal)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<PlaybackPositionWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Enqueued PlaybackPositionWorker for '$title' ($streamId) at ${positionMs}ms (isLocal=$isLocal)")
        }
    }

    override suspend fun doWork(): Result {
        val streamId = inputData.getString(KEY_STREAM_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: "Untitled"
        val uploader = inputData.getString(KEY_UPLOADER) ?: ""
        val avatar = inputData.getString(KEY_AVATAR) ?: ""
        val thumbnail = inputData.getString(KEY_THUMBNAIL) ?: ""
        val durationSec = inputData.getLong(KEY_DURATION_SEC, 0L)
        val positionMs = inputData.getLong(KEY_POSITION_MS, 0L)
        val isLocal = inputData.getBoolean(KEY_IS_LOCAL, false)

        return try {
            val database = AppDatabase.getInstance(applicationContext)
            val historyDao = database.historyDao()

            // Check if existing record exists to retain artwork/metadata if not provided
            val existing = historyDao.getHistoryById(streamId)

            val finalTitle = if (title.isNotBlank() && title != "Untitled") title else (existing?.title ?: title)
            val finalUploader = if (uploader.isNotBlank()) uploader else (existing?.uploaderName ?: "")
            val finalAvatar = if (avatar.isNotBlank()) avatar else (existing?.uploaderAvatar ?: "")
            val finalThumbnail = if (thumbnail.isNotBlank()) thumbnail else (existing?.thumbnailUrl ?: "")
            val finalDuration = if (durationSec > 0) durationSec else (existing?.durationSeconds ?: 0L)

            val historyEntity = HistoryEntity(
                streamId = streamId,
                title = finalTitle,
                uploaderName = finalUploader,
                uploaderAvatar = finalAvatar,
                thumbnailUrl = finalThumbnail,
                durationSeconds = finalDuration,
                lastPositionMs = positionMs,
                watchedAtTimestamp = System.currentTimeMillis()
            )

            historyDao.insertOrUpdate(historyEntity)
            Log.i(TAG, "Successfully persisted playback position to Room: streamId=$streamId, pos=${positionMs}ms, isLocal=$isLocal")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playback position via WorkManager: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
