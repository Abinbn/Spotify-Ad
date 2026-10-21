package com.example.data

import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordedAdRepository(private val dao: RecordedAdDao) {
    val allAds: Flow<List<RecordedAdEntity>> = dao.getAllAds()

    fun getAdsSorted(sortByDate: Boolean, ascending: Boolean): Flow<List<RecordedAdEntity>> {
        return if (sortByDate) {
            if (ascending) dao.getAdsSortedByDateAsc() else dao.getAdsSortedByDateDesc()
        } else {
            if (ascending) dao.getAdsSortedByDurationAsc() else dao.getAdsSortedByDurationDesc()
        }
    }

    suspend fun getAdById(id: Long): RecordedAdEntity? = dao.getAdById(id)

    suspend fun insert(ad: RecordedAdEntity): Long = dao.insert(ad)

    suspend fun update(ad: RecordedAdEntity) = dao.update(ad)

    suspend fun delete(ad: RecordedAdEntity) {
        dao.delete(ad)
        deleteFilesForAd(ad.filePath)
    }

    suspend fun deleteById(id: Long) {
        val ad = dao.getAdById(id)
        if (ad != null) {
            deleteFilesForAd(ad.filePath)
        }
        dao.deleteById(id)
    }

    suspend fun toggleFavorite(id: Long, isFav: Boolean) = dao.updateFavorite(id, isFav)

    suspend fun updateMetadata(id: Long, newTitle: String, newAdvertiser: String, newThemes: String) =
        dao.updateMetadata(id, newTitle, newAdvertiser, newThemes)

    /**
     * Automated cleanup: deletes recordings older than 30 days unless marked as 'Favorite'.
     * Returns the number of files deleted and total bytes freed.
     */
    suspend fun cleanupOldRecordings(days: Int = 30): Pair<Int, Long> {
        val cutoffTimestamp = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        val oldAds = dao.getOldNonFavoriteAds(cutoffTimestamp)
        var freedBytes = 0L

        for (ad in oldAds) {
            freedBytes += deleteFilesForAd(ad.filePath)
        }

        val deletedCount = dao.deleteOldNonFavoriteAds(cutoffTimestamp)
        return Pair(deletedCount, freedBytes)
    }

    suspend fun countOldRecordings(days: Int = 30): Int {
        val cutoffTimestamp = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        return dao.countOldNonFavoriteAds(cutoffTimestamp)
    }

    /**
     * Completely purges all placeholder and sample demo files.
     */
    suspend fun purgeDemoData(): Pair<Int, Long> {
        val sampleAds = dao.getSampleAds()
        var freedBytes = 0L
        for (ad in sampleAds) {
            freedBytes += deleteFilesForAd(ad.filePath)
        }
        val deletedCount = dao.deleteSampleAds()
        return Pair(deletedCount, freedBytes)
    }

    suspend fun deleteAllNonFavorites(): Pair<Int, Long> {
        val ads = dao.getAllNonFavoriteAds()
        var freedBytes = 0L
        for (ad in ads) {
            freedBytes += deleteFilesForAd(ad.filePath)
        }
        val count = dao.deleteAllNonFavoriteAds()
        return Pair(count, freedBytes)
    }

    suspend fun deleteAll(): Pair<Int, Long> {
        val ads = dao.getAllAdsSnapshot()
        var freedBytes = 0L
        for (ad in ads) {
            freedBytes += deleteFilesForAd(ad.filePath)
        }
        val count = dao.deleteAllAds()
        return Pair(count, freedBytes)
    }

    private fun deleteFilesForAd(filePath: String): Long {
        var bytes = 0L
        try {
            val audioFile = File(filePath)
            if (audioFile.exists()) {
                bytes += audioFile.length()
                audioFile.delete()
            }
            val metaFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + "_metadata.json")
            if (metaFile.exists()) {
                bytes += metaFile.length()
                metaFile.delete()
            }
        } catch (_: Exception) {}
        return bytes
    }
}
