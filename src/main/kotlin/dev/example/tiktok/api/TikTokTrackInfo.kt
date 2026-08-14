package dev.example.tiktok.api

data class TikTokTrackInfo(
    val identifier: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    val streamUrl: String,
    val isAudioOnly: Boolean,
    val artworkUrl: String?,
    val sourceUrl: String
)
