package com.spotad.recorder.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.spotad.recorder.audio.AudioFingerprintEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads Spotify's media session (and, as a fallback, its notification) to know what is playing.
 *
 * The media session is the authoritative source: it exposes a real [PlaybackState], so we no
 * longer guess "playing" from the label of a notification button. Events are de-duplicated so a
 * burst of notification updates cannot start or stop a capture repeatedly.
 */
class SpotifyNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controllerCallback: MediaController.Callback? = null
    private var boundController: MediaController? = null
    private var stopCheckJob: Job? = null

    private var lastSignature: String = ""
    private var lastDispatchAt: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
        setupMediaSessionMonitoring()
        inspectActiveNotifications()
    }

    override fun onListenerDisconnected() {
        cleanup()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null
        activeSessionsListener?.let { listener ->
            try {
                mediaSessionManager()?.removeOnActiveSessionsChangedListener(listener)
            } catch (_: Exception) {
            }
        }
        activeSessionsListener = null
        controllerCallback?.let { callback ->
            try {
                boundController?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        controllerCallback = null
        boundController = null
    }

    private fun mediaSessionManager(): MediaSessionManager? =
        getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private fun setupMediaSessionMonitoring() {
        try {
            val manager = mediaSessionManager() ?: return
            val component = ComponentName(this, SpotifyNotificationListener::class.java)

            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                bindControllers(controllers)
            }
            activeSessionsListener = listener
            manager.addOnActiveSessionsChangedListener(listener, component)
            bindControllers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access is missing or was revoked", e)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up media session monitoring", e)
        }
    }

    private fun bindControllers(controllers: List<MediaController>?) {
        controllers ?: return
        val spotifyController = controllers.firstOrNull { isSpotifyPackage(it.packageName) }
        if (spotifyController == null) return

        if (boundController?.sessionToken != spotifyController.sessionToken) {
            controllerCallback?.let { callback ->
                try {
                    boundController?.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishFromController(spotifyController, "Spotify media session")
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_STOPPED) {
                        AdRecordingService.dispatchPlaybackStopped()
                    } else {
                        publishFromController(spotifyController, "Spotify media session")
                    }
                }

                override fun onSessionDestroyed() {
                    AdRecordingService.dispatchPlaybackStopped()
                }
            }
            try {
                spotifyController.registerCallback(callback)
                controllerCallback = callback
                boundController = spotifyController
            } catch (e: Exception) {
                Log.w(TAG, "Could not observe the Spotify session", e)
            }
        }

        publishFromController(spotifyController, "Spotify media session")
    }

    private fun publishFromController(controller: MediaController, source: String) {
        val metadata = try {
            controller.metadata
        } catch (_: Exception) {
            null
        }
        val playbackState = try {
            controller.playbackState
        } catch (_: Exception) {
            null
        }

        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim()
            ?: ""
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.trim()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.trim()
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)?.trim()
            ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playbackState?.position ?: 0L
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING ||
            playbackState?.state == PlaybackState.STATE_BUFFERING

        if (title.isBlank() && artist.isBlank()) return
        if (!isPlaying && title.isBlank()) return

        if (title.isBlank()) {
            // Some Spotify builds expose only the advertiser for ads.
            title = artist
            artist = ""
        }

        publish(title, artist, album, duration, position, isPlaying, source)
    }

    private fun inspectActiveNotifications() {
        try {
            val notifications = activeNotifications ?: return
            notifications
                .filter { isSpotifyPackage(it.packageName) }
                .firstOrNull { it.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
                ?.let { processNotification(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect active notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!isSpotifyPackage(notification.packageName)) return
        processNotification(notification)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Prefer the media session token: it gives us the real playback state.
        val token = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
        } catch (_: Exception) {
            null
        }

        if (token != null) {
            try {
                publishFromController(MediaController(applicationContext, token), "Spotify notification")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the notification's media session", e)
            }
        }

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            title = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        }
        if (title.isBlank() && artist.isBlank()) return

        val isPlaying = inferPlayingFromActions(notification) ?: true
        publish(title, artist, album, 0L, 0L, isPlaying, "Spotify notification")
    }

    private fun inferPlayingFromActions(notification: Notification): Boolean? {
        val labels = notification.actions
            ?.mapNotNull { it.title?.toString()?.lowercase() }
            .orEmpty()
        return when {
            labels.any { it.contains("pause") } -> true
            labels.any { it.contains("play") || it.contains("resume") } -> false
            else -> null
        }
    }

    private fun publish(
        title: String,
        artist: String,
        album: String,
        durationMillis: Long,
        positionMillis: Long,
        isPlaying: Boolean,
        source: String
    ) {
        val signature = "$title|$artist|$album|$isPlaying|$durationMillis"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastDispatchAt < DEDUPE_WINDOW_MS) return
        lastSignature = signature
        lastDispatchAt = now

        val fingerprint = AudioFingerprintEngine.evaluateAdBreak(
            trackId = null,
            trackTitle = title,
            artist = artist,
            lengthMillis = durationMillis
        )
        val kind = when (
            AudioFingerprintEngine.classify(fingerprint.isAdBreak, title, album, durationMillis)
        ) {
            AudioFingerprintEngine.ContentType.ADVERTISEMENT -> ContentKind.AD
            AudioFingerprintEngine.ContentType.PODCAST -> ContentKind.PODCAST
            AudioFingerprintEngine.ContentType.MUSIC -> ContentKind.MUSIC
            else -> ContentKind.UNKNOWN
        }

        AdRecordingService.dispatchEvent(
            SpotifyAdEvent(
                trackId = null,
                title = title,
                artist = artist,
                album = album,
                durationMillis = durationMillis,
                positionMillis = positionMillis,
                isPlaying = isPlaying,
                isAd = fingerprint.isAdBreak,
                confidence = fingerprint.confidence,
                source = source,
                kind = kind
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (!isSpotifyPackage(packageName)) return

        // The notification disappears when playback stops or Spotify is swiped away. Confirm
        // against the media session before telling the recorder to stop.
        stopCheckJob?.cancel()
        stopCheckJob = scope.launch {
            delay(700)
            val stillPlaying = try {
                val manager = mediaSessionManager()
                val component = ComponentName(this@SpotifyNotificationListener, SpotifyNotificationListener::class.java)
                manager?.getActiveSessions(component)
                    ?.any { isSpotifyPackage(it.packageName) && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: false
            } catch (_: Exception) {
                false
            }
            if (!stillPlaying) AdRecordingService.dispatchPlaybackStopped()
        }
    }

    companion object {
        private const val TAG = "SpotifyNotifListener"
        private const val DEDUPE_WINDOW_MS = 750L

        private val SPOTIFY_PACKAGES = setOf("com.spotify.music", "com.spotify.lite")

        @Volatile
        var instance: SpotifyNotificationListener? = null
            private set

        fun isSpotifyPackage(packageName: String?): Boolean {
            val name = packageName ?: return false
            return name in SPOTIFY_PACKAGES || name.startsWith("com.spotify.")
        }

        fun hasAccess(context: Context): Boolean {
            val enabled = try {
                NotificationManagerCompat.getEnabledListenerPackages(context)
            } catch (_: Exception) {
                emptySet<String>()
            }
            if (enabled.contains(context.packageName)) return true

            val listeners = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
            } catch (_: Exception) {
                null
            } ?: return false

            val component = ComponentName(context, SpotifyNotificationListener::class.java)
            return listeners.contains(component.flattenToString()) ||
                listeners.contains(component.flattenToShortString()) ||
                listeners.contains(context.packageName)
        }

        /** Asks the system to rebind the listener after the user returns from Settings. */
        fun requestRebind(context: Context) {
            if (!hasAccess(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, SpotifyNotificationListener::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Rebind request failed", e)
            }
        }

        /** Reads the current Spotify state once, for when the app comes back to the foreground. */
        fun refreshNowPlaying(context: Context) {
            if (!hasAccess(context)) return
            try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return
                val component = ComponentName(context, SpotifyNotificationListener::class.java)
                val controller = manager.getActiveSessions(component)
                    .firstOrNull { isSpotifyPackage(it.packageName) }
                    ?: return
                instance?.publishFromController(controller, "Spotify media session")
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh the Spotify state", e)
            }
        }
    }
}
