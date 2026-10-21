package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.audio.AudioFingerprintEngine

class SpotifyBroadcastReceiver : BroadcastReceiver() {

    private val tag = "SpotifyBroadcastRcvr"

    companion object {
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"
        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        const val ACTION_QUEUE_CHANGED = "com.spotify.music.queuechanged"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (AdRecordingService.isTravelMode(context)) {
            Log.d(tag, "Travel Mode active: Ignoring Spotify broadcast event")
            return
        }
        val action = intent.action ?: return
        Log.d(tag, "Received Spotify broadcast action: $action")

        val trackId = intent.getStringExtra("id")
        val track = intent.getStringExtra("track")
            ?: intent.getStringExtra("title")
            ?: intent.getStringExtra("name")
            ?: ""
        val artist = intent.getStringExtra("artist")
            ?: intent.getStringExtra("author")
            ?: ""
        val album = intent.getStringExtra("album") ?: ""
        val rawLength = try {
            val i = intent.getIntExtra("length", 0)
            if (i != 0) i.toLong() else intent.getLongExtra("length", 0L)
        } catch (_: Exception) {
            intent.getLongExtra("length", 0L)
        }
        // Spotify length can be transmitted in seconds (e.g. 15, 30, 210) or milliseconds
        val length = if (rawLength in 1..999) rawLength * 1000L else rawLength
        val rawPosition = try {
            val i = intent.getIntExtra("playbackPosition", 0)
            if (i != 0) i.toLong() else intent.getLongExtra("playbackPosition", 0L)
        } catch (_: Exception) {
            intent.getLongExtra("playbackPosition", 0L)
        }
        val playbackPosition = if (rawPosition in 1..999) rawPosition * 1000L else rawPosition
        val playing = intent.getBooleanExtra("playing", true)
        val timeSent = intent.getLongExtra("timeSent", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()

        // Evaluate with hybrid fingerprinting engine
        val fingerprint = AudioFingerprintEngine.evaluateAdBreak(
            trackId = trackId,
            trackTitle = track,
            artist = artist,
            lengthMillis = length
        )

        val isAd = fingerprint.isAdBreak ||
                track.contains("advertis", ignoreCase = true) ||
                artist.contains("advertis", ignoreCase = true) ||
                (trackId?.contains(":ad:", ignoreCase = true) == true)

        val contentType = when {
            isAd -> "Advertisement"
            length > 600_000L || album.contains("podcast", ignoreCase = true) -> "Podcast Episode"
            else -> "Music Track"
        }

        val event = SpotifyAdEvent(
            trackId = trackId,
            trackTitle = when {
                track.isNotBlank() -> track
                isAd -> "Spotify Advertisement"
                else -> "Spotify Audio Track"
            },
            artist = when {
                artist.isNotBlank() -> artist
                isAd -> "Spotify Sponsor"
                else -> "Spotify"
            },
            album = album,
            lengthMillis = length,
            currentPositionMillis = playbackPosition,
            isPlaying = playing,
            isAdBreak = isAd,
            confidence = if (isAd) maxOf(fingerprint.confidence, 0.90f) else fingerprint.confidence,
            source = "Broadcast ($action)",
            contentType = contentType,
            timestamp = timeSent
        )

        AdRecordingService.dispatchSpotifyEvent(context, event)
    }
}
