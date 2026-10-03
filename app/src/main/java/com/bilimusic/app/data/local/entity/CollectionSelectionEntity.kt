package com.bilimusic.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bilimusic.app.domain.model.Episode

/**
 * FR-3 选集勾选记忆表。
 * 同一歌单 + 同一合集 + 同一 cid 唯一；删歌单级联删除。
 */
@Entity(
    tableName = "collection_selection",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "collectionKey", "epCid"], unique = true),
        Index(value = ["playlistId"]),
    ],
)
data class CollectionSelectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val playlistId: Long,
    /** seasonId 或 bvid */
    val collectionKey: String,
    val epCid: Long,
    val epBvid: String,
    val epTitle: String,
    val durationMs: Long,
    @ColumnInfo(defaultValue = "1")
    val selected: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val pageIndex: Int = 1,
    /** 合集/分P的顺序，保证队列按原始相对顺序 */
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
)

fun CollectionSelectionEntity.toDomain(): Episode = Episode(
    id = id,
    playlistId = playlistId,
    collectionKey = collectionKey,
    epCid = epCid,
    epBvid = epBvid,
    epTitle = epTitle,
    durationMs = durationMs,
    selected = selected,
    pageIndex = pageIndex,
)
