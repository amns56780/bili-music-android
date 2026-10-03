package com.bilimusic.app.data.repository

import com.bilimusic.app.data.local.dao.CollectionSelectionDao
import com.bilimusic.app.data.local.dao.SongDao
import com.bilimusic.app.data.local.entity.CollectionSelectionEntity
import com.bilimusic.app.data.local.entity.toDomain
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.Failure
import com.bilimusic.app.data.remote.FailureKind
import com.bilimusic.app.data.remote.RequestThrottle
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.domain.model.Episode
import com.bilimusic.app.domain.model.EpisodeGroup
import com.bilimusic.app.domain.model.SongDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-3 合集 / 分P 选集。
 *
 * - `collectionKey` 有两种：`season:{seasonId}`（合集）与 `pages:{bvid}`（多 P 视频）
 * - 首次进入默认**全选**，并把这份默认记忆写库；之后每次改动都持久化，
 *   所以切歌单、重启 App 后勾选状态依然保留
 * - 顺序按合集/分P的**原始顺序**（pageIndex），不是勾选顺序
 */
interface EpisodeRepository {

    /** 读取一个合集/分P组的全部集（含已保存的勾选状态） */
    suspend fun loadGroup(playlistId: Long, collectionKey: String): ApiResult<EpisodeGroup>

    /** 观察某一组的勾选状态（UI 实时刷新） */
    fun observeGroup(playlistId: Long, collectionKey: String): Flow<List<Episode>>

    suspend fun setSelected(playlistId: Long, collectionKey: String, epCid: Long, selected: Boolean)

    suspend fun setAllSelected(playlistId: Long, collectionKey: String, selected: Boolean)

    /** 反选：把当前勾选状态整体取反 */
    suspend fun invertSelection(playlistId: Long, collectionKey: String)

    /** 该组里被勾选的集（按原始顺序） */
    suspend fun selectedEpisodes(playlistId: Long, collectionKey: String): List<Episode>

    /** 把勾选的集加入指定歌单；返回 (新增数, 重复跳过数) */
    suspend fun addSelectedToPlaylist(
        targetPlaylistId: Long,
        playlistId: Long,
        collectionKey: String,
    ): ApiResult<AddSongsResult>
}

