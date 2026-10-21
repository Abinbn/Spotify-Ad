package com.example.service

data class SpotifyNowPlaying(
    val isSpotifyDetected: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val totalLengthMillis: Long = 0L,
    val currentPositionMillis: Long = 0L,
    val lastDetectedTimestamp: Long = 0L,
    val isAdBreak: Boolean = false,
    val contentType: String = "Idle",
    val source: String = ""
)

data class SpotifyAdEvent(
    val trackId: String?,
    val trackTitle: String,
    val artist: String,
    val album: String,
    val lengthMillis: Long,
    val currentPositionMillis: Long = 0L,
    val isPlaying: Boolean,
    val isAdBreak: Boolean,
    val confidence: Float,
    val source: String,
    val contentType: String = "Music Track",
    val timestamp: Long = System.currentTimeMillis()
)
