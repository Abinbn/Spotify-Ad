package com.spotad.recorder.data

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
    fun observeAll(): Flow<List<RecordedAdEntity>>

    @Query("SELECT * FROM recorded_ads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecordedAdEntity?

    @Query("SELECT * FROM recorded_ads")
    suspend fun snapshot(): List<RecordedAdEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(ad: RecordedAdEntity): Long

    @Update
    suspend fun update(ad: RecordedAdEntity)

    @Delete
    suspend fun delete(ad: RecordedAdEntity)

    @Query("UPDATE recorded_ads SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query(
        "UPDATE recorded_ads SET title = :title, advertiser = :advertiser, " +
            "themeTags = :themes, notes = :notes WHERE id = :id"
    )
    suspend fun updateMetadata(id: Long, title: String, advertiser: String, themes: String, notes: String)

    @Query("SELECT * FROM recorded_ads WHERE timestamp < :cutoff AND isFavorite = 0")
    suspend fun olderThan(cutoff: Long): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads WHERE timestamp < :cutoff AND isFavorite = 0")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM recorded_ads WHERE timestamp < :cutoff AND isFavorite = 0")
    suspend fun countOlderThan(cutoff: Long): Int

    @Query("SELECT * FROM recorded_ads WHERE isFavorite = 0")
    suspend fun nonFavorites(): List<RecordedAdEntity>

    @Query("DELETE FROM recorded_ads WHERE isFavorite = 0")
    suspend fun deleteNonFavorites(): Int

    @Query("DELETE FROM recorded_ads")
    suspend fun deleteAll(): Int
}
