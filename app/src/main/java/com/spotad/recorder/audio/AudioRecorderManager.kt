package com.spotad.recorder.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Captures the device's playback audio during an ad break and writes a real, standards-compliant
 * 16-bit PCM WAV file.
 *
 * Design rules that the previous implementation got wrong and that this one enforces:
 *  - No synthetic "fallback audio". If the system will not let us capture, we report an error;
 *    we never invent a tone and present it as a recording.
 *  - The file extension matches the container (`.wav`), and the header is patched with the real
 *    data size when the capture ends, so every player can open it.
 *  - The reported duration comes from the number of bytes actually written, not from a timer.
 *  - Capture happens on a dedicated thread with a hard byte/time limit, so it can always be
 *    stopped deterministically.
 */
class AudioRecorderManager(private val context: Context) {

    data class CaptureResult(
        val file: File,
        val durationMillis: Long,
        val sampleRate: Int,
        val peakAmplitude: Float,
        val waveform: List<Float>
    ) {
        val isSilent: Boolean get() = peakAmplitude < SILENCE_PEAK
    }

    @Volatile
    private var recording = false

    @Volatile
    private var finishedResult: CaptureResult? = null

    @Volatile
    private var lastError: String? = null

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var outputFile: File? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var maxBytes = 0L

    @Volatile
    private var bytesWritten = 0L

    @Volatile
    private var peakSample = 0

    private val amplitudeSamples = ArrayList<Float>()

    /** Optional callback invoked from the capture thread with the latest peak level (0..1). */
    @Volatile
    var onAmplitude: ((Float) -> Unit)? = null

    fun isRecording(): Boolean = recording

    fun lastErrorMessage(): String? = lastError

    /**
     * Starts capturing playback audio. Returns `false` when the platform refuses to set up the
     * capture; [lastErrorMessage] then explains why.
     */
    fun start(
        projection: MediaProjection?,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        maxDurationSeconds: Int = 180
    ): Boolean {
        if (recording || captureThread?.isAlive == true) return false
        lastError = null
        finishedResult = null
        amplitudeSamples.clear()
        bytesWritten = 0
        peakSample = 0

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastError = "Internal audio capture requires Android 10 or newer"
            return false
        }
        if (projection == null) {
            lastError = "Audio capture permission was not granted"
            return false
        }

        this.sampleRate = sampleRate
        maxBytes = sampleRate.toLong() * 2L * maxDurationSeconds.coerceIn(5, 600)

