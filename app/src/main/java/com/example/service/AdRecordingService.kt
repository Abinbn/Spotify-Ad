package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioRecorderManager
import com.example.audio.ExportManager
import com.example.audio.SpeechKeywordExtractor
import com.example.data.AppDatabase
import com.example.data.RecordedAdEntity
import com.example.data.RecordedAdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MonitoringState {
    IDLE,
    SLEEPING,        // In background standby waiting for Spotify ad
    ECO_SLEEP,       // Screen off & Spotify inactive: pauses high-frequency audio analysis to save battery
    RECORDING,       // Active audio capture of Spotify ad
    TRAVEL_INACTIVE  // Completely turned off for travel/battery saving (no background service)
}

data class ServiceUiState(
    val isRunning: Boolean = false,
    val state: MonitoringState = MonitoringState.IDLE,
    val currentAdTitle: String = "",
    val currentAdvertiser: String = "",
    val recordingDurationSec: Int = 0,
    val lastDetectedEvent: SpotifyAdEvent? = null,
    val liveAmplitude: Float = 0f,
    val isTravelModeActive: Boolean = false,
    val isEcoModeActive: Boolean = false
)

class AdRecordingService : Service() {

    private val tag = "AdRecordingService"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordingTimerJob: Job? = null
    private var backgroundPollerJob: Job? = null
    private lateinit var audioRecorderManager: AudioRecorderManager

    private var screenReceiver: BroadcastReceiver? = null
    private var spotifyBroadcastReceiver: SpotifyBroadcastReceiver? = null
    private var isSpotifyCurrentlyActive = false

    companion object {
        const val CHANNEL_ID = "spotad_monitor_channel"
        const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "spotad_prefs"
        private const val KEY_TRAVEL_MODE = "travel_mode_active"

        const val ACTION_START = "com.example.spotad.START"
        const val ACTION_STOP = "com.example.spotad.STOP"
        const val ACTION_TRIGGER_AD = "com.example.spotad.TRIGGER_AD"
        const val ACTION_END_AD = "com.example.spotad.END_AD"
        const val ACTION_SET_TRAVEL_MODE = "com.example.spotad.SET_TRAVEL_MODE"

        private var activeService: AdRecordingService? = null
        private var sharedMediaProjection: MediaProjection? = null

        private val _serviceUiState = MutableStateFlow(ServiceUiState())
        val serviceUiState: StateFlow<ServiceUiState> = _serviceUiState.asStateFlow()

        private val _spotifyNowPlaying = MutableStateFlow(SpotifyNowPlaying())
        val spotifyNowPlaying: StateFlow<SpotifyNowPlaying> = _spotifyNowPlaying.asStateFlow()

        fun isTravelMode(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_TRAVEL_MODE, false)
        }

