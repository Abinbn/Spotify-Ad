package com.spotad.recorder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spotad.recorder.MainActivity
import com.spotad.recorder.R
import com.spotad.recorder.audio.AudioFingerprintEngine
import com.spotad.recorder.audio.AudioRecorderManager
import com.spotad.recorder.audio.ExportManager
import com.spotad.recorder.audio.SpeechKeywordExtractor
import com.spotad.recorder.data.AppDatabase
import com.spotad.recorder.data.RecordedAdEntity
import com.spotad.recorder.data.RecordedAdRepository
import com.spotad.recorder.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MonitoringState { OFF, LISTENING, RECORDING, NEEDS_ACCESS, ERROR }

data class ServiceUiState(
    val monitoringEnabled: Boolean = false,
    val state: MonitoringState = MonitoringState.OFF,
    val captureGranted: Boolean = false,
    val recordingTitle: String = "",
    val recordingAdvertiser: String = "",
    val recordingSeconds: Int = 0,
    val liveAmplitude: Float = 0f,
    val lastError: String? = null,
    val lastSavedTitle: String? = null,
    val lastSavedAt: Long = 0L
)

/**
 * The background brain of the app.
 *
 * Responsibilities:
 *  - keep a foreground service alive only while the user has monitoring switched on;
 *  - ask the platform for playback capture exactly once per consent, and hold it for reuse;
 *  - start a capture when the notification listener reports an ad, stop it when music returns or
 *    when the safety limit is reached;
 *  - persist the clip with facts (real duration, sample rate, source) and nothing invented.
 */
class AdRecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var recorder: AudioRecorderManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var repository: RecordedAdRepository

    private var tickerJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recordingStartedAt = 0L
    private var recordingTitle = ""
    private var recordingAdvertiser = ""
    private var recordingSource = ""
    private var recordingConfidence = 0f
    @Volatile
    private var finalizing = false

    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        recorder = AudioRecorderManager(applicationContext)
        settingsStore = SettingsStore.get(applicationContext)
        repository = RecordedAdRepository(AppDatabase.getInstance(applicationContext).recordedAdDao())
        createChannels()

        recorder.onAmplitude = { amplitude ->
            if (_state.value.state == MonitoringState.RECORDING) {
                _state.value = _state.value.copy(liveAmplitude = amplitude)
            }
        }

        val settings = settingsStore.snapshot()
        _state.value = _state.value.copy(monitoringEnabled = settings.monitoringEnabled)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: if (settingsStore.snapshot().monitoringEnabled) ACTION_START else null

        when (action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_GRANT_PROJECTION -> intent?.let { handleProjectionGrant(it) }
            ACTION_RECORD_NOW -> {
                ensureForeground()
                startManualRecording()
            }
            ACTION_STOP_RECORDING -> {
                // The service may have been recreated just to deliver this action.
                ensureForeground()
                finishRecording(reason = "Stopped by user")
                if (!settingsStore.snapshot().monitoringEnabled) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_TOGGLE_MONITORING -> {
                ensureForeground()
                if (settingsStore.snapshot().monitoringEnabled) handleStop() else handleStart()
            }
            null -> {
                // Restarted by the system after being killed.
                if (settingsStore.snapshot().monitoringEnabled) {
                    handleStart()
                } else {
                    ensureForeground()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        return if (settingsStore.snapshot().monitoringEnabled) START_STICKY else START_NOT_STICKY
    }

    private fun handleStart() {
        if (!ensureForeground()) {
            _state.value = _state.value.copy(
                state = MonitoringState.ERROR,
                lastError = "Android did not allow the background service to start"
            )
            return
        }

        settingsStore.update { it.copy(monitoringEnabled = true) }

        if (!hasAudioPermission()) {
            publishState(
                state = MonitoringState.NEEDS_ACCESS,
                error = "Microphone permission is required for audio capture"
            )
            updateNotification()
            return
        }

        if (mediaProjection != null) {
            publishState(state = MonitoringState.LISTENING, error = null)
        } else {
            publishState(state = MonitoringState.NEEDS_ACCESS)
            setError("Grant audio capture access so SpotAd can save ad audio")
        }
        updateNotification()
    }

    private fun handleStop() {
        finishRecording(reason = "Monitoring stopped", notifySaved = false)
        finalizing = false
        settingsStore.update { it.copy(monitoringEnabled = false) }
        publishState(state = MonitoringState.OFF, error = null)
        releaseProjection()
        stopForegroundCompat()
        stopSelf()
    }

    private fun handleProjectionGrant(intent: Intent) {
        // The service was started with startForegroundService(), so a foreground notification is
        // mandatory even if the result turns out to be invalid.
        ensureForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || data == null) {
            setError("Audio capture permission was not granted")
            publishState(state = MonitoringState.NEEDS_ACCESS)
            updateNotification()
            if (!settingsStore.snapshot().monitoringEnabled) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }

        // Android 14+ requires the mediaProjection foreground service type to be active before
        // a MediaProjection instance is created.
        if (!ensureForeground(includeProjection = true)) {
            setError("Android blocked the audio capture service")
            publishState(state = MonitoringState.ERROR)
            updateNotification()
            return
        }

        releaseProjection()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }

        if (projection == null) {
            setError("Could not obtain audio capture access")
            publishState(state = MonitoringState.NEEDS_ACCESS)
            updateNotification()
            if (!settingsStore.snapshot().monitoringEnabled) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Media projection stopped by the system or the user")
                releaseProjection()
                finishRecording(reason = "Audio capture access ended", notifySaved = true)
                publishState(
                    state = if (settingsStore.snapshot().monitoringEnabled) {
                        MonitoringState.NEEDS_ACCESS
                    } else MonitoringState.OFF,
                    error = "Audio capture access ended. Grant it again to keep recording."
                )
                updateNotification()
            }
        }
        projection.registerCallback(callback, mainHandler)

        mediaProjection = projection
        projectionCallback = callback
        settingsStore.update { it.copy(captureConsentGiven = true, monitoringEnabled = true) }
        publishState(
            state = MonitoringState.LISTENING,
            captureGranted = true,
            error = null
        )
        updateNotification()
    }

    private fun ensureForeground(includeProjection: Boolean = false): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if ((includeProjection || mediaProjection != null) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            _state.value = _state.value.copy(
                state = MonitoringState.ERROR,
                lastError = "Android blocked the background service. Open the app and try again."
            )
            false
        }
    }

    private fun handleEvent(event: SpotifyAdEvent) {
        if (!settingsStore.snapshot().monitoringEnabled) return
        if (finalizing) return

        if (event.isAd && event.isPlaying) {
            if (_state.value.state == MonitoringState.RECORDING) {
                // Keep the first, most specific title we have.
                if (recordingTitle.isGenericAdTitle() && !event.title.isGenericAdTitle()) {
                    recordingTitle = event.title
                    recordingAdvertiser = event.artist
                }
                updateNotification()
                return
            }
            startRecording(event)
        } else if (_state.value.state == MonitoringState.RECORDING) {
            finishRecording(reason = if (event.isPlaying) "Ad finished" else "Playback paused")
        }
    }

    /** User-initiated capture. Useful when Spotify does not label an ad in its metadata. */
    private fun startManualRecording() {
        if (_state.value.state == MonitoringState.RECORDING) return
        if (!hasAudioPermission() || mediaProjection == null) {
            setError("Grant microphone and audio capture access first")
            publishState(state = MonitoringState.NEEDS_ACCESS)
            updateNotification()
            if (!settingsStore.snapshot().monitoringEnabled) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        startRecording(
            SpotifyAdEvent(
                trackId = null,
                title = "Manual recording",
                artist = "",
                album = "",
                durationMillis = 0L,
                isPlaying = true,
                isAd = true,
                confidence = 1f,
                source = "Manual capture",
                kind = ContentKind.AD
            )
        )
    }

    private fun startRecording(event: SpotifyAdEvent) {
        if (recorder.isRecording()) return
        val projection = mediaProjection
        if (projection == null) {
            setError("Grant audio capture access so SpotAd can save ad audio")
            publishState(state = MonitoringState.NEEDS_ACCESS)
            updateNotification()
            return
        }
        if (!hasAudioPermission()) {
            setError("Microphone permission is required for audio capture")
            publishState(state = MonitoringState.NEEDS_ACCESS)
            updateNotification()
            return
        }

        val settings = settingsStore.snapshot()
        val started = recorder.start(
            projection = projection,
            sampleRate = AudioRecorderManager.DEFAULT_SAMPLE_RATE,
            maxDurationSeconds = settings.maxAdSeconds
        )

        if (!started) {
            setError(recorder.lastErrorMessage() ?: "Audio capture could not start")
            publishState(
                state = if (mediaProjection == null) MonitoringState.NEEDS_ACCESS else MonitoringState.LISTENING,
                error = recorder.lastErrorMessage()
            )
            updateNotification()
            return
        }

        recordingStartedAt = System.currentTimeMillis()
        recordingTitle = event.title
        recordingAdvertiser = event.artist
        recordingSource = event.source
        recordingConfidence = event.confidence

        _state.value = _state.value.copy(
            state = MonitoringState.RECORDING,
            recordingTitle = AudioFingerprintEngine.libraryTitle(event.title, event.artist),
            recordingAdvertiser = AudioFingerprintEngine.advertiserName(event.title, event.artist),
            recordingSeconds = 0,
            liveAmplitude = 0f,
            lastError = null
        )
        startTicker()
        updateNotification()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        val maxSeconds = settingsStore.snapshot().maxAdSeconds
        tickerJob = serviceScope.launch {
            while (isActive && _state.value.state == MonitoringState.RECORDING) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
                if (_state.value.state != MonitoringState.RECORDING) break
                _state.value = _state.value.copy(recordingSeconds = elapsed)
                if (elapsed % 2 == 0) updateNotification()
                if (elapsed >= maxSeconds) {
                    // The ad signal can be lost (Spotify killed, phone call, ...). Never
                    // record forever.
                    finishRecording(reason = "Reached the maximum clip length")
                    break
                }
            }
        }
    }

    /**
     * Finalizes the capture off the main thread, discards silence and saves real metadata.
     */
    private fun finishRecording(
        reason: String,
        notifySaved: Boolean = true
    ) {
        if (_state.value.state != MonitoringState.RECORDING || finalizing) return
        finalizing = true

        tickerJob?.cancel()
        tickerJob = null
        _state.value = _state.value.copy(
            state = MonitoringState.LISTENING,
            recordingSeconds = 0,
            liveAmplitude = 0f
        )

        val title = recordingTitle
        val advertiser = recordingAdvertiser
        val source = recordingSource
        val confidence = recordingConfidence

        serviceScope.launch {
            val result = withContext(Dispatchers.IO) { recorder.stop() }
            finalizing = false
            if (result == null) {
                Log.w(TAG, "Capture produced no data: $reason")
                setError("No audio was captured. Check the capture access and try again.")
                updateNotification()
                return@launch
            }
            if (result.isSilent) {
                withContext(Dispatchers.IO) { result.file.delete() }
                setError("The capture was silent. Spotify may not allow audio capture on this device.")
                updateNotification()
                return@launch
            }
            if (result.durationMillis < MIN_CLIP_MILLIS) {
                withContext(Dispatchers.IO) { result.file.delete() }
                return@launch
            }

            val libraryTitle = AudioFingerprintEngine.libraryTitle(title, advertiser)
            val libraryAdvertiser = AudioFingerprintEngine.advertiserName(title, advertiser)
            val extracted = SpeechKeywordExtractor.extract(
                title = libraryTitle,
                advertiser = libraryAdvertiser,
                durationMillis = result.durationMillis,
                speechRecognizedText = null
            )

            val entity = RecordedAdEntity(
                title = libraryTitle,
                advertiser = libraryAdvertiser,
                filePath = result.file.absolutePath,
                durationMillis = result.durationMillis,
                timestamp = System.currentTimeMillis(),
                fileSizeBytes = result.file.length(),
                spokenKeywords = extracted.keywords.joinToString(", "),
                themeTags = extracted.themes.joinToString(", "),
                audioConfidence = confidence,
                transcriptSnippet = extracted.transcriptSnippet,
                waveformPoints = result.waveform.joinToString(","),
                sampleRate = result.sampleRate,
                source = source
            )

            try {
                repository.insert(entity)
                setError(null, clearOnly = true)
                _state.value = _state.value.copy(
                    lastSavedTitle = libraryTitle,
                    lastSavedAt = System.currentTimeMillis(),
                    state = MonitoringState.LISTENING
                )
                if (notifySaved) showSavedNotification(entity)
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Could not save the recording", e)
                withContext(Dispatchers.IO) { result.file.delete() }
                setError("Could not save the recording (storage full?)")
            }
        }
    }

    private fun setError(message: String?, clearOnly: Boolean = false) {
        _state.value = _state.value.copy(lastError = if (clearOnly) null else message)
    }

    private fun publishState(
        state: MonitoringState,
        captureGranted: Boolean = mediaProjection != null,
        error: String? = _state.value.lastError
    ) {
        _state.value = _state.value.copy(
            monitoringEnabled = settingsStore.snapshot().monitoringEnabled,
            state = state,
            captureGranted = captureGranted,
            lastError = error
        )
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun releaseProjection() {
        projectionCallback?.let { callback ->
            try {
                mediaProjection?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        _state.value = _state.value.copy(captureGranted = false)
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }

        val savedChannel = NotificationChannel(
            CHANNEL_SAVED,
            getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alerts_desc)
            setShowBadge(true)
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(savedChannel)
    }

    private fun buildNotification(): Notification {
        val state = _state.value
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val current = currentNowPlaying
        val title: String
        val text: String
        when (state.state) {
            MonitoringState.RECORDING -> {
                title = getString(R.string.notification_recording_title)
                text = listOf(state.recordingTitle, state.recordingAdvertiser)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "Capturing audio…" }
            }
            MonitoringState.LISTENING -> {
                title = getString(R.string.notification_listening_title)
                text = if (current.detected && current.isPlaying && current.title.isNotBlank()) {
                    listOf(current.title, current.artist).filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    getString(R.string.notification_listening_text)
                }
            }
            MonitoringState.NEEDS_ACCESS -> {
                title = getString(R.string.notification_paused_title)
                text = state.lastError ?: "Audio capture access is needed"
            }
            MonitoringState.ERROR -> {
                title = "SpotAd needs attention"
                text = state.lastError ?: "Open the app to fix the problem"
            }
            MonitoringState.OFF -> {
                title = getString(R.string.app_name)
                text = "Monitoring is off"
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_spotad)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(state.state != MonitoringState.OFF)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))

        if (state.state == MonitoringState.RECORDING) {
            builder.addAction(
                0,
                getString(R.string.action_stop_recording),
                servicePendingIntent(ACTION_STOP_RECORDING, 2)
            )
        } else {
            builder.addAction(
                0,
                getString(if (state.monitoringEnabled) R.string.action_pause else R.string.action_resume),
                servicePendingIntent(ACTION_TOGGLE_MONITORING, 3)
            )
        }

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AdRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (_state.value.monitoringEnabled || isForeground) {
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the notification", e)
        }
    }

    private fun showSavedNotification(ad: RecordedAdEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            this,
            SAVED_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_stat_spotad)
            .setContentTitle("Saved: ${ad.displayTitle}")
            .setContentText(
                "${ad.displayAdvertiser} · ${ExportManager.formatDuration(ad.durationMillis)}"
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(SAVED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the saved notification", e)
        }
    }

    private fun stopForegroundCompat() {
        isForeground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        if (instance === this) instance = null
        if (_state.value.state == MonitoringState.RECORDING) {
            recorder.abort()
            _state.value = _state.value.copy(state = MonitoringState.LISTENING)
        }
        releaseProjection()
        val enabled = settingsStore.snapshot().monitoringEnabled
        _state.value = _state.value.copy(
            monitoringEnabled = enabled,
            state = if (enabled) {
                // The service is gone even though monitoring is still enabled (OEM kill or a
                // crash). Say so instead of pretending we are listening.
                MonitoringState.NEEDS_ACCESS
            } else {
                MonitoringState.OFF
            },
            lastError = if (enabled) "The background service stopped. Reopen SpotAd." else _state.value.lastError
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun String?.isGenericAdTitle(): Boolean =
        this.isNullOrBlank() || AudioFingerprintEngine.isAdvertisementLabel(this)

    companion object {
        private const val TAG = "AdRecordingService"
        private const val CHANNEL_MONITOR = "spotad_monitor"
        private const val CHANNEL_SAVED = "spotad_saved"
        const val NOTIFICATION_ID = 1001
        private const val SAVED_NOTIFICATION_ID = 1002
        private const val MIN_CLIP_MILLIS = 1500L

        const val ACTION_START = "com.spotad.action.START"
        const val ACTION_STOP = "com.spotad.action.STOP"
        const val ACTION_GRANT_PROJECTION = "com.spotad.action.GRANT_PROJECTION"
        const val ACTION_STOP_RECORDING = "com.spotad.action.STOP_RECORDING"
        const val ACTION_RECORD_NOW = "com.spotad.action.RECORD_NOW"
        const val ACTION_TOGGLE_MONITORING = "com.spotad.action.TOGGLE_MONITORING"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: AdRecordingService? = null

        private val _state = MutableStateFlow(ServiceUiState())
        val state: StateFlow<ServiceUiState> = _state.asStateFlow()

        private val _nowPlaying = MutableStateFlow(NowPlaying())
        val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

        @Volatile
        private var currentNowPlaying: NowPlaying = NowPlaying()

        fun start(context: Context) {
            val intent = Intent(context, AdRecordingService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the monitoring service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdRecordingService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop the monitoring service", e)
            }
        }

        fun grantProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AdRecordingService::class.java).apply {
                action = ACTION_GRANT_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not hand the projection to the service", e)
            }
        }

        fun recordNow(context: Context) {
            val intent = Intent(context, AdRecordingService::class.java).setAction(ACTION_RECORD_NOW)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start a manual recording", e)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, AdRecordingService::class.java).setAction(ACTION_STOP_RECORDING)
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        /** Called by the notification listener for every Spotify metadata update. */
        fun dispatchEvent(event: SpotifyAdEvent) {
            currentNowPlaying = NowPlaying(
                detected = true,
                isPlaying = event.isPlaying,
                isAd = event.isAd,
                title = event.title,
                artist = event.artist,
                kind = event.kind,
                source = event.source,
                updatedAt = event.receivedAt
            )
            _nowPlaying.value = currentNowPlaying
            instance?.handleEvent(event)
        }

        fun clearError() {
            _state.value = _state.value.copy(lastError = null)
        }

        fun dispatchPlaybackStopped() {
            currentNowPlaying = currentNowPlaying.copy(isPlaying = false, isAd = false)
            _nowPlaying.value = currentNowPlaying
            instance?.let { service ->
                if (_state.value.state == MonitoringState.RECORDING) {
                    service.mainHandler.post {
                        service.finishRecording(reason = "Spotify stopped")
                    }
                }
            }
        }
    }
}
