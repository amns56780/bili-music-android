package com.bilimusic.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bilimusic.app.domain.model.Song

/**
 * 曲目表。同一歌单内按 (bvid, cid) 唯一，重复导入会被忽略（OnConflictStrategy.IGNORE）。
 * 删歌单级联删除曲目。
 */
@Entity(
    tableName = "song",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bvid", "cid", "playlistId"], unique = true),
        Index(value = ["playlistId"]),
        Index(value = ["collectionKey"]),
    ],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bvid: String,
    val cid: Long,
    val title: String,
    val upperName: String,
    val coverUrl: String?,
    val durationMs: Long,
    val playlistId: Long,
    /** 上一次成功播放时使用的音质 id（FR-10），null 表示还没播过 */
    val audioQualityId: Int? = null,
    /** 失效视频（attr != 0 / -404 / 62002 / 62004）标记，播放时跳过 */
    @ColumnInfo(defaultValue = "0")
    val isInvalid: Boolean = false,
    val addedAt: Long,
    val sortOrder: Int = 0,
    /** 合集/分P组 key：seasonId 或 bvid；单曲为 null */
    val collectionKey: String? = null,
    /** 该曲所属合集/分P的集数，用于歌单详情页「选集」入口 */
    @ColumnInfo(defaultValue = "0")
    val episodeCount: Int = 0,
    /** 在分P中的序号，从 1 开始 */
    @ColumnInfo(defaultValue = "1")
    val pageIndex: Int = 1,
    /**
     * 本地歌曲的文件 URI（如 `content://media/external/audio/media/123`）；
     * B 站曲目为 null。非空时播放链路直接播本地文件，不走取流与缓存。
     */
    val localUri: String? = null,
)

fun SongEntity.toDomain(): Song = Song(
    id = id,
    bvid = bvid,
    cid = cid,
    title = title,
    upperName = upperName,
    coverUrl = coverUrl,
    durationMs = durationMs,
    playlistId = playlistId,
    audioQualityId = audioQualityId,
    isInvalid = isInvalid,
    addedAt = addedAt,
    sortOrder = sortOrder,
    collectionKey = collectionKey,
    episodeCount = episodeCount,
    pageIndex = pageIndex,
    localUri = localUri,
)
