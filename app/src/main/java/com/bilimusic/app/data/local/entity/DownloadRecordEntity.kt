package com.bilimusic.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * FR-9 离线下载记录。cacheKey = "$bvid-$cid-$qualityId"，
 * 与 ExoPlayer 的 CacheKeyFactory / DownloadRequest 使用同一个 key。
 */
@Entity(
    tableName = "download_record",
    indices = [Index(value = ["cacheKey"], unique = true), Index(value = ["bvid", "cid"])],
)
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val cacheKey: String,
    val bvid: String,
    val cid: Long,
    val qualityId: Int,
    val title: String,
    val upperName: String,
    val coverUrl: String?,
    val sizeBytes: Long = 0L,
    /** DownloadState 枚举名：QUEUED / DOWNLOADING / COMPLETED / FAILED / REMOVED */
    val state: String,
    val progress: Float = 0f,
    val createdAt: Long,
    val updatedAt: Long,
    val failReason: String? = null,
    @ColumnInfo(defaultValue = "0")
    val lastAccessAt: Long = 0L,
)

/** 下载状态。存进 Room 时用 name，读取时用 valueOf 并做兜底。 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    REMOVED,
    ;

    companion object {
        fun fromNameOrNull(name: String?): DownloadState? =
            entries.firstOrNull { it.name == name }
    }
}
