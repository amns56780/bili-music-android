package com.bilimusic.app.data.repository

import com.bilimusic.app.data.local.dao.CollectionSelectionDao
import com.bilimusic.app.data.local.dao.PlaylistDao
import com.bilimusic.app.data.local.dao.SongDao
import com.bilimusic.app.data.local.entity.PlaylistEntity
import com.bilimusic.app.data.local.entity.SongEntity
import com.bilimusic.app.data.local.entity.toDomain
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.PlaylistSourceType
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.domain.model.SongDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌单 / 曲目的唯一数据入口（UI 层只跟 ViewModel 交互，ViewModel 只跟 Repository 交互）。
 */
interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylist(playlistId: Long): Flow<Playlist?>
    fun observeSongs(playlistId: Long): Flow<List<Song>>

    suspend fun createPlaylist(title: String): Long

    /** 导入用：创建带来源信息的歌单 */
    suspend fun createPlaylistForSource(
        title: String,
        sourceType: PlaylistSourceType,
        sourceId: String,
        coverUrl: String? = null,
    ): Long
    suspend fun renamePlaylist(playlistId: Long, title: String)
    suspend fun deletePlaylist(playlistId: Long)

    suspend fun getPlaylist(playlistId: Long): Playlist?
    suspend fun getSongs(playlistId: Long): List<Song>
    suspend fun getSong(songId: Long): Song?

    /** 新增曲目；返回 (新增数, 因去重跳过数) */
    suspend fun addSongs(playlistId: Long, drafts: List<SongDraft>): AddSongsResult

    suspend fun removeSong(songId: Long)
    suspend fun clearSongs(playlistId: Long)
    suspend fun playlistTitleExists(title: String): Boolean
    suspend fun songCount(playlistId: Long): Int

    /** 按来源查找歌单（重复导入同一个收藏夹/合集时复用） */
    suspend fun findPlaylistBySource(type: PlaylistSourceType, sourceId: String): Playlist?

    suspend fun updatePlaylistCover(playlistId: Long, coverUrl: String?)

    suspend fun markImported(playlistId: Long, at: Long = System.currentTimeMillis())

    suspend fun updatePlayedQuality(songId: Long, qualityId: Int?)
    suspend fun markSongInvalid(songId: Long, invalid: Boolean)
    suspend fun upsertEpisodeSelections(
        playlistId: Long,
        collectionKey: String,
        episodes: List<EpisodeDraft>,
    )
}

data class AddSongsResult(
    val inserted: Int,
    val skippedAsDuplicate: Int,
)

