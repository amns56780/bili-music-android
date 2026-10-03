package com.bilimusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bilimusic.app.data.local.entity.DownloadRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_record ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_record WHERE state = 'COMPLETED' ORDER BY lastAccessAt ASC")
    suspend fun getCompletedByLru(): List<DownloadRecordEntity>

    @Query("SELECT * FROM download_record WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByCacheKey(cacheKey: String): DownloadRecordEntity?

    @Query("SELECT * FROM download_record WHERE bvid = :bvid AND cid = :cid ORDER BY qualityId DESC")
    suspend fun getBySong(bvid: String, cid: Long): List<DownloadRecordEntity>

    @Query("SELECT * FROM download_record WHERE state = :state")
    suspend fun getByState(state: String): List<DownloadRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecordEntity)

    @Query("UPDATE download_record SET state = :state, progress = :progress, sizeBytes = :sizeBytes, updatedAt = :updatedAt WHERE cacheKey = :cacheKey")
    suspend fun updateProgress(
        cacheKey: String,
        state: String,
        progress: Float,
        sizeBytes: Long,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_record WHERE cacheKey = :cacheKey")
    suspend fun deleteByCacheKey(cacheKey: String)

    @Query("DELETE FROM download_record")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM download_record WHERE state = 'COMPLETED'")
    suspend fun totalCompletedBytes(): Long

    @Query("SELECT COUNT(*) FROM download_record WHERE state = 'COMPLETED'")
    suspend fun completedCount(): Int
}
