package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerManager
import com.example.audio.ExportManager
import com.example.audio.PlaybackState
import com.example.data.AppDatabase
import com.example.data.RecordedAdEntity
import com.example.data.RecordedAdRepository
import com.example.service.AdRecordingService
import com.example.service.AdSimulator
import com.example.service.MonitoringState
import com.example.service.ServiceUiState
import com.example.service.SpotifyNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class SortOrder(val label: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    DURATION_DESC("Longest Duration"),
    DURATION_ASC("Shortest Duration")
}

data class UiNotification(
    val message: String,
    val isError: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordedAdRepository
    val playerManager: AudioPlayerManager = AudioPlayerManager(application)

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedThemeFilter = MutableStateFlow("All")
    val selectedThemeFilter: StateFlow<String> = _selectedThemeFilter.asStateFlow()

    private val _uiNotification = MutableStateFlow<UiNotification?>(null)
    val uiNotification: StateFlow<UiNotification?> = _uiNotification.asStateFlow()

    private val _inspectingAd = MutableStateFlow<RecordedAdEntity?>(null)
    val inspectingAd: StateFlow<RecordedAdEntity?> = _inspectingAd.asStateFlow()

    private val _oldAdsEligibleCount = MutableStateFlow(0)
    val oldAdsEligibleCount: StateFlow<Int> = _oldAdsEligibleCount.asStateFlow()

    val serviceState: StateFlow<ServiceUiState> = AdRecordingService.serviceUiState
    val spotifyNowPlaying: StateFlow<com.example.service.SpotifyNowPlaying> = AdRecordingService.spotifyNowPlaying
    val playerState: StateFlow<PlaybackState> = playerManager.playbackState

    init {
        val dao = AppDatabase.getInstance(application).recordedAdDao()
        repository = RecordedAdRepository(dao)

        // Purge any placeholder or demo data per user request
        viewModelScope.launch {
            AdSimulator.purgeDemoData(application)
            // Automated 30-day cleanup on startup
            val (deleted, freedBytes) = repository.cleanupOldRecordings(days = 30)
            if (deleted > 0) {
                _uiNotification.value = UiNotification("Cleaned up $deleted ads older than 30 days (${freedBytes / 1024} KB freed)")
            }
            refreshOldAdsCount()
        }
    }

    // Reactive ad list with real-time filtering and sorting
    val recordedAds: StateFlow<List<RecordedAdEntity>> = combine(
        repository.allAds,
        _sortOrder,
        _searchQuery,
        _selectedThemeFilter
    ) { ads, sort, query, themeFilter ->
        var list = ads

        // Filter by theme
        if (themeFilter != "All") {
            list = list.filter {
                it.themeTags.contains(themeFilter, ignoreCase = true) ||
                        it.spokenKeywords.contains(themeFilter, ignoreCase = true)
            }
        }

        // Filter by search query
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(q) ||
                        it.advertiser.lowercase().contains(q) ||
                        it.spokenKeywords.lowercase().contains(q) ||
                        it.themeTags.lowercase().contains(q)
            }
        }

        // Sort
        when (sort) {
            SortOrder.DATE_DESC -> list.sortedByDescending { it.timestamp }
            SortOrder.DATE_ASC -> list.sortedBy { it.timestamp }
            SortOrder.DURATION_DESC -> list.sortedByDescending { it.durationMillis }
            SortOrder.DURATION_ASC -> list.sortedBy { it.durationMillis }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setThemeFilter(theme: String) {
        _selectedThemeFilter.value = theme
    }

    fun inspectAd(ad: RecordedAdEntity?) {
        _inspectingAd.value = ad
    }

    fun clearNotification() {
        _uiNotification.value = null
    }

    /**
     * Toggles Travel / Inactive mode to completely shut down the background monitor while travelling.
     */
    fun toggleTravelInactiveMode(context: Context) {
        val currentTravel = serviceState.value.isTravelModeActive
        val newTravelState = !currentTravel

        AdRecordingService.setTravelModePreference(context, newTravelState)
        val intent = Intent(context, AdRecordingService::class.java).apply {
            action = AdRecordingService.ACTION_SET_TRAVEL_MODE
            putExtra("travel_mode", newTravelState)
        }
        if (newTravelState) {
            try {
                context.startService(intent)
            } catch (_: Exception) {}
            _uiNotification.value = UiNotification("Travel Inactive Mode ON: Service completely disabled to save battery")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    try {
                        context.startService(intent)
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
            _uiNotification.value = UiNotification("Ad Monitor Resumed")
            SpotifyNotificationListener.queryActiveSpotifyMediaSession(context)
        }
    }

    /**
     * Performs automated cleanup manually on demand: deletes recordings older than 30 days unless marked Favorite.
     */
    fun performManualCleanup(context: Context) {
        viewModelScope.launch {
            val (deleted, freedBytes) = repository.cleanupOldRecordings(days = 30)
            refreshOldAdsCount()
            if (deleted > 0) {
                _uiNotification.value = UiNotification("Storage Cleaned: $deleted ads deleted (${freedBytes / 1024} KB freed)")
            } else {
                _uiNotification.value = UiNotification("Storage is clean: No non-favorite ads older than 30 days")
            }
        }
    }

    fun deleteAllNonFavoriteAds(context: Context) {
        viewModelScope.launch {
            val (deleted, freedBytes) = repository.deleteAllNonFavorites()
            refreshOldAdsCount()
            _uiNotification.value = UiNotification("Deleted $deleted non-starred ads (${freedBytes / 1024} KB freed)")
        }
    }

    fun clearAllRecordings(context: Context) {
        viewModelScope.launch {
            playerManager.stop()
            val (deleted, freedBytes) = repository.deleteAll()
            refreshOldAdsCount()
            _uiNotification.value = UiNotification("Cleared all recordings ($deleted deleted, ${freedBytes / 1024} KB freed)")
        }
    }

    private suspend fun refreshOldAdsCount() {
        _oldAdsEligibleCount.value = repository.countOldRecordings(days = 30)
    }

    fun toggleMonitoring(context: Context) {
        val currentState = serviceState.value.state
        val isCurrentlyActive = serviceState.value.isRunning && currentState != MonitoringState.IDLE

        if (isCurrentlyActive) {
            val intent = Intent(context, AdRecordingService::class.java).apply {
                action = AdRecordingService.ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to stop service", e)
            }
            AdRecordingService.setServiceState(
                serviceState.value.copy(
                    isRunning = false,
                    state = MonitoringState.IDLE
                )
            )
            _uiNotification.value = UiNotification("Ad Monitor Paused")
        } else {
            // Turning ON monitoring
            AdRecordingService.setTravelModePreference(context, false)
            AdRecordingService.setServiceState(
                serviceState.value.copy(
                    isRunning = true,
                    isTravelModeActive = false,
                    state = MonitoringState.SLEEPING
                )
            )
            val intent = Intent(context, AdRecordingService::class.java).apply {
                action = AdRecordingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    try {
                        context.startService(intent)
                    } catch (e2: Exception) {
                        android.util.Log.e("MainViewModel", "Failed to start service", e2)
                    }
                }
            } else {
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Failed to start service", e)
                }
            }
            _uiNotification.value = UiNotification("Ad Monitor Active (Listening for Spotify)")
            // Query current active Spotify media sessions immediately
            SpotifyNotificationListener.queryActiveSpotifyMediaSession(context)
            SpotifyNotificationListener.rebindIfPossible(context)
        }
    }

    fun playAd(ad: RecordedAdEntity) {
        playerManager.play(ad.id, ad.filePath)
    }

    fun pausePlayback() {
        playerManager.pause()
    }

    fun resumePlayback() {
        playerManager.resume()
    }

    fun seekPlayback(positionMs: Long) {
        playerManager.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerManager.setSpeed(speed)
    }

    fun deleteAd(ad: RecordedAdEntity) {
        viewModelScope.launch {
            if (playerState.value.adId == ad.id) {
                playerManager.stop()
            }
            repository.delete(ad)
            _uiNotification.value = UiNotification("Deleted: \"${ad.title}\"")
            refreshOldAdsCount()
        }
    }

    fun toggleFavorite(ad: RecordedAdEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(ad.id, !ad.isFavorite)
            refreshOldAdsCount()
        }
    }

    fun updateAdMetadata(id: Long, newTitle: String, newAdvertiser: String, newThemes: String) {
        viewModelScope.launch {
            repository.updateMetadata(id, newTitle, newAdvertiser, newThemes)
            _inspectingAd.value = null
            _uiNotification.value = UiNotification("Metadata updated")
        }
    }

    fun exportAdToMusic(context: Context, ad: RecordedAdEntity) {
        viewModelScope.launch {
            val result = ExportManager.exportToPublicMusic(context, ad)
            result.onSuccess {
                _uiNotification.value = UiNotification("Exported MP3 to Music/SpotifyAds folder!")
            }.onFailure { err ->
                _uiNotification.value = UiNotification("Export failed: ${err.message}", isError = true)
            }
        }
    }

    fun shareAd(context: Context, ad: RecordedAdEntity) {
        ExportManager.shareAd(context, ad)
    }

    fun triggerSimulation(context: Context, presetIndex: Int) {
        val isRunning = serviceState.value.isRunning
        if (!isRunning) {
            val intent = Intent(context, AdRecordingService::class.java).apply {
                action = AdRecordingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        _uiNotification.value = UiNotification("Capturing live test ad break...")
        AdSimulator.simulateLiveAdBreak(context, presetIndex, simulatedDurationSec = 6) {
            _uiNotification.value = UiNotification("Test ad recorded and tagged successfully!")
        }
    }

    fun stopRecordingNow(context: Context) {
        val intent = Intent(context, AdRecordingService::class.java).apply {
            action = AdRecordingService.ACTION_END_AD
        }
        context.startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
