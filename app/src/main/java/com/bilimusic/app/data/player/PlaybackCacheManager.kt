package com.bilimusic.app.data.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-9 离线缓存底座。
 *
 * - 缓存目录放 `getExternalFilesDir("media")`（不需要任何存储权限）
 * - 容量上限可配（默认 2GB），用 [LeastRecentlyUsedCacheEvictor] 做 LRU 淘汰
 * - **缓存 key 不是 URL**：URL 只有 120 分钟有效期，所以 key 用 `bvid-cid-音质`，
 *   由 `MediaItem.customCacheKey` / `DownloadRequest.customCacheKey` 写进 `DataSpec.key`，
 *   播放与下载用同一个 key 才能互相命中
 */
@Singleton
class PlaybackCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackPrefs: PlaybackPrefs,
) {

    val databaseProvider: StandaloneDatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /** 缓存上限的最新值（由后台协程持续更新，供 SimpleCache 初始化时读取） */
    @Volatile
    private var currentLimitBytes: Long = PlaybackPrefs.DEFAULT_CACHE_LIMIT_BYTES

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            playbackPrefs.cacheLimitBytes.collectLatest { currentLimitBytes = it }
        }
    }

    /** 缓存目录（设置页「缓存管理」也用它算占用） */
    val cacheDir: File
        get() = File(context.getExternalFilesDir("media"), "audio_cache")

    val simpleCache: SimpleCache by lazy {
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(currentLimitBytes),
            databaseProvider,
        )
    }

    /** 当前缓存占用（字节） */
    fun usedBytes(): Long = runCatching { simpleCache.cacheSpace }.getOrDefault(0L)

    /** 设置页展示用：最多多少字节 */
    fun limitBytes(): Long = currentLimitBytes

    fun release() {
        runCatching { simpleCache.release() }
    }
}
