package com.bilimusic.app.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.bilimusic.app.data.remote.BiliHeaderInterceptor
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器要用的音频虚拟 URI：`bilimusic://audio/{bvid}/{cid}`。
 *
 * 之所以不直接把真实取流地址塞进 MediaItem：
 * 1. 取流地址只有 120 分钟有效期，队列里第 50 首可能几小时后才播 —— 必须先解析再打开
 * 2. 真实地址在播放时才拿，队列构建零网络请求（导入 99 首也不需要 99 次取流）
 */
object AudioUri {
    private const val SCHEME = "bilimusic"
    private const val HOST = "audio"

    fun build(bvid: String, cid: Long): Uri = Uri.parse("$SCHEME://$HOST/$bvid/$cid")

    fun parse(uri: Uri): Ref? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val cid = segments[1].toLongOrNull() ?: return null
        return Ref(bvid = segments[0], cid = cid)
    }

    data class Ref(val bvid: String, val cid: Long)
}

/**
 * 播放时把虚拟 URI 解析成真实取流地址（[ResolvingDataSource.Resolver]）。
 * 每次 open 都会走一遍，所以地址永远是新鲜的。
 */
class BiliAudioResolver(
    private val streamUrlRepository: StreamUrlRepository,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val ref = AudioUri.parse(dataSpec.uri) ?: return dataSpec
        val stream = runBlocking { streamUrlRepository.resolve(ref.bvid, ref.cid) }
            ?: throw IOException("取流失败：拿不到音频地址（bvid=${ref.bvid}）")
        return dataSpec.withUri(Uri.parse(stream.url))
    }
}

/**
 * 播放地址过期（403）时清掉缓存，让下一次重试重新取流。
 * 任务书要求「播放器报 403 时也要自动重新取流并重试一次」。
 */
class ExpiredUrlRetryDataSource private constructor(
    private val upstream: DataSource,
    private val onForbidden: (String) -> Unit,
) : DataSource {

    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (error: HttpDataSource.InvalidResponseCodeException) {
        if (error.responseCode == 403 || error.responseCode == 401) {
            onForbidden(dataSpec.uri.toString())
        }
        throw error
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
        upstream.read(buffer, offset, readLength)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()

    class Factory(
        private val upstream: DataSource.Factory,
        private val onForbidden: (String) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ExpiredUrlRetryDataSource(upstream.createDataSource(), onForbidden)
    }
}

/**
 * 按 URI 选择数据源：
 * - **本地文件**（content:// / file://）走**不缓存**的链 —— 文件本来就在本地，
 *   再缓存一份纯属白占用户空间
 * - 其余（虚拟地址 bilimusic:// 与解析后的 http(s)）走**带缓存**的链（FR-9 离线播放）
 *
 * 为什么不用 `CacheKeyFactory` 返回 null 来跳过缓存：Media3 1.5 的 CacheDataSource
 * 拿到 null key 会直接抛 `Source error`（实测踩过），所以改成在数据源这一层分流。
 */
class SchemeRoutingDataSource private constructor(
    private val cachedFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
) : DataSource {

    private var active: DataSource? = null
    private val pendingListeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val isLocal = scheme != null && scheme != "http" && scheme != "https" && scheme != "bilimusic"
        val delegate = (if (isLocal) directFactory else cachedFactory).createDataSource()
        pendingListeners.forEach { delegate.addTransferListener(it) }
        active = delegate
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
        active?.read(buffer, offset, readLength) ?: -1

    override fun addTransferListener(transferListener: TransferListener) {
        // ExoPlayer 会在 open 之前调用；具体用哪条链要到 open 才知道，所以先攒着
        pendingListeners += transferListener
    }

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(
        private val cachedFactory: DataSource.Factory,
        private val directFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(cachedFactory, directFactory)
    }
}

/**
 * 403 / 401（地址过期）允许快速重试，让上层有机会重新取流；
 * 404 / 410 属于永久失效，直接放弃。
 */
class BiliLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(3) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is HttpDataSource.InvalidResponseCodeException) {
            return when (error.responseCode) {
                401, 403 -> 200L
                404, 410 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/**
 * 组装播放器用的数据源：
 * OkHttp（带 Referer/UA，防 403） → 虚拟 URI 解析 → 403 失效重取，外面再套一层离线缓存。
 *
 * 缓存放在最外层：命中缓存的曲目**根本不会去解析 URL**，所以断网也能播已下载的歌（FR-9）。
 */
@Singleton
class PlayerDataSources @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val streamUrlRepository: StreamUrlRepository,
    private val cacheManager: PlaybackCacheManager,
) {

    /** 不带缓存的链（下载时由 DownloadManager 自己套一层 CacheDataSource） */
    fun createUpstream(okHttpClient: okhttp3.OkHttpClient): DataSource.Factory {
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(BiliHeaderInterceptor.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to BiliHeaderInterceptor.REFERER,
                    "Origin" to BiliHeaderInterceptor.ORIGIN,
                ),
            )
        // 外面套一层 DefaultDataSource：http(s) 继续走 OkHttp（带 Referer 防 403），
        // content:// / file:// 这些本地 URI 由它自己处理 —— 本地播放器功能靠的就是这一层。
        val upstream: DataSource.Factory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val resolving = ResolvingDataSource.Factory(
            upstream,
            BiliAudioResolver(streamUrlRepository),
        )
        return ExpiredUrlRetryDataSource.Factory(resolving) { url ->
            streamUrlRepository.invalidateByUrl(url)
        }
    }

    /** 播放用：线上曲目包一层 CacheDataSource（离线可播）；本地文件走不缓存的直连链 */
    fun create(okHttpClient: okhttp3.OkHttpClient): DataSource.Factory {
        val direct = createUpstream(okHttpClient)
        val cached: DataSource.Factory =
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cacheManager.simpleCache)
                .setUpstreamDataSourceFactory(direct)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return SchemeRoutingDataSource.Factory(cachedFactory = cached, directFactory = direct)
    }
}
