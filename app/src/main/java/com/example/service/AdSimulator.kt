package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.RecordedAdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AdSimulator {

    private const val TAG = "AdSimulator"

    data class AdPreset(
        val title: String,
        val advertiser: String,
        val durationSec: Int,
        val sampleTranscript: String,
        val keywords: List<String>,
        val themes: List<String>
    )

    val PRESET_ADS = listOf(
        AdPreset(
            title = "Special Spotify Promotion",
            advertiser = "Spotify",
            durationSec = 20,
            sampleTranscript = "Get Spotify Premium for free. Unlimited skips, offline listening, and zero interruptions. Tap the banner to learn more.",
            keywords = listOf("free trial", "premium", "unlimited skips", "offline", "tap banner"),
            themes = listOf("Subscription", "Special Offer")
        ),
        AdPreset(
            title = "Nike Athletic Campaign",
            advertiser = "Nike",
            durationSec = 15,
            sampleTranscript = "What's stopping you from pushing beyond your limit? The all-new Nike Air Max. Available now.",
            keywords = listOf("air max", "cushioning", "shoes", "style", "run"),
            themes = listOf("Fashion & Retail", "Promo")
        ),
        AdPreset(
            title = "Fast Delivery Meal Deal",
            advertiser = "DoorDash",
            durationSec = 15,
            sampleTranscript = "Juicy burgers and refreshing shakes delivered straight to your door. Use promo code TASTY50 for 50% off.",
            keywords = listOf("burgers", "fast delivery", "promo code", "50% off", "delicious"),
            themes = listOf("Food & Dining", "Special Offer")
        )
    )

    /**
     * Purges any previously generated demo data from local storage and Room database.
     */
    fun purgeDemoData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).recordedAdDao()
                val repository = RecordedAdRepository(dao)
                val (deleted, freedBytes) = repository.purgeDemoData()
                if (deleted > 0) {
                    Log.i(TAG, "Purged $deleted demo records ($freedBytes bytes freed)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error purging demo data", e)
            }
        }
    }

    /**
     * Triggers a live ad simulation event through the detection and recording service on demand.
     */
    fun simulateLiveAdBreak(
        context: Context,
        presetIndex: Int = 0,
        simulatedDurationSec: Int = 7,
        onComplete: (() -> Unit)? = null
    ) {
        val preset = PRESET_ADS[presetIndex % PRESET_ADS.size]

        // 1. Trigger Ad Event
        val event = SpotifyAdEvent(
            trackId = "spotify:ad:live_test_${System.currentTimeMillis()}",
            trackTitle = preset.title,
            artist = preset.advertiser,
            album = "Spotify Commercial Break",
            lengthMillis = preset.durationSec * 1000L,
            isPlaying = true,
            isAdBreak = true,
            confidence = 0.98f,
            source = "Ad Break Simulator"
        )

        AdRecordingService.dispatchSpotifyEvent(context, event)

        // 2. Schedule completion after simulated duration
        CoroutineScope(Dispatchers.IO).launch {
            delay(simulatedDurationSec * 1000L)
            // Emulate music resumption
            val musicEvent = SpotifyAdEvent(
                trackId = "spotify:track:song_${System.currentTimeMillis()}",
                trackTitle = "Song Resumed",
                artist = "Music Artist",
                album = "Music Album",
                lengthMillis = 180_000L,
                isPlaying = true,
                isAdBreak = false,
                confidence = 0.10f,
                source = "Spotify Music Resumed"
            )
            AdRecordingService.dispatchSpotifyEvent(context, musicEvent)
            onComplete?.invoke()
        }
    }
}
