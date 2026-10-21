package com.spotad.recorder.audio

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.spotad.recorder.data.RecordedAdEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moves recordings out of the app sandbox (Music folder, share sheet) and renders the metadata
 * text used by "copy details".
 *
 * Files are WAV, so both the MIME type and the extension are `wav`. The previous code exported
 * WAV data under an `.mp3` name with an `audio/mpeg` MIME type, which made exports unplayable in
 * several apps and unsearchable in MediaStore.
 */
object ExportManager {

    private const val TAG = "ExportManager"
    private const val MIME_WAV = "audio/wav"
    private const val FOLDER = "SpotAd"

    fun exportToMusic(context: Context, ad: RecordedAdEntity): Result<Uri> = runCatching {
        val source = File(ad.filePath)
        require(source.exists() && source.length() > 0) { "The recording file is missing" }

        val displayName = buildFileName(ad)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(context, ad, source, displayName)
        } else {
            exportToPublicDirectory(context, source, displayName)
        }
    }.onFailure { Log.e(TAG, "Export failed", it) }

    private fun exportWithMediaStore(
        context: Context,
        ad: RecordedAdEntity,
        source: File,
        displayName: String
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, ad.displayTitle)
            put(MediaStore.Audio.Media.ARTIST, ad.displayAdvertiser)
            put(MediaStore.Audio.Media.ALBUM, "SpotAd recordings")
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_WAV)
            put(MediaStore.Audio.Media.DURATION, ad.durationMillis)
            put(MediaStore.Audio.Media.DATE_ADDED, ad.timestamp / 1000)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the exported file")

        try {
            val output = resolver.openOutputStream(uri) ?: error("Could not open the export target")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        val clearPending = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        resolver.update(uri, clearPending, null, null)

        // Make the file visible to gallery / music apps immediately on every OEM.
        try {
            MediaScannerConnection.scanFile(context, arrayOf(source.absolutePath), arrayOf(MIME_WAV), null)
        } catch (_: Exception) {
        }

        return uri
    }

    @Suppress("DEPRECATION")
    private fun exportToPublicDirectory(context: Context, source: File, displayName: String): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            FOLDER
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create the Music/$FOLDER folder")
        }
        val target = File(directory, displayName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target)
    }

    fun share(context: Context, ad: RecordedAdEntity): Result<Unit> = runCatching {
        val file = File(ad.filePath)
        require(file.exists()) { "The recording file is missing" }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_WAV
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ad.displayTitle)
            putExtra(Intent.EXTRA_TEXT, detailsText(ad))
            clipData = ClipData.newRawUri(ad.displayTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(send, "Share recording").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }.onFailure { Log.e(TAG, "Share failed", it) }

    fun detailsText(ad: RecordedAdEntity): String {
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ad.timestamp))
        return buildString {
            appendLine(ad.displayTitle)
            appendLine("Advertiser: ${ad.displayAdvertiser}")
            appendLine("Recorded: $date")
            appendLine("Duration: ${formatDuration(ad.durationMillis)}")
            if (ad.keywords.isNotEmpty()) appendLine("Keywords: ${ad.keywords.joinToString(", ")}")
            if (ad.themes.isNotEmpty()) appendLine("Themes: ${ad.themes.joinToString(", ")}")
            if (ad.notes.isNotBlank()) appendLine("Notes: ${ad.notes}")
            appendLine("Captured with SpotAd — audio stays on this device unless you export it.")
        }
    }

    fun buildFileName(ad: RecordedAdEntity): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ad.timestamp))
        val brand = ad.displayAdvertiser.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24)
        val title = ad.displayTitle.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40)
        val stem = listOf(brand, title).filter { it.isNotBlank() }.joinToString("_").ifBlank { "spotad" }
        return "${stem}_$date.wav"
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
        else -> "$bytes B"
    }

    fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
}