        fun setTravelModePreference(context: Context, active: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_TRAVEL_MODE, active).apply()
            _serviceUiState.value = _serviceUiState.value.copy(
                isTravelModeActive = active,
                state = if (active) MonitoringState.TRAVEL_INACTIVE else MonitoringState.SLEEPING
            )
        }

        fun setServiceState(newState: ServiceUiState) {
            _serviceUiState.value = newState
        }

        fun setMediaProjection(projection: MediaProjection?) {
            sharedMediaProjection = projection
        }

        fun dispatchSpotifyStopped(context: Context) {
            _spotifyNowPlaying.value = _spotifyNowPlaying.value.copy(
                isPlaying = false,
                isSpotifyDetected = false
            )
            val service = activeService
            if (service != null && _serviceUiState.value.state == MonitoringState.RECORDING) {
                service.stopAdRecording(cancel = false)
            }
        }

        fun dispatchSpotifyEvent(context: Context, event: SpotifyAdEvent) {
            // Check travel inactive mode
            if (isTravelMode(context)) {
                Log.d("AdRecordingService", "Travel Mode active: Ignoring Spotify event")
                return
            }

            val current = _spotifyNowPlaying.value
            val isMeaningfulTitle = event.trackTitle.isNotBlank() && event.trackTitle != "Spotify Audio Track"
            val titleToUse = if (isMeaningfulTitle) event.trackTitle else current.title.ifBlank { event.trackTitle }
            val artistToUse = if (event.artist.isNotBlank() && event.artist != "Spotify") event.artist else current.artist.ifBlank { event.artist }
            val albumToUse = if (event.album.isNotBlank()) event.album else current.album
            val totalLengthToUse = if (event.lengthMillis > 0L) event.lengthMillis else current.totalLengthMillis

            _spotifyNowPlaying.value = SpotifyNowPlaying(
                isSpotifyDetected = true,
                isPlaying = event.isPlaying,
                title = titleToUse,
                artist = artistToUse,
                album = albumToUse,
                totalLengthMillis = totalLengthToUse,
                currentPositionMillis = event.currentPositionMillis,
                lastDetectedTimestamp = event.timestamp,
                isAdBreak = event.isAdBreak,
                contentType = event.contentType,
                source = event.source
            )

            _serviceUiState.value = _serviceUiState.value.copy(lastDetectedEvent = event)

            val service = activeService
            if (service == null) {
                // If service not started, auto-start if an ad was detected
                if (event.isAdBreak && event.isPlaying) {
                    val intent = Intent(context, AdRecordingService::class.java).apply {
                        action = ACTION_START
                        putExtra("auto_title", event.trackTitle)
                        putExtra("auto_advertiser", event.artist)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            context.startForegroundService(intent)
                        } catch (e: Exception) {
                            Log.e("AdRecordingService", "Failed to startForegroundService", e)
                        }
                    } else {
                        context.startService(intent)
                    }
                }
                return
            }

            service.handleSpotifyEvent(event)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        audioRecorderManager = AudioRecorderManager(applicationContext)
        createNotificationChannel()

        val travelActive = isTravelMode(applicationContext)
        if (travelActive) {
            startServiceInForeground("Travel Mode Active")
            _serviceUiState.value = _serviceUiState.value.copy(
                isRunning = false,
                isTravelModeActive = true,
                state = MonitoringState.TRAVEL_INACTIVE
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // ALWAYS immediately call startForeground in onCreate to prevent ForegroundServiceDidNotStartInTimeException
        startServiceInForeground(
            statusText = "Detecting Spotify audio & songs in background",
            title = "Detecting Spotify Audio",
            subText = "Detecting Audio"
        )

        // Register dynamic Spotify BroadcastReceiver for real-time background capture
        registerSpotifyBroadcastReceiver()

        // Register screen state receiver for battery-efficient Eco Mode
        registerScreenStateReceiver()

        // Start background polling to check Spotify notifications and media sessions
        startBackgroundPoller()

        // Automated cleanup on service start: purge recordings older than 30 days unless favorite
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).recordedAdDao()
                val repository = RecordedAdRepository(dao)
                val (deleted, freedBytes) = repository.cleanupOldRecordings(days = 30)
                if (deleted > 0) {
                    Log.i(tag, "Automated cleanup: Deleted $deleted ads older than 30 days (${freedBytes / 1024} KB freed)")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during automated 30-day cleanup", e)
            }
        }
    }

    private fun registerSpotifyBroadcastReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(SpotifyBroadcastReceiver.ACTION_METADATA_CHANGED)
                addAction(SpotifyBroadcastReceiver.ACTION_PLAYBACK_STATE_CHANGED)
                addAction(SpotifyBroadcastReceiver.ACTION_QUEUE_CHANGED)
            }
            spotifyBroadcastReceiver = SpotifyBroadcastReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(spotifyBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(spotifyBroadcastReceiver, filter)
            }
            Log.i(tag, "Dynamically registered SpotifyBroadcastReceiver for background monitoring")
        } catch (e: Exception) {
            Log.w(tag, "Failed to dynamically register Spotify broadcast receiver", e)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (_serviceUiState.value.state != MonitoringState.RECORDING &&
                            _serviceUiState.value.state != MonitoringState.TRAVEL_INACTIVE) {
                            Log.i(tag, "Screen OFF & Spotify idle: Entering Eco-Sleep Mode")
                            _serviceUiState.value = _serviceUiState.value.copy(
                                state = MonitoringState.ECO_SLEEP,
                                isEcoModeActive = true
                            )
                            updateNotification("Eco-Sleep: Audio analysis paused to save battery")
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (_serviceUiState.value.state == MonitoringState.ECO_SLEEP) {
                            Log.i(tag, "Screen ON: Resuming standard monitoring standby")
                            _serviceUiState.value = _serviceUiState.value.copy(
                                state = MonitoringState.SLEEPING,
                                isEcoModeActive = false
                            )
                            val nowPlaying = _spotifyNowPlaying.value
                            val status = if (nowPlaying.isSpotifyDetected && nowPlaying.title.isNotBlank()) {
                                "Spotify: ${nowPlaying.title} • ${nowPlaying.artist}"
                            } else {
                                "Monitoring Spotify in background"
                            }
                            updateNotification(status)
                        }
                    }
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startBackgroundPoller() {
        backgroundPollerJob?.cancel()
        backgroundPollerJob = scope.launch {
            while (isActive) {
                delay(3000)
                if (_serviceUiState.value.state == MonitoringState.TRAVEL_INACTIVE) break

                // Check active Spotify notifications & MediaSessions
                val listener = SpotifyNotificationListener.instance
                if (listener != null) {
                    listener.checkActiveSpotifyNotifications()
                } else if (SpotifyNotificationListener.isNotificationAccessGranted(applicationContext)) {
                    SpotifyNotificationListener.queryActiveSpotifyMediaSession(applicationContext)
                    SpotifyNotificationListener.rebindIfPossible(applicationContext)
                }

                // Update notification text if not actively recording
                if (_serviceUiState.value.state == MonitoringState.SLEEPING) {
                    val np = _spotifyNowPlaying.value
                    if (np.isSpotifyDetected && np.isPlaying && np.title.isNotBlank()) {
                        if (np.isAdBreak) {
                            updateNotification(
                                statusText = "🔴 Ad: ${np.title} • ${np.artist}",
                                title = "Recording Spotify Ad",
                                subText = "Recording Ad"
                            )
                        } else {
                            updateNotification(
                                statusText = "🎵 ${np.title} • ${np.artist}",
                                title = "Detecting Spotify Song",
                                subText = "Detecting Audio & Ads"
                            )
                        }
                    } else {
                        updateNotification(
                            statusText = "Detecting Spotify audio & songs in background",
                            title = "Detecting Spotify Audio",
                            subText = "Detecting Audio"
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_SET_TRAVEL_MODE -> {
                val travel = intent?.getBooleanExtra("travel_mode", false) ?: false
                setTravelModePreference(applicationContext, travel)
                if (travel) {
                    stopAdRecording(cancel = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    _serviceUiState.value = _serviceUiState.value.copy(
                        isRunning = false,
                        isTravelModeActive = true,
                        state = MonitoringState.TRAVEL_INACTIVE
                    )
                    stopSelf()
                    return START_NOT_STICKY
                } else {
                    startServiceInForeground(
                        statusText = "Detecting Spotify audio & songs in background",
                        title = "Detecting Spotify Audio",
                        subText = "Detecting Audio"
                    )
                }
            }

            ACTION_START -> {
                // User started monitor: ensure travel mode is cleared
                setTravelModePreference(applicationContext, false)
                startServiceInForeground(
                    statusText = "Detecting Spotify audio & songs in background",
                    title = "Detecting Spotify Audio",
                    subText = "Detecting Audio"
                )
                val autoTitle = intent?.getStringExtra("auto_title")
                val autoAdvertiser = intent?.getStringExtra("auto_advertiser")
                if (!autoTitle.isNullOrBlank()) {
                    startAdRecording(autoTitle, autoAdvertiser ?: "Spotify Sponsor")
                }
            }

            ACTION_STOP -> {
                stopAdRecording(cancel = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                _serviceUiState.value = _serviceUiState.value.copy(
                    isRunning = false,
                    state = MonitoringState.IDLE
                )
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRIGGER_AD -> {
                val title = intent?.getStringExtra("title") ?: "Spotify Commercial"
                val advertiser = intent?.getStringExtra("advertiser") ?: "Spotify Advertiser"
                startAdRecording(title, advertiser)
            }

            ACTION_END_AD -> {
                stopAdRecording(cancel = false)
            }
        }

        return START_STICKY
    }

    private fun startServiceInForeground(
        statusText: String = "Detecting Spotify audio & songs in background",
        title: String? = "Detecting Spotify Audio",
        subText: String? = "Detecting Audio"
    ) {
        try {
            createNotificationChannel()
            val notification = buildNotification(statusText, title, subText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to startForeground with dataSync, trying fallback", e)
            try {
                val notification = buildNotification(statusText, title, subText)
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(tag, "Fallback startForeground failed", e2)
            }
        }
        _serviceUiState.value = _serviceUiState.value.copy(
            isRunning = true,
            isTravelModeActive = false,
            state = if (_serviceUiState.value.state == MonitoringState.RECORDING) MonitoringState.RECORDING else MonitoringState.SLEEPING
        )
    }

    fun handleSpotifyEvent(event: SpotifyAdEvent) {
        if (_serviceUiState.value.isTravelModeActive) return

        isSpotifyCurrentlyActive = event.isPlaying
        val currentState = _serviceUiState.value.state

        if (event.isAdBreak && event.isPlaying) {
            // An ad is actively playing
            if (currentState != MonitoringState.RECORDING) {
                Log.i(tag, "Ad break detected from ${event.source}: ${event.trackTitle}")
                startAdRecording(event.trackTitle, event.artist)
            }
        } else {
            // Regular music playing or paused
            if (currentState == MonitoringState.RECORDING) {
                Log.i(tag, "Ad break finished. Resuming sleep state.")
                stopAdRecording(cancel = false)
            } else {
                val nextState = if (_serviceUiState.value.isEcoModeActive) MonitoringState.ECO_SLEEP else MonitoringState.SLEEPING
                _serviceUiState.value = _serviceUiState.value.copy(state = nextState)
                if (event.isPlaying && event.trackTitle.isNotBlank()) {
                    updateNotification(
                        statusText = "🎵 ${event.trackTitle} • ${event.artist}",
                        title = "Detecting Spotify Song",
                        subText = "Detecting Audio & Ads"
                    )
                } else {
                    updateNotification(
                        statusText = if (nextState == MonitoringState.ECO_SLEEP) "Eco-Sleep: Audio analysis paused" else "Detecting Spotify audio & songs in background",
                        title = if (nextState == MonitoringState.ECO_SLEEP) "SpotAd Eco-Sleep" else "Detecting Spotify Audio",
                        subText = if (nextState == MonitoringState.ECO_SLEEP) "Eco-Sleep" else "Detecting Audio"
                    )
                }
            }
        }
    }

    fun startAdRecording(title: String, advertiser: String) {
        if (_serviceUiState.value.state == MonitoringState.RECORDING) return
        if (_serviceUiState.value.isTravelModeActive) return

        val cleanTitle = if (title.isBlank()) "Spotify Advertisement" else title
        val cleanAdvertiser = if (advertiser.isBlank()) "Spotify" else advertiser

        _serviceUiState.value = _serviceUiState.value.copy(
            state = MonitoringState.RECORDING,
            currentAdTitle = cleanTitle,
            currentAdvertiser = cleanAdvertiser,
            recordingDurationSec = 0
        )

        updateNotification(
            statusText = "\"$cleanTitle\" • $cleanAdvertiser",
            title = "🔴 Recording Spotify Ad (0s)",
            subText = "Recording Audio"
        )

        // Start audio capture
        audioRecorderManager.startCapture(
            mediaProjection = sharedMediaProjection,
            adTitle = cleanTitle,
            onAmplitudeSample = { amp ->
                _serviceUiState.value = _serviceUiState.value.copy(liveAmplitude = amp)
            }
        )

        // Seconds counter
        recordingTimerJob?.cancel()
        recordingTimerJob = scope.launch {
            var seconds = 0
            while (isActive && _serviceUiState.value.state == MonitoringState.RECORDING) {
                delay(1000)
                seconds++
                _serviceUiState.value = _serviceUiState.value.copy(recordingDurationSec = seconds)
                updateNotification(
                    statusText = "\"$cleanTitle\" • $cleanAdvertiser (${seconds}s)",
                    title = "🔴 Recording Spotify Ad (${seconds}s)",
                    subText = "Recording Audio"
                )
            }
        }
    }

    fun stopAdRecording(cancel: Boolean) {
        if (_serviceUiState.value.state != MonitoringState.RECORDING) return

        val adTitle = _serviceUiState.value.currentAdTitle
        val adAdvertiser = _serviceUiState.value.currentAdvertiser
        val durationSec = _serviceUiState.value.recordingDurationSec

        recordingTimerJob?.cancel()
        recordingTimerJob = null

        val returnState = if (_serviceUiState.value.isEcoModeActive) MonitoringState.ECO_SLEEP else MonitoringState.SLEEPING
        _serviceUiState.value = _serviceUiState.value.copy(
            state = returnState,
            recordingDurationSec = 0,
            liveAmplitude = 0f
        )
        val np = _spotifyNowPlaying.value
        if (np.isSpotifyDetected && np.isPlaying && np.title.isNotBlank()) {
            updateNotification(
                statusText = "🎵 ${np.title} • ${np.artist}",
                title = "Detecting Spotify Song",
                subText = "Detecting Audio & Ads"
            )
        } else if (returnState == MonitoringState.ECO_SLEEP) {
            updateNotification(
                statusText = "Eco-Sleep: Audio analysis paused",
                title = "SpotAd Eco-Sleep",
                subText = "Eco-Sleep"
            )
        } else {
            updateNotification(
                statusText = "Detecting Spotify audio & songs in background",
                title = "Detecting Spotify Audio",
                subText = "Detecting Audio"
            )
        }

        if (cancel || durationSec < 2) {
            scope.launch { audioRecorderManager.stopCapture() }
            return
        }

        scope.launch {
            val (file, waveformPoints) = audioRecorderManager.stopCapture()
            if (file != null && file.exists()) {
                val durationMs = durationSec * 1000L
                val extracted = SpeechKeywordExtractor.extract(
                    title = adTitle,
                    advertiser = adAdvertiser,
                    durationMillis = durationMs
                )

                val entity = RecordedAdEntity(
                    title = adTitle,
                    advertiser = adAdvertiser,
                    filePath = file.absolutePath,
                    durationMillis = durationMs,
                    timestamp = System.currentTimeMillis(),
                    fileSizeBytes = file.length(),
                    spokenKeywords = extracted.keywords.joinToString(", "),
                    themeTags = extracted.themes.joinToString(", "),
                    isFavorite = false,
                    audioConfidence = 0.96f,
                    transcriptSnippet = extracted.transcriptSnippet,
                    waveformPoints = waveformPoints.joinToString(",")
                )

                val dao = AppDatabase.getInstance(applicationContext).recordedAdDao()
                val id = dao.insert(entity)

                // Save companion JSON metadata
                val savedEntity = entity.copy(id = id)
                ExportManager.saveCompanionMetadataJson(file, savedEntity)

                Log.i(tag, "Successfully recorded & saved ad #$id: $adTitle")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        statusText: String,
        title: String? = null,
        subText: String? = null
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultTitle = when (_serviceUiState.value.state) {
            MonitoringState.RECORDING -> "🔴 Recording Spotify Ad"
            MonitoringState.ECO_SLEEP -> "SpotAd Standby (Eco Mode)"
            else -> {
                val np = _spotifyNowPlaying.value
                if (np.isSpotifyDetected && np.isPlaying && np.title.isNotBlank()) {
                    "Detecting Spotify Song"
                } else {
                    "Detecting Spotify Audio"
                }
            }
        }

        val actualTitle = title ?: defaultTitle
        val actualSubText = subText ?: when (_serviceUiState.value.state) {
            MonitoringState.RECORDING -> "Recording Audio"
            MonitoringState.ECO_SLEEP -> "Eco-Sleep"
            else -> "Detecting Audio"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(actualTitle)
            .setContentText(statusText)
            .setSubText(actualSubText)
            .setSmallIcon(R.drawable.ic_spotad_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .build()
    }

    private fun updateNotification(
        statusText: String,
        title: String? = null,
        subText: String? = null
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText, title, subText))
    }

    override fun onDestroy() {
        activeService = null
        backgroundPollerJob?.cancel()
        recordingTimerJob?.cancel()
        stopAdRecording(cancel = true)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        spotifyBroadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        val travel = isTravelMode(applicationContext)
        _serviceUiState.value = _serviceUiState.value.copy(
            isRunning = false,
            isTravelModeActive = travel,
            state = if (travel) MonitoringState.TRAVEL_INACTIVE else MonitoringState.IDLE
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
