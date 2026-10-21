package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.RecordedAdEntity
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportManager {

    private const val TAG = "ExportManager"

    /**
     * Exports the recorded ad MP3 file and companion metadata JSON to the public Music/SpotifyAds folder.
     */
    fun exportToPublicMusic(context: Context, ad: RecordedAdEntity): Result<Uri> {
        return try {
            val sourceFile = File(ad.filePath)
            if (!sourceFile.exists()) {
                return Result.failure(IllegalStateException("Recorded file does not exist: ${ad.filePath}"))
            }

            val sanitizedTitle = ad.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ad.timestamp))
            val exportFileName = "SpotifyAd_${ad.advertiser}_${sanitizedTitle}_$dateStr.mp3"

            // Save JSON metadata alongside
            saveCompanionMetadataJson(sourceFile, ad)

            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, exportFileName)
                put(MediaStore.Audio.Media.TITLE, ad.title)
                put(MediaStore.Audio.Media.ARTIST, ad.advertiser)
                put(MediaStore.Audio.Media.ALBUM, "Spotify Ads Library")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.DURATION, ad.durationMillis)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SpotifyAds")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return Result.failure(IllegalStateException("Failed to create MediaStore entry"))

            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting ad audio", e)
            Result.failure(e)
        }
    }

    /**
     * Creates and saves a .json companion metadata file alongside the audio file.
     */
    fun saveCompanionMetadataJson(audioFile: File, ad: RecordedAdEntity): File {
        val jsonFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + "_metadata.json")
        val json = JSONObject().apply {
            put("id", ad.id)
            put("title", ad.title)
            put("advertiser", ad.advertiser)
            put("duration_ms", ad.durationMillis)
            put("recording_timestamp", ad.timestamp)
            put("recording_date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ad.timestamp)))
            put("file_size_bytes", ad.fileSizeBytes)
            put("spoken_keywords", ad.spokenKeywords)
            put("theme_tags", ad.themeTags)
            put("is_favorite", ad.isFavorite)
            put("audio_fingerprint_confidence", ad.audioConfidence)
            put("transcript_snippet", ad.transcriptSnippet)
        }
        jsonFile.writeText(json.toString(2))
        return jsonFile
    }

    /**
     * Opens Android System Share Sheet to send the ad MP3 to WhatsApp, Drive, Telegram, etc.
     */
    fun shareAd(context: Context, ad: RecordedAdEntity) {
        try {
            val audioFile = File(ad.filePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "Cannot share: file missing ${ad.filePath}")
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                audioFile
            )

            val shareText = buildString {
                append("Spotify Ad: \"${ad.title}\"\n")
                append("Advertiser: ${ad.advertiser}\n")
                append("Duration: ${ad.durationMillis / 1000}s\n")
                if (ad.spokenKeywords.isNotBlank()) {
                    append("Keywords: ${ad.spokenKeywords}\n")
                }
                if (ad.themeTags.isNotBlank()) {
                    append("Themes: ${ad.themeTags}\n")
                }
                if (ad.transcriptSnippet.isNotBlank()) {
                    append("\n\"${ad.transcriptSnippet}\"")
                }
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Recorded Spotify Ad - ${ad.title}")
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Spotify Ad MP3")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share ad", e)
        }
    }
}
