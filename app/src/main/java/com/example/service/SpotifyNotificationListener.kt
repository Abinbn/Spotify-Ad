package com.example.service

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
import com.example.audio.AudioFingerprintEngine

class SpotifyNotificationListener : NotificationListenerService() {

    private val tag = "SpotifyNotifListener"

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeSessionsChangedListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registeredSpotifyControllers = mutableMapOf<String, MediaController>()

    companion object {
        var instance: SpotifyNotificationListener? = null
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            try {
                val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                if (enabledPackages.contains(context.packageName)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w("SpotifyNotifListener", "Failed checking NotificationManagerCompat: ${e.message}")
            }

            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, SpotifyNotificationListener::class.java).flattenToString()
            return enabledListeners.contains(myComponent) || enabledListeners.contains(context.packageName)
        }

        fun rebindIfPossible(context: Context) {
            if (!isNotificationAccessGranted(context)) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, SpotifyNotificationListener::class.java))
                    Log.d("SpotifyNotifListener", "Requested listener rebind")
                } catch (e: Exception) {
                    Log.w("SpotifyNotifListener", "Could not request rebind: ${e.message}")
                }
            }

            // If listener instance is not bound yet, toggle component to force OS to rebind
            if (instance == null) {
                try {
                    val comp = ComponentName(context, SpotifyNotificationListener::class.java)
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        comp,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        comp,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    Log.d("SpotifyNotifListener", "Toggled listener component to force rebind")
                } catch (e: Exception) {
                    Log.w("SpotifyNotifListener", "Could not toggle component: ${e.message}")
                }
            }
        }

        /**
         * Checks active MediaSessions directly from Context if notification access is granted.
         */
        fun queryActiveSpotifyMediaSession(context: Context): Boolean {
            if (!isNotificationAccessGranted(context)) return false
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
                val component = ComponentName(context, SpotifyNotificationListener::class.java)
                val sessions = msm.getActiveSessions(component)
                for (controller in sessions) {
                    if (controller.packageName.contains("spotify", ignoreCase = true)) {
                        dispatchFromMediaController(context, controller, "MediaSessionManager query")
                        return true
                    }
                }
            } catch (e: SecurityException) {
                Log.w("SpotifyNotifListener", "SecurityException querying MediaSessions: ${e.message}")
            } catch (e: Exception) {
                Log.w("SpotifyNotifListener", "Error querying MediaSessions: ${e.message}")
            }
            return false
        }

        fun dispatchFromMediaController(
            context: Context,
            controller: MediaController,
            source: String
        ) {
            val metadata = controller.metadata
            val pbState = controller.playbackState

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
            val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val positionMs = pbState?.position ?: 0L
            val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING ||
                    pbState?.state == PlaybackState.STATE_BUFFERING ||
                    pbState?.state == PlaybackState.STATE_FAST_FORWARDING ||
                    pbState?.state == PlaybackState.STATE_REWINDING

            if (title.isBlank() && artist.isBlank()) {
                // If title is blank, but it's Spotify playing, use fallback
                if (isPlaying) {
                    title = "Spotify Active Audio"
                    artist = "Spotify"
                } else {
                    return
                }
            }

            val fingerprint = AudioFingerprintEngine.evaluateAdBreak(
                trackId = null,
                trackTitle = title,
                artist = artist,
                lengthMillis = if (durationMs > 0) durationMs else 30_000L
            )

            val isAd = fingerprint.isAdBreak ||
                    title.contains("advertis", ignoreCase = true) ||
                    artist.contains("advertis", ignoreCase = true) ||
                    title.equals("advertisement", ignoreCase = true) ||
                    title.equals("ad", ignoreCase = true)

            val contentType = when {
                isAd -> "Advertisement"
                durationMs > 600_000L || album.contains("podcast", ignoreCase = true) || title.contains("episode", ignoreCase = true) -> "Podcast Episode"
                else -> "Music Track"
            }

            val event = SpotifyAdEvent(
                trackId = null,
                trackTitle = if (title.isBlank() && isAd) "Spotify Commercial" else title,
                artist = if (artist.isBlank() && isAd) "Spotify Sponsor" else artist,
                album = album,
                lengthMillis = durationMs,
                currentPositionMillis = positionMs,
                isPlaying = isPlaying,
                isAdBreak = isAd,
                confidence = if (isAd) maxOf(fingerprint.confidence, 0.92f) else fingerprint.confidence,
                source = source,
                contentType = contentType,
                timestamp = pbState?.lastPositionUpdateTime.takeIf { it != null && it > 0 } ?: System.currentTimeMillis()
            )

            AdRecordingService.dispatchSpotifyEvent(context, event)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(tag, "SpotifyNotificationListener connected to notification subsystem")

        // Register MediaSession active sessions listener
        setupMediaSessionMonitoring()

        // Check currently active notifications
        checkActiveSpotifyNotifications()
    }

    private fun setupMediaSessionMonitoring() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, SpotifyNotificationListener::class.java)

            activeSessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                Log.d(tag, "Active media sessions changed: ${controllers?.size} sessions")
                inspectMediaControllers(controllers)
            }

            mediaSessionManager?.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener!!,
                component
            )

            // Inspect currently active sessions
            val sessions = mediaSessionManager?.getActiveSessions(component)
            inspectMediaControllers(sessions)
        } catch (e: Exception) {
            Log.w(tag, "Could not set up MediaSessionManager listener: ${e.message}")
        }
    }

    private fun inspectMediaControllers(controllers: List<MediaController>?) {
        if (controllers == null) return
        for (controller in controllers) {
            val pkg = controller.packageName ?: continue
            if (pkg.contains("spotify", ignoreCase = true)) {
                Log.i(tag, "Found active Spotify MediaController: $pkg")
                dispatchFromMediaController(applicationContext, controller, "MediaSession: $pkg")

                // Register real-time callback if not already registered
                val sessionTokenKey = controller.sessionToken.toString()
                if (!registeredSpotifyControllers.containsKey(sessionTokenKey)) {
                    registeredSpotifyControllers[sessionTokenKey] = controller
                    controller.registerCallback(object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            Log.d(tag, "Spotify MediaController: metadata changed")
                            dispatchFromMediaController(applicationContext, controller, "MediaSession MetadataChanged")
                        }

                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            Log.d(tag, "Spotify MediaController: playback state changed -> ${state?.state}")
                            dispatchFromMediaController(applicationContext, controller, "MediaSession PlaybackStateChanged")
                        }

                        override fun onSessionDestroyed() {
                            Log.d(tag, "Spotify MediaController: session destroyed")
                            registeredSpotifyControllers.remove(sessionTokenKey)
                            AdRecordingService.dispatchSpotifyStopped(applicationContext)
                        }
                    })
                }
            }
        }
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
        if (instance == this) instance = null
        try {
            activeSessionsChangedListener?.let {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(it)
            }
        } catch (_: Exception) {}
        registeredSpotifyControllers.clear()
    }

    fun checkActiveSpotifyNotifications() {
        try {
            // Check active status bar notifications
            val activeNotifs = try { activeNotifications } catch (_: Exception) { null }
            if (activeNotifs != null) {
                val spotifyNotifs = activeNotifs.filter {
                    it.packageName?.contains("spotify", ignoreCase = true) == true
                }
                val mediaNotif = spotifyNotifs.firstOrNull { sbn ->
                    val extras = sbn.notification?.extras
                    extras != null && (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                            !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
                } ?: spotifyNotifs.firstOrNull()

                if (mediaNotif != null) {
                    processSpotifyNotification(mediaNotif)
                }
            }

            // Also check active media sessions directly
            mediaSessionManager?.let { msm ->
                val component = ComponentName(this, SpotifyNotificationListener::class.java)
                val sessions = msm.getActiveSessions(component)
                inspectMediaControllers(sessions)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error inspecting active Spotify notifications or sessions", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName ?: return

        if (!packageName.contains("spotify", ignoreCase = true)) {
            return
        }

        if (AdRecordingService.isTravelMode(applicationContext)) {
            Log.d(tag, "Travel Mode active: Ignoring notification")
            return
        }

        processSpotifyNotification(sbn)
    }

    private fun processSpotifyNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""
        var artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: ""
        var album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        var durationMs = 0L
        var positionMs = 0L
        var isPlaying = true

        // Detect playing vs paused from notification action buttons (Play vs Pause action)
        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                val actTitle = action.title?.toString() ?: ""
                if (actTitle.contains("pause", ignoreCase = true)) {
                    isPlaying = true
                } else if (actTitle.contains("play", ignoreCase = true)) {
                    isPlaying = false
                }
            }
        }

        // Try extracting high-fidelity metadata from MediaSession Token
        try {
            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }

            if (token != null) {
                val controller = MediaController(applicationContext, token)
                val pbState = controller.playbackState
                if (pbState != null) {
                    isPlaying = pbState.state == PlaybackState.STATE_PLAYING ||
                            pbState.state == PlaybackState.STATE_BUFFERING ||
                            pbState.state == PlaybackState.STATE_FAST_FORWARDING ||
                            pbState.state == PlaybackState.STATE_REWINDING
                    positionMs = pbState.position
                }

                val metadata = controller.metadata
                if (metadata != null) {
                    val mediaTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    val mediaArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                    val mediaAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                    val mediaDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    if (!mediaTitle.isNullOrBlank()) title = mediaTitle
                    if (!mediaArtist.isNullOrBlank()) artist = mediaArtist
                    if (!mediaAlbum.isNullOrBlank()) album = mediaAlbum
                    if (mediaDuration > 0) durationMs = mediaDuration
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not extract MediaController metadata from notification", e)
        }

        if (title.isBlank() && artist.isBlank()) {
            return
        }

        Log.d(tag, "Spotify Notification detected: title='$title', artist='$artist', album='$album', duration=${durationMs}ms, pos=${positionMs}ms, isPlaying=$isPlaying")

        // Check if this is an ad
        val fingerprint = AudioFingerprintEngine.evaluateAdBreak(
            trackId = null,
            trackTitle = title,
            artist = artist,
            lengthMillis = if (durationMs > 0) durationMs else 30_000L
        )

        val isAd = fingerprint.isAdBreak ||
                title.contains("advertis", ignoreCase = true) ||
                artist.contains("advertis", ignoreCase = true) ||
                title.equals("advertisement", ignoreCase = true) ||
                title.equals("ad", ignoreCase = true)

        val contentType = when {
            isAd -> "Advertisement"
            durationMs > 600_000L || album.contains("podcast", ignoreCase = true) || title.contains("episode", ignoreCase = true) -> "Podcast Episode"
            else -> "Music Track"
        }

        val event = SpotifyAdEvent(
            trackId = null,
            trackTitle = if (title.isBlank() && isAd) "Spotify Commercial" else title,
            artist = if (artist.isBlank() && isAd) "Spotify Sponsor" else artist,
            album = album,
            lengthMillis = durationMs,
            currentPositionMillis = positionMs,
            isPlaying = isPlaying,
            isAdBreak = isAd,
            confidence = if (isAd) maxOf(fingerprint.confidence, 0.92f) else fingerprint.confidence,
            source = "Spotify Notification",
            contentType = contentType,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        AdRecordingService.dispatchSpotifyEvent(applicationContext, event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg.contains("spotify", ignoreCase = true)) {
            Log.d(tag, "Spotify notification dismissed")
            // Check if there is still an active media session before declaring stopped
            try {
                val component = ComponentName(this, SpotifyNotificationListener::class.java)
                val sessions = mediaSessionManager?.getActiveSessions(component)
                val hasSpotify = sessions?.any { it.packageName.contains("spotify", ignoreCase = true) && it.playbackState?.state == PlaybackState.STATE_PLAYING } == true
                if (!hasSpotify) {
                    AdRecordingService.dispatchSpotifyStopped(applicationContext)
                }
            } catch (e: Exception) {
                AdRecordingService.dispatchSpotifyStopped(applicationContext)
            }
        }
    }
}
