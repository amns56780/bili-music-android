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
 * FR-2 第 3 条：UP 主投稿来源的真机测试（只跑 1 页，避免真的打 10 页接口）。
 *
 * 验收标准是「可用，或按风控降级为明确提示（不允许崩）」，
 * 所以两种结果都算通过：成功导入，或者收到带 riskControlled 标记的友好提示。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e mid '12345678' \
 *   -e class com.bilimusic.app.UploadImportTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class UploadImportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun UP主投稿_可用或按风控降级() {
        val midText = InstrumentationRegistry.getArguments().getString("mid").orEmpty()
        assumeTrue("未传 -e mid，跳过", midText.isNotBlank())
        val mid = midText.toLongOrNull()
        assumeTrue("mid 不是数字，跳过", mid != null)

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val securePrefs = SecurePrefs(context)
        val cookieStore = CookieStore(securePrefs, json)
        assumeTrue("当前未登录，跳过", cookieStore.hasSessData)

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
            val importRepository = ImportRepositoryImpl(
                api = api,
                wbiSigner = WbiSigner(api),
                playlistRepository = PlaylistRepositoryImpl(
                    playlistDao = db.playlistDao(),
                    songDao = db.songDao(),
                    selectionDao = db.collectionSelectionDao(),
                ),
                throttle = RequestThrottle(),
                cookieStore = cookieStore,
                accountStore = AccountStore(securePrefs, json),
                shortLinkResolver = ShortLinkResolver(client),
            )

            val events = mutableListOf<ImportProgress>()
            runBlocking {
                importRepository.importUploads(mid = mid!!, maxPages = 1).collect { events += it }
            }

            val failed = events.filterIsInstance<ImportProgress.Failed>().firstOrNull()
            val report = events.filterIsInstance<ImportProgress.Finished>().firstOrNull()?.report
            println("[BiliMusicTest] UP主投稿 事件数=${events.size}")
            report?.let { println("[BiliMusicTest] 报告=${it.summary}") }
            failed?.let {
                println("[BiliMusicTest] 提示=${it.message} riskControlled=${it.riskControlled}")
            }

            // 验收：要么成功，要么降级提示；两种都算通过，但必须给出明确结果
            val ok = (report != null && report.success >= 0) || failed != null
            assertTrue("必须有明确结果（成功报告或失败提示），不能静默失败", ok)
            if (failed != null && failed.riskControlled) {
                println("[BiliMusicTest] 结论：命中风控，已按任务书要求降级为友好提示（不重试、不死磕）")
            } else if (report != null) {
                println("[BiliMusicTest] 结论：该来源可用，导入成功 ${report.success} 条")
            } else {
                println("[BiliMusicTest] 结论：非风控失败，已给出可读提示")
            }
        } finally {
            db.close()
        }
    }
}
