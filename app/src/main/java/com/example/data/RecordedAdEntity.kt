package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recorded_ads")
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
    val audioConfidence: Float = 0.95f,
    val transcriptSnippet: String = "",
    val waveformPoints: String = ""
)
