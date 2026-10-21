package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordedAdDao {
    @Query("SELECT * FROM recorded_ads ORDER BY timestamp DESC")
    fun getAllAds(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads ORDER BY timestamp DESC")
    fun getAdsSortedByDateDesc(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads ORDER BY timestamp ASC")
    fun getAdsSortedByDateAsc(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads ORDER BY durationMillis DESC")
    fun getAdsSortedByDurationDesc(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads ORDER BY durationMillis ASC")
    fun getAdsSortedByDurationAsc(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads WHERE id = :id LIMIT 1")
    suspend fun getAdById(id: Long): RecordedAdEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ad: RecordedAdEntity): Long

    @Update
    suspend fun update(ad: RecordedAdEntity)

    @Delete
    suspend fun delete(ad: RecordedAdEntity)

    @Query("DELETE FROM recorded_ads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE recorded_ads SET isFavorite = :isFav WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFav: Boolean)

    @Query("UPDATE recorded_ads SET title = :newTitle, advertiser = :newAdvertiser, themeTags = :newThemes WHERE id = :id")
    suspend fun updateMetadata(id: Long, newTitle: String, newAdvertiser: String, newThemes: String)

    // Automated 30-Day Cleanup Queries (exempting Favorites)
    @Query("SELECT * FROM recorded_ads WHERE timestamp < :cutoffTimestamp AND isFavorite = 0")
    suspend fun getOldNonFavoriteAds(cutoffTimestamp: Long): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads WHERE timestamp < :cutoffTimestamp AND isFavorite = 0")
    suspend fun deleteOldNonFavoriteAds(cutoffTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM recorded_ads WHERE timestamp < :cutoffTimestamp AND isFavorite = 0")
    suspend fun countOldNonFavoriteAds(cutoffTimestamp: Long): Int

    // Purge any demo/sample recordings
    @Query("SELECT * FROM recorded_ads WHERE filePath LIKE '%sample%' OR title LIKE '%Free Premium%'")
    suspend fun getSampleAds(): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads WHERE filePath LIKE '%sample%' OR title LIKE '%Free Premium%'")
    suspend fun deleteSampleAds(): Int

    @Query("SELECT * FROM recorded_ads WHERE isFavorite = 0")
    suspend fun getAllNonFavoriteAds(): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads WHERE isFavorite = 0")
    suspend fun deleteAllNonFavoriteAds(): Int

    @Query("SELECT * FROM recorded_ads")
    suspend fun getAllAdsSnapshot(): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads")
    suspend fun deleteAllAds(): Int
}
