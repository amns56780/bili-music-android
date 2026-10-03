package com.bilimusic.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.local.AppDatabase
import com.bilimusic.app.data.local.prefs.AccountStore
import com.bilimusic.app.data.local.prefs.SecurePrefs
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.BiliCookieJar
import com.bilimusic.app.data.remote.BiliHeaderInterceptor
import com.bilimusic.app.data.remote.CookieStore
import com.bilimusic.app.data.remote.RequestThrottle
import com.bilimusic.app.data.remote.ShortLinkResolver
import com.bilimusic.app.data.remote.WbiSigner
import com.bilimusic.app.data.repository.ImportRepositoryImpl
import com.bilimusic.app.data.repository.PlaylistRepositoryImpl
import com.bilimusic.app.domain.model.ImportProgress
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * FR-2 / FR-3：合集 / 分P 导入的真机测试。
 *
 * 直接用**内存数据库**跑一遍真实导入，不污染手机上的歌单数据；覆盖：
 * 解析输入 → 取视频详情 → 识别 ugc_season / pages → 按原始顺序落库 → 生成报告。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e bvid 'BV1xx411c7mD' \
 *   -e class com.bilimusic.app.SeasonImportTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SeasonImportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun 按BV导入合集或分P() {
        val bvid = InstrumentationRegistry.getArguments().getString("bvid").orEmpty()
        println("[BiliMusicTest] args=$bvid keys=${InstrumentationRegistry.getArguments().keySet()}")
        assumeTrue("未传 -e bvid，跳过", bvid.isNotBlank())

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val securePrefs = SecurePrefs(context)
        val cookieStore = CookieStore(securePrefs, json)
        println("[BiliMusicTest] hasSessData=${cookieStore.hasSessData} cookies=${cookieStore.pairs().size}")
        assumeTrue("当前未登录（没有 SESSDATA），跳过", cookieStore.hasSessData)

        val accountStore = AccountStore(securePrefs, json)
        val client = OkHttpClient.Builder()
            .cookieJar(BiliCookieJar(cookieStore))
            .addInterceptor(BiliHeaderInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.bilibili.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BiliApi::class.java)

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val playlistRepository = PlaylistRepositoryImpl(
                playlistDao = db.playlistDao(),
                songDao = db.songDao(),
                selectionDao = db.collectionSelectionDao(),
            )
            val importRepository = ImportRepositoryImpl(
                api = api,
                wbiSigner = WbiSigner(api),
                playlistRepository = playlistRepository,
                throttle = RequestThrottle(),
                cookieStore = cookieStore,
                accountStore = accountStore,
                shortLinkResolver = ShortLinkResolver(client),
            )

            val events = mutableListOf<ImportProgress>()
            runBlocking {
                importRepository.importByVideoInput(bvid).collect { events += it }
            }

            val failed = events.filterIsInstance<ImportProgress.Failed>().firstOrNull()
            val finished = events.filterIsInstance<ImportProgress.Finished>().firstOrNull()
            val report = finished?.report

            println("[BiliMusicTest] 事件数=${events.size}")
            events.filterIsInstance<ImportProgress.Working>().take(3).forEach {
                println("[BiliMusicTest] 进度 ${it.current}/${it.total} ${it.message}")
            }
            println("[BiliMusicTest] 报告=${report?.summary ?: "无"}（歌单：${report?.playlistTitle}）")
            failed?.let { println("[BiliMusicTest] 失败原因=${it.message}") }

            assertTrue("不应该导入失败：${failed?.message}", failed == null)
            assertTrue("应该拿到导入报告", report != null)
            assertTrue("至少导入 1 条", (report?.success ?: 0) > 0)

            // 落库结果：曲目数、顺序、集合信息
            val playlistId = report!!.playlistId
            val songs = runBlocking { playlistRepository.getSongs(playlistId) }
            println("[BiliMusicTest] 落库曲目数=${songs.size}")
            songs.take(3).forEach { song ->
                println(
                    "[BiliMusicTest] 曲目 sortOrder=${song.sortOrder} page=${song.pageIndex} " +
                        "集数=${song.episodeCount} collection=${song.collectionKey} 标题=${song.title}",
                )
            }
            assertTrue("落库曲目数应等于成功条数", songs.size == report.success)
            assertTrue("每条都应该有 cid", songs.all { it.cid > 0L })
            assertTrue("每条都应该有时长", songs.all { it.durationMs > 0L })
        } finally {
            db.close()
        }
    }
}
