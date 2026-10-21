package com.spotad.recorder.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spotad.recorder.audio.AudioPlayerManager
import com.spotad.recorder.audio.AudioRecorderManager
import com.spotad.recorder.audio.ExportManager
import com.spotad.recorder.audio.PlaybackState
import com.spotad.recorder.audio.WavTrimmer
import com.spotad.recorder.data.AppDatabase
import com.spotad.recorder.data.AppSettings
import com.spotad.recorder.data.RecordedAdEntity
import com.spotad.recorder.data.RecordedAdRepository
import com.spotad.recorder.data.SettingsStore
import com.spotad.recorder.data.ThemeMode
import com.spotad.recorder.service.AdRecordingService
import com.spotad.recorder.service.NowPlaying
import com.spotad.recorder.service.ServiceUiState
import com.spotad.recorder.service.SpotifyNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SortOrder(val label: String, val shortLabel: String) {
    NEWEST("Newest first", "Newest"),
    OLDEST("Oldest first", "Oldest"),
    LONGEST("Longest first", "Longest"),
    SHORTEST("Shortest first", "Shortest")
}

enum class LibraryFilter { ALL, FAVORITES }

data class UiMessage(val text: String, val isError: Boolean = false)

data class LibraryUiState(
    val ads: List<RecordedAdEntity> = emptyList(),
    val totalCount: Int = 0,
    val favoriteCount: Int = 0,
    val storageBytes: Long = 0L,
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordedAdRepository =
        RecordedAdRepository(AppDatabase.getInstance(application).recordedAdDao())
    private val settingsStore = SettingsStore.get(application)
    private val playerManager = AudioPlayerManager(application)

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val serviceState: StateFlow<ServiceUiState> = AdRecordingService.state
    val nowPlaying: StateFlow<NowPlaying> = AdRecordingService.nowPlaying
    val playerState: StateFlow<PlaybackState> = playerManager.playbackState

    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    private val _sort = MutableStateFlow(SortOrder.NEWEST)
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _inspectingAd = MutableStateFlow<RecordedAdEntity?>(null)
    val inspectingAd: StateFlow<RecordedAdEntity?> = _inspectingAd.asStateFlow()

    private val _oldRecordings = MutableStateFlow(0)
    val oldRecordings: StateFlow<Int> = _oldRecordings.asStateFlow()

    val library: StateFlow<LibraryUiState> = combine(
        repositoryAds(),
        _searchQuery,
        _filter,
        _sort
    ) { ads, query, filter, sort ->
        val trimmed = query.trim()
        var visible = ads
        if (filter == LibraryFilter.FAVORITES) {
            visible = visible.filter { it.isFavorite }
        }
        if (trimmed.isNotEmpty()) {
            visible = visible.filter { ad ->
                ad.title.contains(trimmed, ignoreCase = true) ||
                    ad.advertiser.contains(trimmed, ignoreCase = true) ||
                    ad.notes.contains(trimmed, ignoreCase = true) ||
                    ad.spokenKeywords.contains(trimmed, ignoreCase = true) ||
                    ad.themeTags.contains(trimmed, ignoreCase = true)
            }
        }
        visible = when (sort) {
            SortOrder.NEWEST -> visible.sortedByDescending { it.timestamp }
            SortOrder.OLDEST -> visible.sortedBy { it.timestamp }
            SortOrder.LONGEST -> visible.sortedByDescending { it.durationMillis }
            SortOrder.SHORTEST -> visible.sortedBy { it.durationMillis }
        }
        LibraryUiState(
            ads = visible,
            totalCount = ads.size,
            favoriteCount = ads.count { it.isFavorite },
            storageBytes = ads.sumOf { it.fileSizeBytes },
            searchQuery = query,
            filter = filter,
            sort = sort
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val days = settingsStore.snapshot().retentionDays
                    if (days > 0) repository.deleteOlderThan(days)
                    repository.pruneOrphanFiles(AudioRecorderManager.adsDirectory(application))
                }
                refreshOldCount()
            }.onFailure { Log.w(TAG, "Startup maintenance failed", it) }
        }

        // Surface newly saved clips and player errors as in-app messages.
        viewModelScope.launch {
            var lastSavedAt = 0L
            serviceState.collect { state ->
                if (state.lastSavedAt > lastSavedAt && state.lastSavedTitle != null) {
                    lastSavedAt = state.lastSavedAt
                    _message.value = UiMessage("Saved \"${state.lastSavedTitle}\"")
                    refreshOldCount()
                }
            }
        }
        viewModelScope.launch {
            var lastError: String? = null
            playerState.collect { state ->
                val error = state.error
                if (error != null && error != lastError) {
                    lastError = error
                    _message.value = UiMessage(error, isError = true)
                    playerManager.clearError()
                }
            }
        }
    }

    private fun repositoryAds() = repository.ads

    // ------------------------------------------------------------------ library controls

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: LibraryFilter) {
        _filter.value = filter
    }

    fun setSortOrder(order: SortOrder) {
        _sort.value = order
    }

    fun inspectAd(ad: RecordedAdEntity?) {
        _inspectingAd.value = ad
    }

    fun toggleFavorite(ad: RecordedAdEntity) {
        viewModelScope.launch {
            repository.setFavorite(ad.id, !ad.isFavorite)
            if (_inspectingAd.value?.id == ad.id) {
                _inspectingAd.value = ad.copy(isFavorite = !ad.isFavorite)
            }
        }
    }

    fun deleteAd(ad: RecordedAdEntity) {
        viewModelScope.launch {
            if (playerState.value.adId == ad.id) playerManager.stop()
            repository.delete(ad)
            if (_inspectingAd.value?.id == ad.id) _inspectingAd.value = null
            _message.value = UiMessage("Deleted \"${ad.displayTitle}\"")
            refreshOldCount()
        }
    }

    fun updateMetadata(id: Long, title: String, advertiser: String, themes: String, notes: String) {
        viewModelScope.launch {
            repository.updateMetadata(id, title, advertiser, themes, notes)
            _inspectingAd.value = null
            _message.value = UiMessage("Details updated")
        }
    }

    /**
     * Lossless WAV trim. The row is only touched after the trimmed file is safely on disk, so a
     * failed trim leaves the original audio and metadata exactly as they were.
     */
    fun trimAd(ad: RecordedAdEntity, startMillis: Long, endMillis: Long) {
        viewModelScope.launch {
            if (playerState.value.adId == ad.id) playerManager.stop()
            val originalDuration = ad.durationMillis.coerceAtLeast(1L)
            val oldBars = ad.waveform

            val result = withContext(Dispatchers.IO) {
                val file = File(ad.filePath)
                val info = WavTrimmer.trimInPlace(file, startMillis, endMillis)
                info?.let { it to file.length() }
            }

            if (result == null) {
                _message.value = UiMessage(
                    "Could not trim this recording. The original is unchanged.",
                    isError = true
                )
                return@launch
            }

            val (info, size) = result
            val waveform: List<Float> = if (oldBars.isEmpty()) {
                emptyList()
            } else {
                val from = (startMillis.toFloat() / originalDuration * oldBars.size)
                    .toInt().coerceIn(0, oldBars.lastIndex)
                val to = (endMillis.toFloat() / originalDuration * oldBars.size)
                    .toInt().coerceIn(from + 1, oldBars.size)
                oldBars.subList(from, to).toList()
            }

            val updated = ad.copy(
                durationMillis = info.durationMillis,
                fileSizeBytes = size,
                waveformPoints = waveform.joinToString(",")
            )
            repository.update(updated)
            // Close the sheet: the confirmation snackbar lives on the main screen and would be
            // hidden behind the modal sheet, and the list row already shows the new length.
            if (_inspectingAd.value?.id == ad.id) _inspectingAd.value = null
            _message.value = UiMessage("Trimmed to ${ExportManager.formatDuration(info.durationMillis)}")
        }
    }

    fun exportAd(context: Context, ad: RecordedAdEntity) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ExportManager.exportToMusic(context, ad) }
            _message.value = result.fold(
                onSuccess = { UiMessage("Exported to Music/SpotAd") },
                onFailure = { UiMessage(it.message ?: "Export failed", isError = true) }
            )
        }
    }

    fun shareAd(context: Context, ad: RecordedAdEntity) {
        ExportManager.share(context, ad).onFailure {
            _message.value = UiMessage(it.message ?: "Sharing failed", isError = true)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun postMessage(text: String, isError: Boolean = false) {
        _message.value = UiMessage(text, isError)
    }

    // ------------------------------------------------------------------ monitoring

    fun toggleMonitoring(context: Context) {
        if (serviceState.value.monitoringEnabled) {
            AdRecordingService.stop(context)
            _message.value = UiMessage("Monitoring paused")
        } else {
            AdRecordingService.start(context)
        }
    }

    fun stopCurrentRecording(context: Context) {
        AdRecordingService.stopRecording(context)
    }

    fun startManualRecording(context: Context) {
        AdRecordingService.recordNow(context)
    }

    fun onProjectionResult(context: Context, resultCode: Int, data: Intent?) {
        if (data == null) {
            _message.value = UiMessage("Audio capture was not granted", isError = true)
            return
        }
        AdRecordingService.grantProjection(context, resultCode, data)
        if (!settingsStore.snapshot().monitoringEnabled) {
            AdRecordingService.start(context)
        }
    }

    fun onProjectionDenied() {
        _message.value = UiMessage(
            "Audio capture is needed to save ads. You can grant it any time.",
            isError = true
        )
    }

    fun dismissServiceError() {
        AdRecordingService.clearError()
    }

    fun onAppResumed(context: Context) {
        SpotifyNotificationListener.refreshNowPlaying(context)
        viewModelScope.launch { refreshOldCount() }
    }

    // ------------------------------------------------------------------ settings

    fun setMaxAdSeconds(seconds: Int) {
        settingsStore.update { it.copy(maxAdSeconds = seconds.coerceIn(15, 600)) }
    }

    fun setRetentionDays(days: Int) {
        settingsStore.update { it.copy(retentionDays = days.coerceIn(0, 3650)) }
        viewModelScope.launch {
            if (days > 0) {
                val removed = withContext(Dispatchers.IO) { repository.deleteOlderThan(days) }
                if (removed > 0) _message.value = UiMessage("Removed $removed old recordings")
            }
            refreshOldCount()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.update { it.copy(themeMode = mode) }
    }

    fun completeOnboarding() {
        settingsStore.update { it.copy(onboardingComplete = true) }
    }

    fun replayOnboarding() {
        settingsStore.update { it.copy(onboardingComplete = false) }
    }

    fun cleanupOldRecordings() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                repository.deleteOlderThan(settingsStore.snapshot().retentionDays)
            }
            refreshOldCount()
            _message.value = UiMessage(if (removed > 0) "Removed $removed recordings" else "Nothing to clean up")
        }
    }

    fun deleteAllRecordings() {
        viewModelScope.launch {
            playerManager.stop()
            val removed = withContext(Dispatchers.IO) { repository.deleteEverything() }
            refreshOldCount()
            _message.value = UiMessage("Deleted $removed recordings")
        }
    }

    fun deleteUnstarredRecordings() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { repository.deleteNonFavorites() }
            refreshOldCount()
            _message.value = UiMessage("Deleted $removed recordings")
        }
    }

    // ------------------------------------------------------------------ playback

    fun playAd(ad: RecordedAdEntity) {
        if (!File(ad.filePath).exists()) {
            _message.value = UiMessage("The audio file for this recording is missing", isError = true)
            return
        }
        playerManager.play(ad.id, ad.filePath)
    }

    fun pausePlayback() = playerManager.pause()

    fun resumePlayback() = playerManager.resume()

    fun seekPlayback(positionMs: Long) = playerManager.seekTo(positionMs)

    fun setPlaybackSpeed(speed: Float) = playerManager.setSpeed(speed)

    fun closePlayer() = playerManager.stop()

    private suspend fun refreshOldCount() {
        val days = settingsStore.snapshot().retentionDays
        _oldRecordings.value = withContext(Dispatchers.IO) {
            if (days > 0) repository.countOlderThan(days) else 0
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