@Singleton
class EpisodeRepositoryImpl @Inject constructor(
    private val api: BiliApi,
    private val songDao: SongDao,
    private val selectionDao: CollectionSelectionDao,
    private val playlistRepository: PlaylistRepository,
    private val throttle: RequestThrottle,
) : EpisodeRepository {

    override fun observeGroup(playlistId: Long, collectionKey: String): Flow<List<Episode>> =
        selectionDao.observeGroup(playlistId, collectionKey).map { rows -> rows.map { it.toDomain() } }

    override suspend fun loadGroup(
        playlistId: Long,
        collectionKey: String,
    ): ApiResult<EpisodeGroup> {
        val sample = songDao.getByCollection(playlistId, collectionKey).firstOrNull()
            ?: return ApiResult.Error(
                Failure("这个合集在歌单里已经没有对应曲目了", null, FailureKind.NOT_FOUND),
            )

        throttle.await()
        val detail = when (val result = apiCall(maxAttempts = 2) {
            api.videoView(mapOf("bvid" to sample.bvid))
        }) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
        }

        val season = detail.ugcSeason
        val entries: List<EpisodeEntry>
        val groupTitle: String
        if (collectionKey.startsWith(KEY_SEASON) && season != null && !season.sections.isNullOrEmpty()) {
            val list = mutableListOf<EpisodeEntry>()
            var index = 0
            season.sections.orEmpty().forEach { section ->
                section.episodes.orEmpty().forEach { episode ->
                    index++
                    list += EpisodeEntry(
                        cid = episode.cid,
                        bvid = episode.arc?.bvid?.ifBlank { episode.bvid } ?: episode.bvid,
                        title = episode.arc?.title?.ifBlank { episode.title } ?: episode.title,
                        durationMs = (episode.arc?.duration ?: episode.duration).toLong() * 1000L,
                        pageIndex = index,
                    )
                }
            }
            entries = list
            groupTitle = season.title.ifBlank { detail.title }
        } else {
            entries = detail.pages.orEmpty().map { page ->
                EpisodeEntry(
                    cid = page.cid,
                    bvid = detail.bvid,
                    title = if (page.part.isBlank()) detail.title else page.part,
                    durationMs = page.duration.toLong() * 1000L,
                    pageIndex = page.page,
                )
            }
            groupTitle = detail.title
        }

        if (entries.isEmpty()) {
            return ApiResult.Error(Failure("没有读到分P或合集内容", null, FailureKind.NOT_FOUND))
        }

        // 合并已保存的勾选状态：有记录用记录，没记录默认全选
        val saved = selectionDao.getGroup(playlistId, collectionKey).associateBy { it.epCid }
        val rows = entries.mapIndexed { index, entry ->
            CollectionSelectionEntity(
                id = saved[entry.cid]?.id ?: 0L,
                playlistId = playlistId,
                collectionKey = collectionKey,
                epCid = entry.cid,
                epBvid = entry.bvid,
                epTitle = entry.title,
                durationMs = entry.durationMs,
                selected = saved[entry.cid]?.selected ?: true,
                pageIndex = entry.pageIndex,
                sortOrder = index,
            )
        }
        // 首次进入就把「默认全选」这份记忆落库
        selectionDao.upsertAll(rows)

        return ApiResult.Success(
            EpisodeGroup(
                collectionKey = collectionKey,
                title = groupTitle,
                episodes = rows.map { it.toDomain() },
                upperName = sample.upperName,
                coverUrl = sample.coverUrl,
            ),
        )
    }

    override suspend fun setSelected(
        playlistId: Long,
        collectionKey: String,
        epCid: Long,
        selected: Boolean,
    ) {
        val row = selectionDao.getGroup(playlistId, collectionKey).firstOrNull { it.epCid == epCid }
            ?: return
        selectionDao.setSelected(row.id, selected)
    }

    override suspend fun setAllSelected(
        playlistId: Long,
        collectionKey: String,
        selected: Boolean,
    ) {
        selectionDao.setAllSelected(playlistId, collectionKey, selected)
    }

    override suspend fun invertSelection(playlistId: Long, collectionKey: String) {
        val rows = selectionDao.getGroup(playlistId, collectionKey)
        val inverted = rows.map { it.copy(selected = !it.selected) }
        selectionDao.upsertAll(inverted)
    }

    override suspend fun selectedEpisodes(
        playlistId: Long,
        collectionKey: String,
    ): List<Episode> = selectionDao.getSelected(playlistId, collectionKey).map { it.toDomain() }

    override suspend fun addSelectedToPlaylist(
        targetPlaylistId: Long,
        playlistId: Long,
        collectionKey: String,
    ): ApiResult<AddSongsResult> {
        val selected = selectionDao.getSelected(playlistId, collectionKey)
        if (selected.isEmpty()) {
            return ApiResult.Error(Failure("请至少选择 1 集", null, FailureKind.UNKNOWN))
        }
        val sample = songDao.getByCollection(playlistId, collectionKey).firstOrNull()
        val drafts = selected.map { row ->
            SongDraft(
                bvid = row.epBvid,
                cid = row.epCid,
                title = row.epTitle,
                upperName = sample?.upperName.orEmpty().ifBlank { "未知UP主" },
                coverUrl = sample?.coverUrl,
                durationMs = row.durationMs,
                collectionKey = collectionKey,
                episodeCount = selected.size,
                pageIndex = row.pageIndex,
            )
        }
        return ApiResult.Success(playlistRepository.addSongs(targetPlaylistId, drafts))
    }

    private data class EpisodeEntry(
        val cid: Long,
        val bvid: String,
        val title: String,
        val durationMs: Long,
        val pageIndex: Int,
    )

    private companion object {
        const val KEY_SEASON = "season:"
    }
}
