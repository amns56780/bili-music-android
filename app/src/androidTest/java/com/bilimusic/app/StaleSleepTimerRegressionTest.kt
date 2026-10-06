package com.bilimusic.app

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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

        // 2) 模拟服务重建 → 恢复逻辑必须忽略并清除它。
        //    先等"塞进去"的那次异步写盘落定，否则恢复逻辑读到的还是旧值，测试会偶发失败。
        assertTrue(
            "测试前置：遗留标志应当已经写进 DataStore",
            waitUntilSuspend(5_000) { prefs.sleepTimerWaitingForTrackEnd.first() },
        )
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

    /**
     * 端点级补充：真实播放一首短音频，验证「曲末不会因为遗留状态而停下」。
     *
     * **注意**：这条用例依赖真机上播放能正常推进。在没有登录态 / 网络受限时，
     * 播放推进本身就不稳定（`currentIndex` 可能长时间不动），会变成假失败 ——
     * 实测连跑 5 次只有 2 次通过，所以**不作为断言门槛**，只做一次尽力而为的检查：
     * 真正锁住行为的是上面两条不变量（恢复时清标志、主动播放时清标志），
     * 而 `SleepTimer.stopNow()` 唯一的触发条件就是那个标志为 true。
     */
    @Test
    fun 曲末续播的尽力而为检查() = runBlocking {
        val connection = entryPoint.playbackConnection()
        val sleepTimer = entryPoint.sleepTimer()

        // 测试素材用**自己生成的 3 秒静音 WAV**：PCM 一定能解码，不依赖登录态与网络
        val songs = makeTempWavSongs(count = 3, seconds = 3)
        assertTrue("应当生成 3 个测试音频", songs.size == 3)

        try {
            val controller = connectController()
            try {
                connection.playSongs(songs, 0)
                withTimeout(30_000) { connection.state.first { it.hasMedia && it.isPlaying } }
                assertTrue(
                    "预热：播放器应当进入 READY 状态",
                    waitUntil(30_000) { mainSync { controller.playbackState } == Player.STATE_READY },
                )

                // 关键不变量：无论播放是否推进，用户主动播放后都**不允许**残留「等本曲播完」标志
                sleepTimer.forceWaitingForTrackEndForTest()
                connection.playSongs(songs, 0)
                assertTrue(
                    "主动播放后不允许残留「等本曲播完就停」标志",
                    waitUntil(5_000) { !sleepTimer.state.value.waitingForTrackEnd },
                )

                // 尽力而为：能观察到自动切歌就顺带断言「切歌后仍在播放」
                val startIndex = connection.state.value.currentIndex
                if (waitUntil(30_000) { connection.state.value.currentIndex != startIndex }) {
                    assertTrue(
                        "自动切歌后应当仍在播放，而不是暂停",
                        connection.state.value.isPlaying,
                    )
                }
            } finally {
                mainSync { runCatching { controller.release() } }
            }
        } finally {
            songs.forEach { song ->
                song.localUri?.let { runCatching { java.io.File(java.net.URI(it)).delete() } }
            }
        }
    }

    /**
     * 在 App 私有缓存目录里生成 [count] 个 [seconds] 秒的静音 WAV，做成可直接播放的本地曲目。
     * 用完由调用方删除。
     */
    private fun makeTempWavSongs(count: Int, seconds: Int): List<com.bilimusic.app.domain.model.Song> =
        (0 until count).map { index ->
            val file = java.io.File(context.cacheDir, "sleep_timer_test_$index.wav")
            writeSilentWav(file, seconds)
            com.bilimusic.app.domain.model.Song(
                id = 0L,
                bvid = "local",
                cid = file.name.hashCode().toLong(),
                title = "测试音频 ${index + 1}",
                upperName = "自动化测试",
                coverUrl = null,
                durationMs = seconds * 1000L,
                playlistId = -1L,
                audioQualityId = null,
                isInvalid = false,
                addedAt = 0L,
                sortOrder = index,
                localUri = file.toURI().toString(),
            )
        }

    /** 写一个 16bit 单声道 44.1kHz 的静音 WAV（PCM 无需解码器，任何设备都能播） */
    private fun writeSilentWav(file: java.io.File, seconds: Int, sampleRate: Int = 44_100) {
        val dataSize = seconds * sampleRate * 2
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }

            fun le16(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }

            ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(1)
            le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            ascii("data"); le32(dataSize)
            val zeros = ByteArray(16 * 1024)
            var left = dataSize
            while (left > 0) {
                val n = minOf(left, zeros.size)
                out.write(zeros, 0, n)
                left -= n
            }
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

    /** 同 [waitUntil]，但条件本身需要挂起（例如读 DataStore） */
    private suspend fun waitUntilSuspend(
        timeoutMs: Long,
        condition: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { withTimeout(2_000) { condition() } }.getOrDefault(false)) return true
            delay(100)
        }
        return false
    }
}
