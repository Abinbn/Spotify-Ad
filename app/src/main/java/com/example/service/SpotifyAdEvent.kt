package com.example.service

enum class ContentKind { NONE, AD, MUSIC, PODCAST, UNKNOWN }

/** What Spotify is playing right now, according to the media session / notification. */
data class NowPlaying(
    val detected: Boolean = false,
    val isPlaying: Boolean = false,
    val isAd: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val kind: ContentKind = ContentKind.NONE,
    val source: String = "",
    val updatedAt: Long = 0L
)

/** A single metadata update emitted by the notification listener or a media session. */
data class SpotifyAdEvent(
    val trackId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val positionMillis: Long = 0L,
    val isPlaying: Boolean,
    val isAd: Boolean,
    val confidence: Float,
    val source: String,
    val kind: ContentKind = ContentKind.UNKNOWN,
    val receivedAt: Long = System.currentTimeMillis()
)
