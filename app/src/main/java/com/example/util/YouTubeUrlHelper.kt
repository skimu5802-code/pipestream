package com.example.util

object YouTubeUrlHelper {
    // Matches youtube.com/watch?v=..., youtu.be/..., youtube.com/shorts/..., youtube.com/live/..., music.youtube.com/...
    private val YOUTUBE_REGEX = Regex(
        "(?:https?:\\/\\/)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com\\/(?:watch\\?(?:[^&\\s\\n\\r]+&)*v=|v\\/|embed\\/|shorts\\/|live\\/)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts 11-character YouTube video ID from a URL or raw text snippet.
     */
    fun extractVideoId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = YOUTUBE_REGEX.find(text.trim())
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Extracts the matched full YouTube URL from a raw text snippet.
     */
    fun extractFirstYouTubeUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = YOUTUBE_REGEX.find(text.trim())
        return match?.value
    }
}
