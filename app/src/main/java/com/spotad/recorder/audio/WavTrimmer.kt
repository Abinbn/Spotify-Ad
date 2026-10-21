package com.spotad.recorder.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Lossless trimming for the WAV files SpotAd writes.
 *
 * Because the capture is linear PCM, cutting a clip is a header edit plus a byte copy - no
 * re-encoding, no quality loss, no dependency on an encoder that may not exist on the device.
 * Everything here works on 16-bit PCM only; anything else is rejected instead of corrupted.
 */
object WavTrimmer {

    private const val HEADER_SIZE = 44L

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long
    ) {
        val frameSize: Int get() = max(1, channels * bitsPerSample / 8)
        val durationMillis: Long
            get() = if (sampleRate <= 0) 0L else dataLength * 1000L / (sampleRate.toLong() * frameSize)
    }

    /**
     * Reads the RIFF chunks of [file]. Returns `null` when the file is missing, truncated or not a
     * 16-bit PCM WAV - callers should treat that as "cannot trim" rather than guessing.
     */
    fun readInfo(file: File): Info? {
        if (!file.isFile || file.length() < HEADER_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(4).also { raf.readFully(it) }
                if (String(riff, Charsets.US_ASCII) != "RIFF") return null
                raf.skipBytes(4)
                val wave = ByteArray(4).also { raf.readFully(it) }
                if (String(wave, Charsets.US_ASCII) != "WAVE") return null

                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataLength = 0L

                val fileLength = raf.length()
                while (raf.filePointer + 8 <= fileLength) {
                    val id = ByteArray(4).also { raf.readFully(it) }
                    val size = readIntLE(raf).toLong() and 0xFFFFFFFFL
                    val bodyStart = raf.filePointer
                    when (String(id, Charsets.US_ASCII)) {
                        "fmt " -> {
                            val format = readShortLE(raf)
                            channels = readShortLE(raf)
                            sampleRate = readIntLE(raf)
                            raf.skipBytes(6) // byte rate + block align
                            bitsPerSample = readShortLE(raf)
                            // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (also PCM here).
                            if (format != 1 && format != 0xFFFE) return null
                        }
                        "data" -> {
                            dataOffset = bodyStart
                            // Some writers leave 0 or 0xFFFFFFFF when streaming; trust the file size.
                            dataLength = if (size <= 0 || bodyStart + size > fileLength) {
                                fileLength - bodyStart
                            } else {
                                size
                            }
                        }
                        else -> Unit
                    }
                    if (bodyStart + size > fileLength) break
                    raf.seek(bodyStart + size + (size and 1L)) // chunks are word-aligned
                }

                if (sampleRate <= 0 || channels <= 0 || bitsPerSample != 16 ||
                    dataOffset < 0 || dataLength <= 0
                ) {
                    null
                } else {
                    Info(sampleRate, channels, bitsPerSample, dataOffset, dataLength)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cuts [source] down to [startMillis]..[endMillis] and writes the result to [destination].
     *
     * The cut points are snapped to whole sample frames so the output stays a valid WAV. The
     * source file is not modified: the caller decides when to swap the files.
     */
    fun trim(source: File, destination: File, startMillis: Long, endMillis: Long): Info? {
        val info = readInfo(source) ?: return null
        val duration = info.durationMillis
        if (duration <= 0L) return null

        val start = startMillis.coerceIn(0L, duration)
        val end = endMillis.coerceIn(start, duration)
        val bytesPerMs = info.sampleRate.toLong() * info.frameSize / 1000.0
        var startByte = (start * bytesPerMs).roundToLong()
        var endByte = (end * bytesPerMs).roundToLong()

        startByte -= startByte % info.frameSize
        endByte -= endByte % info.frameSize
        endByte = min(endByte, info.dataLength)
        startByte = max(0L, min(startByte, endByte))
        val length = endByte - startByte
        if (length <= 0L) return null

        return try {
            destination.parentFile?.mkdirs()
            RandomAccessFile(source, "r").use { input ->
                RandomAccessFile(destination, "rw").use { output ->
                    output.setLength(0)
                    output.write(
                        AudioRecorderManager.wavHeader(
                            dataLength = length,
                            sampleRate = info.sampleRate,
                            channels = info.channels,
                            bitsPerSample = info.bitsPerSample
                        )
                    )
                    input.seek(info.dataOffset + startByte)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    if (remaining != 0L) {
                        // Truncated source: do not leave a half-written clip behind.
                        output.close()
                        destination.delete()
                        return null
                    }
                }
            }
            readInfo(destination)
        } catch (e: Exception) {
            destination.delete()
            null
        }
    }

    /** Replaces [target] with a trimmed copy, keeping a backup only until the swap succeeds. */
    fun trimInPlace(target: File, startMillis: Long, endMillis: Long): Info? {
        val temp = File(target.parentFile, "${target.nameWithoutExtension}.trim.wav")
        val trimmed = trim(target, temp, startMillis, endMillis) ?: run {
            temp.delete()
            return null
        }
        val backup = File(target.parentFile, "${target.nameWithoutExtension}.orig.wav")
        backup.delete()
        if (!target.renameTo(backup)) {
            temp.delete()
            return null
        }
        if (!temp.renameTo(target)) {
            backup.renameTo(target)
            temp.delete()
            return null
        }
        backup.delete()
        return trimmed
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b3 < 0) throw java.io.EOFException()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b1 < 0) throw java.io.EOFException()
        return b0 or (b1 shl 8)
    }
}
