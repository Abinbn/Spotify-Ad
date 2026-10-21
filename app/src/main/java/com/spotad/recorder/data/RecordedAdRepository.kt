package com.spotad.recorder.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Single source of truth for the recording library. Every delete path removes the audio file as
 * well as the row, so the database can never point at missing audio.
 */
class RecordedAdRepository(private val dao: RecordedAdDao) {

    val ads: Flow<List<RecordedAdEntity>> = dao.observeAll()

    suspend fun getById(id: Long): RecordedAdEntity? = dao.getById(id)

    suspend fun insert(ad: RecordedAdEntity): Long = dao.insert(ad)

    suspend fun update(ad: RecordedAdEntity) = dao.update(ad)

    suspend fun setFavorite(id: Long, isFavorite: Boolean) = dao.setFavorite(id, isFavorite)

    suspend fun updateMetadata(
        id: Long,
        title: String,
        advertiser: String,
        themes: String,
        notes: String
    ) = dao.updateMetadata(id, title, advertiser, themes, notes)

    suspend fun delete(ad: RecordedAdEntity) {
        deleteFile(ad.filePath)
        dao.delete(ad)
    }

    suspend fun deleteById(id: Long) {
        val ad = dao.getById(id) ?: return
        deleteFile(ad.filePath)
        dao.delete(ad)
    }

    /** Returns how many rows were removed. */
    suspend fun deleteOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
        val expired = dao.olderThan(cutoff)
        expired.forEach { deleteFile(it.filePath) }
        return dao.deleteOlderThan(cutoff)
    }

    suspend fun countOlderThan(days: Int): Int {
        if (days <= 0) return 0
        return dao.countOlderThan(System.currentTimeMillis() - days * DAY_MILLIS)
    }

    suspend fun deleteNonFavorites(): Int {
        dao.nonFavorites().forEach { deleteFile(it.filePath) }
        return dao.deleteNonFavorites()
    }

    suspend fun deleteEverything(): Int {
        dao.snapshot().forEach { deleteFile(it.filePath) }
        return dao.deleteAll()
    }

    /** Frees orphaned audio files that no longer have a database row. */
    suspend fun pruneOrphanFiles(adsDirectory: File): Int {
        val known = dao.snapshot().map { it.filePath }.toSet()
        val files = adsDirectory.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.absolutePath !in known) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private fun deleteFile(path: String) {
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // A missing or already-deleted file must never block removing the row.
        }
    }

    companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