        val minBufferSize = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not query the recorder buffer size", e)
            -1
        }
        if (minBufferSize <= 0) {
            lastError = "This device does not support 44.1 kHz mono capture"
            return false
        }
        val bufferSize = max(minBufferSize, 8192)

        val record = try {
            createPlaybackRecord(projection, sampleRate, bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback capture could not be created", e)
            lastError = when (e) {
                is SecurityException -> "Android blocked audio capture. Re-grant capture access."
                is IllegalStateException -> "Audio capture session is no longer valid. Re-grant access."
                else -> "This device cannot capture app audio (${e.javaClass.simpleName})"
            }
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            lastError = "The audio recorder could not start on this device"
            return false
        }

        val file = newAdFile()
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(wavHeader(0, sampleRate))
            }
        } catch (e: Exception) {
            record.release()
            lastError = "Could not create the recording file"
            Log.e(TAG, "Failed to create capture file", e)
            return false
        }

        outputFile = file
        audioRecord = record

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            file.delete()
            lastError = "Android refused to start the audio capture session"
            Log.e(TAG, "startRecording failed", e)
            return false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audioRecord = null
            file.delete()
            lastError = "Audio capture did not start"
            return false
        }

        recording = true
        captureThread = Thread({ captureLoop(record, file) }, "spotad-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPlaybackRecord(
        projection: MediaProjection,
        sampleRate: Int,
        bufferSize: Int
    ): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    /**
     * Stops the capture and returns the finalized file. Returns `null` when nothing was captured.
     */
    fun stop(): CaptureResult? {
        if (!recording && captureThread == null) return finishedResult
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // The recorder can already be in an invalid state after a device teardown.
        }
        try {
            captureThread?.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        return finishedResult
    }

    /** Stops the capture and removes the partial file. */
    fun abort() {
        stop()
        outputFile?.let { file ->
            if (file.exists()) file.delete()
        }
        outputFile = null
        finishedResult = null
    }

    private fun captureLoop(record: AudioRecord, file: File) {
        val buffer = ShortArray(4096)
        val byteBuffer = ByteArray(buffer.size * 2)
        var lastAmplitudeByteMark = 0L
        val amplitudeIntervalBytes = (sampleRate * 2L) / 25L // a level reading every 40 ms

        try {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(44)
            while (recording) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio read failed", e)
                    lastError = "Audio capture stopped unexpectedly"
                    break
                }

                if (read <= 0) {
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        lastError = "The audio capture session was closed by the system"
                        break
                    }
                    continue
                }

                var chunkPeak = 0
                for (i in 0 until read) {
                    val sample = buffer[i].toInt()
                    val magnitude = abs(sample)
                    if (magnitude > chunkPeak) chunkPeak = magnitude
                    byteBuffer[i * 2] = (sample and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }

                try {
                    raf.write(byteBuffer, 0, read * 2)
                } catch (e: Exception) {
                    Log.e(TAG, "Writing audio data failed", e)
                    lastError = "Could not write the recording (storage full?)"
                    break
                }

                bytesWritten += read * 2L
                if (chunkPeak > peakSample) peakSample = chunkPeak

                try {
                    onAmplitude?.invoke((chunkPeak / 32767f).coerceIn(0f, 1f))
                } catch (_: Exception) {
                    // A slow listener must never interrupt the capture.
                }

                if (bytesWritten - lastAmplitudeByteMark >= amplitudeIntervalBytes) {
                    amplitudeSamples.add((chunkPeak / 32767f).coerceIn(0f, 1f))
                    lastAmplitudeByteMark = bytesWritten
                }

                if (bytesWritten >= maxBytes) {
                    Log.i(TAG, "Capture reached the configured maximum length")
                    break
                }
            }

            val dataLength = bytesWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            try {
                raf.seek(0)
                raf.write(wavHeader(dataLength.toLong(), sampleRate))
            } catch (e: Exception) {
                Log.e(TAG, "Could not finalize the WAV header", e)
            }
        }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop failed", e)
            lastError = "Recording failed (${e.javaClass.simpleName})"
        }

        releaseRecorder(record)
        recording = false

        val dataBytes = bytesWritten
        val duration = if (dataBytes > 0) dataBytes * 1000L / (sampleRate.toLong() * 2L) else 0L
        val peak = peakSample / 32767f

        finishedResult = if (dataBytes > 0 && file.exists()) {
            CaptureResult(
                file = file,
                durationMillis = duration,
                sampleRate = sampleRate,
                peakAmplitude = peak,
                waveform = resampleWaveform(amplitudeSamples, WAVEFORM_BARS)
            )
        } else {
            file.delete()
            null
        }
    }

    private fun releaseRecorder(record: AudioRecord) {
        try {
            record.stop()
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
        if (audioRecord === record) audioRecord = null
    }

    private fun newAdFile(): File {
        val directory = adsDirectory(context)
        val name = "ad_${System.currentTimeMillis()}.wav"
        return File(directory, name)
    }

    /**
     * Reduces arbitrary many level readings to a fixed number of bars. The result is honest: an
     * empty recording produces an empty waveform instead of a decorative pattern.
     */
    fun resampleWaveform(samples: List<Float>, bars: Int = WAVEFORM_BARS): List<Float> {
        if (samples.isEmpty() || bars <= 0) return emptyList()
        if (samples.size <= bars) return samples.map { it.coerceIn(0f, 1f) }
        val result = ArrayList<Float>(bars)
        val bucketSize = samples.size.toFloat() / bars
        for (i in 0 until bars) {
            val start = (i * bucketSize).toInt().coerceIn(0, samples.lastIndex)
            val end = (((i + 1) * bucketSize).toInt()).coerceIn(start + 1, samples.size)
            var sum = 0f
            for (index in start until end) sum += samples[index]
            result.add((sum / (end - start)).coerceIn(0f, 1f))
        }
        return result
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val DEFAULT_SAMPLE_RATE = 44100
        const val WAVEFORM_BARS = 48
        private const val SILENCE_PEAK = 0.0025f

        fun adsDirectory(context: Context): File =
            File(context.filesDir, "ads").apply { mkdirs() }

        /** Builds a little-endian PCM WAV header. */
        fun wavHeader(dataLength: Long, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
            val header = ByteArray(44)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
            writeIntLE(header, 4, (36 + dataLength).toInt())
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
            "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
            writeIntLE(header, 16, 16)
            writeShortLE(header, 20, 1)
            writeShortLE(header, 22, channels)
            writeIntLE(header, 24, sampleRate)
            writeIntLE(header, 28, byteRate)
            writeShortLE(header, 32, blockAlign)
            writeShortLE(header, 34, bitsPerSample)
            "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
            writeIntLE(header, 40, dataLength.toInt())
            return header
        }

        private fun writeIntLE(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value shr 8) and 0xFF).toByte()
            target[offset + 2] = ((value shr 16) and 0xFF).toByte()
            target[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun writeShortLE(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        fun fileNameFor(timestamp: Long): String = String.format(
            Locale.US,
            "ad_%d.wav",
            timestamp
        )
    }
}
