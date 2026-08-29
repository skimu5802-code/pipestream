package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

data class StreamItem(
    val id: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String = "",
    val uploaderAvatar: String = "",
    val durationSeconds: Long = 0,
    val views: Long = 0,
    val uploadedDate: String = "",
    val thumbnailUrl: String = "",
    val isLive: Boolean = false
) {
    val formattedDuration: String
        get() {
            if (isLive) return "LIVE"
            if (durationSeconds <= 0) return "--:--"
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

    val formattedViews: String
        get() {
            return when {
                views >= 1_000_000_000 -> String.format("%.1fB views", views / 1_000_000_000.0)
                views >= 1_000_000 -> String.format("%.1fM views", views / 1_000_000.0)
                views >= 1_000 -> String.format("%.1fK views", views / 1_000.0)
                views > 0 -> "$views views"
                else -> ""
            }
        }
}

data class StreamDetails(
    val id: String,
    val title: String,
    val description: String = "",
    val uploaderName: String = "",
    val uploaderUrl: String = "",
    val uploaderAvatar: String = "",
    val uploaderSubscribers: Long = 0,
    val views: Long = 0,
    val likes: Long = 0,
    val uploadDate: String = "",
    val durationSeconds: Long = 0,
    val videoStreams: List<VideoStreamFormat> = emptyList(),
    val audioStreams: List<AudioStreamFormat> = emptyList(),
    val relatedStreams: List<StreamItem> = emptyList(),
    val chapters: List<StreamChapter> = emptyList(),
    val isLive: Boolean = false
) {
    fun toStreamItem(): StreamItem = StreamItem(
        id = id,
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = uploaderAvatar,
        durationSeconds = durationSeconds,
        views = views,
        uploadedDate = uploadDate,
        thumbnailUrl = "https://img.youtube.com/vi/$id/hqdefault.jpg",
        isLive = isLive
    )
}

data class VideoStreamFormat(
    val url: String,
    val quality: String, // e.g. "1080p", "720p", "480p", "360p", "240p"
    val format: String = "mp4",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0,
    val fps: Int = 30,
    val isVideoOnly: Boolean = false
)

data class AudioStreamFormat(
    val url: String,
    val quality: String, // e.g. "256kbps", "128kbps"
    val format: String = "m4a",
    val bitrate: Long = 0,
    val codec: String = "aac"
)

data class StreamChapter(
    val title: String,
    val startSeconds: Long
)

data class CommentItem(
    val id: String,
    val author: String,
    val authorAvatar: String = "",
    val content: String,
    val likeCount: Long = 0,
    val timeAgo: String = ""
)

data class ChannelDetails(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val subscriberCount: Long = 0,
    val description: String = "",
    val videos: List<StreamItem> = emptyList()
)

@Entity(tableName = "watch_history")
data class HistoryEntity(
    @PrimaryKey val streamId: String,
    val title: String,
    val uploaderName: String,
    val uploaderAvatar: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val lastPositionMs: Long,
    val watchedAtTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val streamId: String,
    val title: String,
    val uploaderName: String,
    val uploaderAvatar: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val playlistName: String = "Favorites",
    val addedAtTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val avatarUrl: String,
    val subscriberCount: Long = 0,
    val subscribedAtTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val streamId: String,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String,
    val localFilePath: String,
    val durationSeconds: Long,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val isAudioOnly: Boolean = false,
    val quality: String = "720p",
    val status: String = "COMPLETED", // DOWNLOADING, COMPLETED, PAUSED, FAILED
    val speedBytesPerSecond: Long = 0L,
    val addedTimestamp: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    PAUSED,
    FAILED
}

data class ClipboardDetectedVideo(
    val videoId: String,
    val rawUrl: String,
    val title: String = "YouTube Video ($videoId)",
    val uploaderName: String = "YouTube",
    val thumbnailUrl: String = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
    val durationSeconds: Long = 0L,
    val details: StreamDetails? = null,
    val isLoading: Boolean = true
)

