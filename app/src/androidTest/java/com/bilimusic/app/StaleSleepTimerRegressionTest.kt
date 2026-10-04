package com.bilimusic.app

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.player.PlaybackService
import com.bilimusic.app.widget.PlayerWidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回归测试：**「随机播放听歌，一首播完就莫名停下」**。
 *
 * 根因：`waitingForTrackEnd`（定时关闭的「等本曲播完再停」标志）会持久化，
 * 进程如果在等待期间被杀，这个标志就留在 DataStore 里，之后用户正常听歌时，
 * 只要一首歌自然播完（AUTO 切换）就会触发定时停止。
 *
 * 这里守两条：
 *  1. 服务重建时**必须忽略并清除**这个遗留标志
 *  2. 用户主动开始新播放（playSongs）时也会清掉它
 *  3. 端到端：把当前曲拖到结尾让它自然播完，**必须继续播下一首**（修复前会停）
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.StaleSleepTimerRegressionTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StaleSleepTimerRegressionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val entryPoint: PlayerWidgetEntryPoint
        get() = EntryPointAccessors.fromApplication(context, PlayerWidgetEntryPoint::class.java)

    @Test
    fun 遗留的等待标志在服务重建时会被清掉() = runBlocking {
        val sleepTimer = entryPoint.sleepTimer()
        val prefs = entryPoint.playbackPrefs()

        // 1) 制造「上次进程被杀时留下的等待状态」。
        //    必须在主线程上「设置 + 校验」一次做完：否则正在播放时，播放回调可能抢先把它清掉，
        //    测试就变成偶发失败了（踩过）。
        instrumentation.runOnMainSync {
            sleepTimer.forceWaitingForTrackEndForTest()
            assertTrue(
                "测试前置：应当处于等待本曲播完状态",
                sleepTimer.state.value.waitingForTrackEnd,
            )
        }

        // 2) 模拟服务重建 → 恢复逻辑必须忽略并清除它
        sleepTimer.restoreFromPrefs()
        assertTrue(
            "服务重建后不应再处于「等本曲播完」状态",
            waitUntil(5_000) { !sleepTimer.state.value.waitingForTrackEnd },
        )
        assertFalse(
            "DataStore 里的遗留标志也必须被清掉",
            withTimeout(5_000) { prefs.sleepTimerWaitingForTrackEnd.first() },
        )
    }

    @Test
    fun 用户主动播放会清掉遗留标志() = runBlocking {
        val sleepTimer = entryPoint.sleepTimer()
        val connection = entryPoint.playbackConnection()
        val repository = entryPoint.playlistRepository()

        instrumentation.runOnMainSync {
            sleepTimer.forceWaitingForTrackEndForTest()
            assertTrue(sleepTimer.state.value.waitingForTrackEnd)
        }

        val playlistId = withTimeout(20_000) {
            repository.observePlaylists().first().firstOrNull()?.id
        } ?: error("设备上没有歌单")
        val songs = withTimeout(20_000) { repository.observeSongs(playlistId).first() }
        assertTrue("歌单里至少要有 2 首", songs.size >= 2)

        connection.playSongs(songs, 0)
        assertTrue(
            "用户主动播放后，遗留的「本曲播完后停止」应当被清掉",
            waitUntil(5_000) { !sleepTimer.state.value.waitingForTrackEnd },
        )
    }

    @Test
    fun 一首自然播完后必须继续播下一首() = runBlocking {
        val connection = entryPoint.playbackConnection()
        val sleepTimer = entryPoint.sleepTimer()
        val repository = entryPoint.playlistRepository()

        val playlistId = withTimeout(20_000) {
            repository.observePlaylists().first().firstOrNull()?.id
        } ?: error("设备上没有歌单")
        val songs = withTimeout(20_000) { repository.observeSongs(playlistId).first() }
        assertTrue("歌单里至少要有 2 首才能验证自动续播", songs.size >= 2)

        // 故意先把遗留标志塞进去 —— 修复前就是这个状态导致曲末停住
        sleepTimer.forceWaitingForTrackEndForTest()
        connection.playSongs(songs, 0)
        withTimeout(30_000) { connection.state.first { it.hasMedia && it.isPlaying } }
        val startIndex = connection.state.value.currentIndex

        // 把当前曲拖到只剩 1.2 秒，让它自然播完
        val controller = connectController()
        try {
            val durationMs = mainSync { controller.duration }
            assertTrue("应该拿得到时长", durationMs > 5_000L)
            mainSync { controller.seekTo(durationMs - 1_200L) }

            // 自然播完后应当**继续播下一首且仍在播放**（修复前会停在这一首）
            val advanced = waitUntil(40_000) {
                val state = connection.state.value
                state.currentIndex == startIndex + 1 && state.isPlaying
            }
            assertTrue("一首自然播完后必须继续播下一首，而不是停下", advanced)
        } finally {
            mainSync { runCatching { controller.release() } }
        }
    }

    private fun connectController(): MediaController {
        val latch = CountDownLatch(1)
        var created: MediaController? = null
        instrumentation.runOnMainSync {
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync()
            future.addListener(
                {
                    created = runCatching { future.get() }.getOrNull()
                    latch.countDown()
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        assertTrue("连接 MediaController 超时", latch.await(10, TimeUnit.SECONDS))
        return created ?: error("MediaController 连接失败")
    }

    private fun <T> mainSync(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return false
    }
}