/** 写入选集记录时用的入参 */
data class EpisodeDraft(
    val epCid: Long,
    val epBvid: String,
    val epTitle: String,
    val durationMs: Long,
    val pageIndex: Int,
    val selected: Boolean = true,
)

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val selectionDao: CollectionSelectionDao,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAllWithStats().map { rows ->
            rows.map { it.playlist.toDomain(songCount = it.songCount, totalDurationMs = it.totalDurationMs) }
        }

    override fun observePlaylist(playlistId: Long): Flow<Playlist?> =
        playlistDao.observeById(playlistId).map { it?.toDomain() }

    override fun observeSongs(playlistId: Long): Flow<List<Song>> =
        songDao.observeByPlaylist(playlistId).map { list -> list.map { it.toDomain() } }

    override suspend fun createPlaylist(title: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insert(
            PlaylistEntity(
                title = title.trim(),
                coverUrl = null,
                sourceType = PlaylistSourceType.MANUAL,
                sourceId = null,
                createdAt = now,
                sortOrder = playlistDao.maxSortOrder() + 1,
            ),
        )
    }

    override suspend fun renamePlaylist(playlistId: Long, title: String) {
        playlistDao.rename(playlistId, title.trim())
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        // 外键 CASCADE 会一并删除曲目与选集记录
        playlistDao.deleteById(playlistId)
    }

    override suspend fun getPlaylist(playlistId: Long): Playlist? =
        playlistDao.getById(playlistId)?.toDomain()

    override suspend fun createPlaylistForSource(
        title: String,
        sourceType: PlaylistSourceType,
        sourceId: String,
        coverUrl: String?,
    ): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insert(
            PlaylistEntity(
                title = title.trim().ifBlank { sourceType.displayName },
                coverUrl = coverUrl,
                sourceType = sourceType,
                sourceId = sourceId,
                createdAt = now,
                sortOrder = playlistDao.maxSortOrder() + 1,
                lastImportAt = now,
            ),
        )
    }

    override suspend fun getSongs(playlistId: Long): List<Song> =
        songDao.getByPlaylist(playlistId).map { it.toDomain() }

    override suspend fun getSong(songId: Long): Song? = songDao.getById(songId)?.toDomain()

    override suspend fun addSongs(playlistId: Long, drafts: List<SongDraft>): AddSongsResult {
        if (drafts.isEmpty()) return AddSongsResult(inserted = 0, skippedAsDuplicate = 0)
        var sortOrder = songDao.maxSortOrder(playlistId) + 1
        val now = System.currentTimeMillis()
        var inserted = 0
        var skipped = 0
        drafts.forEach { draft ->
            val rowId = songDao.insertIgnore(
                SongEntity(
                    bvid = draft.bvid,
                    cid = draft.cid,
                    title = draft.title,
                    upperName = draft.upperName,
                    coverUrl = draft.coverUrl,
                    durationMs = draft.durationMs,
                    playlistId = playlistId,
                    audioQualityId = draft.audioQualityId,
                    isInvalid = draft.isInvalid,
                    addedAt = now,
                    sortOrder = sortOrder,
                    collectionKey = draft.collectionKey,
                    episodeCount = draft.episodeCount,
                    pageIndex = draft.pageIndex,
                ),
            )
            if (rowId == -1L) {
                skipped++
            } else {
                inserted++
                sortOrder++
            }
        }
        return AddSongsResult(inserted = inserted, skippedAsDuplicate = skipped)
    }

    override suspend fun removeSong(songId: Long) {
        val song = songDao.getById(songId) ?: return
        songDao.deleteById(songId)
        // 同步清掉该集的勾选记录，避免下次进入选集页残留已移除的集
        selectionDao.deleteEpisode(song.playlistId, song.cid)
    }

    override suspend fun clearSongs(playlistId: Long) {
        songDao.deleteByPlaylist(playlistId)
    }

    override suspend fun playlistTitleExists(title: String): Boolean =
        playlistDao.getByTitle(title.trim()) != null

    override suspend fun songCount(playlistId: Long): Int = songDao.countInPlaylist(playlistId)

    override suspend fun findPlaylistBySource(
        type: PlaylistSourceType,
        sourceId: String,
    ): Playlist? = playlistDao.findBySource(type, sourceId)?.toDomain()

    override suspend fun updatePlaylistCover(playlistId: Long, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        playlistDao.updateCover(playlistId, coverUrl)
    }

    override suspend fun markImported(playlistId: Long, at: Long) {
        playlistDao.updateLastImportAt(playlistId, at)
    }

    override suspend fun updatePlayedQuality(songId: Long, qualityId: Int?) {
        songDao.updateQuality(songId, qualityId)
    }

    override suspend fun markSongInvalid(songId: Long, invalid: Boolean) {
        songDao.markInvalid(songId, invalid)
    }

    override suspend fun upsertEpisodeSelections(
        playlistId: Long,
        collectionKey: String,
        episodes: List<EpisodeDraft>,
    ) {
        val existing = selectionDao.getGroup(playlistId, collectionKey).associateBy { it.epCid }
        val rows = episodes.mapIndexed { index, draft ->
            com.bilimusic.app.data.local.entity.CollectionSelectionEntity(
                // 已存在则保留原 id，靠唯一索引 REPLACE 覆盖，勾选状态持久记忆
                id = existing[draft.epCid]?.id ?: 0L,
                playlistId = playlistId,
                collectionKey = collectionKey,
                epCid = draft.epCid,
                epBvid = draft.epBvid,
                epTitle = draft.epTitle,
                durationMs = draft.durationMs,
                selected = existing[draft.epCid]?.selected ?: draft.selected,
                pageIndex = draft.pageIndex,
                sortOrder = index,
            )
        }
        selectionDao.upsertAll(rows)
    }
}
