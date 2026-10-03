package com.bilimusic.app.data.repository

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.bilimusic.app.data.local.dao.DownloadDao
import com.bilimusic.app.data.local.entity.DownloadRecordEntity
import com.bilimusic.app.data.local.entity.DownloadState
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import com.bilimusic.app.data.player.AudioUri
import com.bilimusic.app.data.player.PlaybackCacheManager
import com.bilimusic.app.data.player.PlayerDataSources
import com.bilimusic.app.di.ApplicationScope
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** 下载状态（UI 用：未下载 / 下载中 / 已下载 / 失败） */
enum class DownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadInfo(
    val cacheKey: String,
    val status: DownloadStatus,
    val progress: Float,
    val sizeBytes: Long,
)

/**
 * FR-9 离线缓存：用 Media3 的 DownloadManager + SimpleCache。
 *
 * 关键点（任务书 FR-9 的坑）：
 * - **缓存 key 用 `bvid-cid-音质`，绝不能用 URL**（URL 只有 120 分钟有效期）
 * - 播放时的 `MediaItem.customCacheKey` 与下载时的 `DownloadRequest.customCacheKey` 用同一个 key，
 *   这样「下载完的歌」播放时能直接命中缓存，断网也能播
 * - 并发上限 2，避免触发风控
 */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheManager: PlaybackCacheManager,
    private val playerDataSources: PlayerDataSources,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val playbackPrefs: PlaybackPrefs,
    private val json: Json,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    val downloads: Flow<List<DownloadRecordEntity>> = downloadDao.observeAll()

    private var managerInstance: DownloadManager? = null
    private var syncedOnce = false

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            finalException?.let { Log.w(TAG, "下载失败 ${download.request.id}: ${it.message}") }
            applicationScope.launch { upsertFromDownload(download, finalException) }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            applicationScope.launch {
                downloadDao.deleteByCacheKey(download.request.id)
            }
        }
    }

    /** 懒创建 DownloadManager（第一次用到时才建，避免拖慢启动） */
    private fun manager(): DownloadManager {
        managerInstance?.let { return it }
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cacheManager.simpleCache)
            .setUpstreamDataSourceFactory(playerDataSources.createUpstream(okHttpClient))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 线程池只有 2 个线程 → 下载并发上限就是 2（避免触发 B 站风控）
        val manager = DownloadManager(
            context,
            cacheManager.databaseProvider,
            cacheManager.simpleCache,
            dataSourceFactory,
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
        )
        manager.addListener(listener)
        manager.resumeDownloads()
        managerInstance = manager
        return manager
    }

    /** App 启动/进设置页时调用一次：把 Media3 下载索引里的状态同步回 Room */
    fun syncIfNeeded() {
        if (syncedOnce) return
        syncedOnce = true
        applicationScope.launch {
            runCatching {
                val cursor = manager().downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    upsertFromDownload(cursor.download, null)
                }
                cursor.close()
            }.onFailure { Log.w(TAG, "同步下载索引失败", it) }
        }
    }

    /** 下载一批曲目（单曲传 1 个即可） */
    suspend fun enqueueSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val option = AudioQualityOption.fromNameOrAuto(playbackPrefs.audioQualityOption.first())
        val qualityId = option.qualityId ?: 0
        val manager = manager()
        val now = System.currentTimeMillis()

        val requests = songs.map { song ->
            val cacheKey = cacheKeyOf(song, qualityId)
            DownloadRequest.Builder(cacheKey, AudioUri.build(song.bvid, song.cid))
                .setCustomCacheKey(cacheKey)
                .setData(
                    json.encodeToString(
                        DownloadPayload(song.title, song.upperName, song.coverUrl, qualityId),
                    ).toByteArray(),
                )
                .build()
        }
        requests.forEach { manager.addDownload(it) }

        requests.forEachIndexed { index, request ->
            val song = songs[index]
            downloadDao.upsert(
                DownloadRecordEntity(
                    cacheKey = request.id,
                    bvid = song.bvid,
                    cid = song.cid,
                    qualityId = qualityId,
                    title = song.title,
                    upperName = song.upperName,
                    coverUrl = song.coverUrl,
                    sizeBytes = 0L,
                    state = DownloadState.QUEUED.name,
                    progress = 0f,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        Log.i(TAG, "已加入下载队列：${requests.size} 首（音质 ${option.settingLabel}）")
    }

    /** 删除单个下载（同时删掉缓存内容与记录） */
    suspend fun removeDownload(cacheKey: String) {
        runCatching { manager().removeDownload(cacheKey) }
        runCatching { cacheManager.simpleCache.removeResource(cacheKey) }
        downloadDao.deleteByCacheKey(cacheKey)
    }

    /** 清空全部缓存 */
    suspend fun clearAll() {
        val all = downloadDao.getCompletedByLru() + downloadDao.getByState(DownloadState.DOWNLOADING.name)
        all.forEach { runCatching { manager().removeDownload(it.cacheKey) } }
        runCatching {
            // SimpleCache.getKeys() 在 Kotlin 里是属性 keys；拷一份再删，避免边遍历边改
            val cacheKeys = ArrayList(cacheManager.simpleCache.keys)
            var index = 0
            while (index < cacheKeys.size) {
                cacheManager.simpleCache.removeResource(cacheKeys[index])
                index++
            }
        }
        downloadDao.deleteAll()
        Log.i(TAG, "已清空缓存（${all.size} 条记录）")
    }

    /** 某个 key 的下载状态（歌单列表项显示用） */
    fun statusOf(cacheKey: String, records: List<DownloadRecordEntity>): DownloadInfo {
        val record = records.firstOrNull { it.cacheKey == cacheKey }
            ?: return DownloadInfo(cacheKey, DownloadStatus.NOT_DOWNLOADED, 0f, 0L)
        return DownloadInfo(
            cacheKey = cacheKey,
            status = when (DownloadState.fromNameOrNull(record.state)) {
                DownloadState.COMPLETED -> DownloadStatus.COMPLETED
                DownloadState.DOWNLOADING -> DownloadStatus.DOWNLOADING
                DownloadState.QUEUED -> DownloadStatus.QUEUED
                DownloadState.FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.NOT_DOWNLOADED
            },
            progress = record.progress,
            sizeBytes = record.sizeBytes,
        )
    }

    /** 计算某首歌的缓存 key（与播放时 MediaItem.customCacheKey 完全一致） */
    fun cacheKeyOf(song: Song, qualityId: Int): String = "${song.bvid}-${song.cid}-$qualityId"

    suspend fun currentQualityId(): Int =
        AudioQualityOption.fromNameOrAuto(playbackPrefs.audioQualityOption.first()).qualityId ?: 0

    // ---------------- 内部 ----------------

    private suspend fun upsertFromDownload(download: Download, error: Exception?) {
        val payload = runCatching {
            download.request.data?.let { json.decodeFromString<DownloadPayload>(String(it)) }
        }.getOrNull() ?: return

        val state = when (download.state) {
            Download.STATE_COMPLETED -> DownloadState.COMPLETED
            Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
            Download.STATE_QUEUED -> DownloadState.QUEUED
            Download.STATE_FAILED -> DownloadState.FAILED
            else -> DownloadState.REMOVED
        }
        if (state == DownloadState.REMOVED) {
            downloadDao.deleteByCacheKey(download.request.id)
            return
        }

        val existing = downloadDao.getByCacheKey(download.request.id)
        downloadDao.upsert(
            DownloadRecordEntity(
                id = existing?.id ?: 0L,
                cacheKey = download.request.id,
                bvid = existing?.bvid.orEmpty().ifBlank { bvidOf(download.request.id) },
                cid = existing?.cid ?: cidOf(download.request.id),
                qualityId = payload.qualityId,
                title = payload.title,
                upperName = payload.upper,
                coverUrl = payload.cover,
                sizeBytes = download.bytesDownloaded,
                state = state.name,
                progress = download.percentDownloaded / 100f,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                failReason = error?.message,
                lastAccessAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun bvidOf(cacheKey: String): String = cacheKey.substringBefore('-')

    private fun cidOf(cacheKey: String): Long =
        cacheKey.split('-').getOrNull(1)?.toLongOrNull() ?: 0L

    @Serializable
    private data class DownloadPayload(
        val title: String,
        val upper: String,
        val cover: String? = null,
        val qualityId: Int = 0,
    )

    private companion object {
        const val TAG = "DownloadRepository"

        /** 下载并发上限（线程池大小 = 并发数） */
        const val MAX_PARALLEL_DOWNLOADS = 2
    }
}
