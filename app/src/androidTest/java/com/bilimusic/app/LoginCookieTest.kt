package com.bilimusic.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.local.prefs.AccountStore
import com.bilimusic.app.data.local.prefs.SecurePrefs
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.BiliCookieJar
import com.bilimusic.app.data.remote.BiliHeaderInterceptor
import com.bilimusic.app.data.remote.CookieStore
import com.bilimusic.app.data.remote.WbiSigner
import com.bilimusic.app.data.repository.AuthRepositoryImpl
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
 * 真机插桩测试：验证 FR-1 的「手动粘贴 Cookie」这条保底路径。
 *
 * 之所以用插桩测试而不是手点 UI：手机上的中文输入法会把 adb 注入的字符转成中文标点，
 * 没法可靠地把一长串 Cookie 打进输入框。这里直接调用 **UI 按钮背后同一个方法**
 * `AuthRepositoryImpl.loginWithRawCookie()`，覆盖：解析 → 落加密存储 → 调 nav 校验 → 保存账号。
 *
 * 运行方式（cookie 通过 -e 参数传入，不写进源码）：
 * ```
 * adb shell am instrument -w -e cookie 'SESSDATA=...' \
 *   -e class com.bilimusic.app.LoginCookieTest#manualCookieLoginWorks \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 没有传 `-e cookie` 时测试会被跳过（Assume），所以放在仓库里也不会影响其他人。
 */
@RunWith(AndroidJUnit4::class)
class LoginCookieTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun cookieArgument(): String =
        InstrumentationRegistry.getArguments().getString("cookie").orEmpty()

    /** 把 Cookie 放进系统剪贴板，便于验证「输入框 + Ctrl+V + 用 Cookie 登录」的真实 UI 路径 */
    @Test
    fun putCookieOnClipboard() {
        val cookie = cookieArgument()
        assumeTrue("未传 -e cookie，跳过", cookie.isNotBlank())

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        instrumentation.runOnMainSync {
            clipboard.setPrimaryClip(ClipData.newPlainText("bilimusic_sessdata", cookie))
        }
        assertTrue(clipboard.hasPrimaryClip())
    }

    /** 真实走一遍手动 Cookie 登录（UI 按钮调用的就是这个方法） */
    @Test
    fun manualCookieLoginWorks() {
        val cookie = cookieArgument()
        assumeTrue("未传 -e cookie，跳过", cookie.isNotBlank())

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val securePrefs = SecurePrefs(context)
        val cookieStore = CookieStore(securePrefs, json)
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

        val repository = AuthRepositoryImpl(
            api = api,
            cookieStore = cookieStore,
            accountStore = accountStore,
            wbiSigner = WbiSigner(api),
        )

        val result = runBlocking { repository.loginWithRawCookie(cookie) }

        when (result) {
            is ApiResult.Success -> {
                val account = result.data
                println("[BiliMusicTest] 登录成功：name=${account.name} mid=${account.mid} vip=${account.isVip}")
                assertTrue("mid 应该 > 0", account.mid > 0L)
                assertTrue("CookieStore 应该已保存 SESSDATA", cookieStore.hasSessData)
                assertTrue("AccountStore 应该已保存账号快照", accountStore.load() != null)
            }

            is ApiResult.Error -> {
                throw AssertionError("手动 Cookie 登录失败：${result.failure.userMessage} (code=${result.failure.code})")
            }
        }
    }
}
