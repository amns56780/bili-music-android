package com.bilimusic.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.PlaylistSourceType

/**
 * 歌单表。sourceType 存枚举名（由 Converters 转换）。
 */
@Entity(
    tableName = "playlist",
    indices = [Index(value = ["sourceType", "sourceId"])],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val coverUrl: String?,
    val sourceType: PlaylistSourceType,
    val sourceId: String?,
    val createdAt: Long,
    val sortOrder: Int = 0,
    /** 最近一次导入时间，0 表示从未导入（手动歌单） */
    @ColumnInfo(defaultValue = "0")
    val lastImportAt: Long = 0L,
)

fun PlaylistEntity.toDomain(songCount: Int = 0, totalDurationMs: Long = 0L): Playlist = Playlist(
    id = id,
    title = title,
    coverUrl = coverUrl,
    sourceType = sourceType,
    sourceId = sourceId,
    createdAt = createdAt,
    sortOrder = sortOrder,
    songCount = songCount,
    totalDurationMs = totalDurationMs,
)
