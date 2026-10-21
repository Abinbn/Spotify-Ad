package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val adId: Long? = null,
    val filePath: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val error: String? = null
)

/**
 * Small single-track player for the library.
 *
 * Adds the pieces a media player needs to behave in a real phone: audio focus so music ducks
 * politely, pause-on-headphone-unplug, and a surfaced error instead of a silent no-op when the
 * file is gone.
 */
class AudioPlayerManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private var receiverRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.25f, 0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
            }
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register the headphone receiver", e)
        }
    }

    fun play(adId: Long, filePath: String) {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            _playbackState.value = _playbackState.value.copy(
                error = "This recording's audio file is missing"
            )
            return
        }

        if (_playbackState.value.adId == adId && mediaPlayer != null) {
            if (_playbackState.value.isPlaying) pause() else resume()
            return
        }

        stop()
        requestFocus()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, Uri.fromFile(file))
                prepare()
            }
            mediaPlayer = player
            val duration = player.duration.toLong().coerceAtLeast(0L)

            player.setOnCompletionListener {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    currentPositionMs = duration
                )
                stopProgressUpdates()
                abandonFocus()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                stop()
                _playbackState.value = _playbackState.value.copy(
                    error = "This file could not be played (code $what)"
                )
                true
            }

            applySpeed(_playbackState.value.speed)
            player.start()

            _playbackState.value = PlaybackState(
                adId = adId,
                filePath = filePath,
                isPlaying = true,
                currentPositionMs = 0L,
                durationMs = duration,
                speed = _playbackState.value.speed
            )
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file", e)
            stop()
            _playbackState.value = _playbackState.value.copy(
                error = "This file could not be played"
            )
        }
    }

    fun pause() {
        try {
            mediaPlayer?.takeIf { it.isPlaying }?.let {
                it.pause()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    currentPositionMs = it.currentPosition.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing", e)
        }
        stopProgressUpdates()
    }

    fun resume() {
        val player = mediaPlayer ?: return
        if (!requestFocus()) return
        try {
            applySpeed(_playbackState.value.speed)
            player.start()
            _playbackState.value = _playbackState.value.copy(isPlaying = true, error = null)
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming", e)
        }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration.toLong().coerceAtLeast(0L)
            val target = positionMs.coerceIn(0L, duration)
            player.seekTo(target.toInt())
            _playbackState.value = _playbackState.value.copy(currentPositionMs = target)
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking", e)
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _playbackState.value = _playbackState.value.copy(speed = clamped)
        applySpeed(clamped)
    }

    fun stop() {
        stopProgressUpdates()
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
        mediaPlayer = null
        _playbackState.value = PlaybackState(speed = _playbackState.value.speed)
        abandonFocus()
    }

    fun release() {
        stop()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    fun clearError() {
        _playbackState.value = _playbackState.value.copy(error = null)
    }

    private fun applySpeed(speed: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            mediaPlayer?.playbackParams = PlaybackParams().apply { this.speed = speed }
        } catch (e: Exception) {
            Log.w(TAG, "Playback speed is not supported for this file", e)
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                val player = mediaPlayer ?: break
                val position = try {
                    player.currentPosition.toLong()
                } catch (_: Exception) {
                    0L
                }
                _playbackState.value = _playbackState.value.copy(currentPositionMs = position)
                delay(100)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { focusRequest = it }
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus", e)
            true
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AudioPlayerManager"
    }
}
