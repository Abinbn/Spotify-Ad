package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured ad clip.
 *
 * Only facts are stored. Transcripts and keywords are left empty when the app could not actually
 * read them, instead of being filled with invented placeholder text.
 */
@Entity(
    tableName = "recorded_ads",
    indices = [Index("timestamp"), Index("isFavorite")]
)
data class RecordedAdEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val advertiser: String,
    val filePath: String,
    val durationMillis: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val fileSizeBytes: Long = 0,
    val spokenKeywords: String = "",
    val themeTags: String = "",
    val isFavorite: Boolean = false,
    val audioConfidence: Float = 0f,
    val transcriptSnippet: String = "",
    val waveformPoints: String = "",
    val sampleRate: Int = 44100,
    /** Where the trigger came from, e.g. "Spotify notification" or "Manual capture". */
    val source: String = "",
    val notes: String = ""
) {
    val displayTitle: String
        get() = title.ifBlank { "Untitled ad" }

    val displayAdvertiser: String
        get() = advertiser.ifBlank { "Unknown advertiser" }

    val keywords: List<String>
        get() = spokenKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val themes: List<String>
        get() = themeTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val waveform: List<Float>
        get() = waveformPoints.split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it in 0f..1f }
}
