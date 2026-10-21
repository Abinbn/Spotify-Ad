package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
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
    val speed: Float = 1.0f
)

class AudioPlayerManager(private val context: Context) {

    private val tag = "AudioPlayerManager"
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun play(adId: Long, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(tag, "Audio file does not exist: $filePath")
            return
        }

        // If already playing this item, toggle pause/resume
        if (_playbackState.value.adId == adId && mediaPlayer != null) {
            if (_playbackState.value.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }

        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                val totalDuration = duration.toLong()

                setOnCompletionListener {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        currentPositionMs = totalDuration
                    )
                    stopProgressTracking()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    stop()
                    true
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playbackParams = PlaybackParams().apply {
                        speed = _playbackState.value.speed
                    }
                }

                start()
                _playbackState.value = PlaybackState(
                    adId = adId,
                    filePath = filePath,
                    isPlaying = true,
                    currentPositionMs = 0L,
                    durationMs = totalDuration,
                    speed = _playbackState.value.speed
                )
                startProgressTracking()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error playing audio file", e)
            stop()
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        currentPositionMs = it.currentPosition.toLong()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error pausing player", e)
        }
        stopProgressTracking()
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                it.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                startProgressTracking()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error resuming player", e)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.let {
                val clamped = positionMs.coerceIn(0L, it.duration.toLong()).toInt()
                it.seekTo(clamped)
                _playbackState.value = _playbackState.value.copy(currentPositionMs = clamped.toLong())
            }
        } catch (e: Exception) {
            Log.e(tag, "Error seeking player", e)
        }
    }

    fun setSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.5f, 2.5f)
        _playbackState.value = _playbackState.value.copy(speed = clampedSpeed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.playbackParams = PlaybackParams().apply { this.speed = clampedSpeed }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error setting playback speed", e)
            }
        }
    }

    fun stop() {
        stopProgressTracking()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping player", e)
        }
        mediaPlayer = null
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = 0L
        )
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                _playbackState.value = _playbackState.value.copy(currentPositionMs = pos)
                delay(80)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stop()
    }
}
