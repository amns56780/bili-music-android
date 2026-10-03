package com.bilimusic.app

import android.content.ComponentName
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.player.PlaybackService
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FR-5 核心正确性测试（真机，约 2.5 分钟）：
 * **到点后当前歌曲必须完整播完，不许中途截断，也不许多播下一首。**
 *
 * 步骤：
 * 1. 播放歌单 → 设 1 分钟定时（默认开启「播完当前歌曲后停止」）
 * 2. 等到点：断言标签变成「本曲播完后停止」，并且播放按钮仍是暂停图标（音乐没被截断）
 * 3. 用 MediaController（**只能在 App 主线程调用**）把当前曲目拖到只剩 1.2 秒
 * 4. 断言：这一首自然播完后播放已暂停（按钮变「播放」），没有继续多播下一首
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.SleepTimerDeadlineTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 注意：部分 ROM 会弹「允许 B 站音乐打开测试包」的确认框，点允许即可；
 * 测试结束后被测进程被回收属于正常现象。
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerDeadlineTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun 定时到点后本曲完整播完才停止() {
        // 1. 播放第一个歌单
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("我的歌单").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(
            hasClickAction() and hasText("首 ·", substring = true),
        ).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("播放全部").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("播放全部").performClick()
        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithContentDescription("定时关闭").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. 设 1 分钟定时（开关默认「播完当前歌曲后停止」）
        composeRule.onNodeWithContentDescription("定时关闭").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("播完当前歌曲后停止").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1")
        composeRule.onNodeWithText("开始").performClick()

        // 3. 等到点：标签变成「本曲播完后停止」
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithText("本曲播完后停止", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. ★ 关键断言：到点后音乐**还在播**（暂停图标仍在 = 当前曲目没有被截断）
        composeRule.onNodeWithContentDescription("暂停")
            .assertExists("定时到点后不应该立刻暂停，应该等本曲播完")

        // 5. 主线程上连接 MediaController，把当前曲目拖到只剩 1.2 秒
        val controller = connectController()
        try {
            assertTrue("连接后仍在播放", mainSync { controller.playWhenReady })
            val durationMs = mainSync { controller.duration }
            assertTrue("应该拿得到时长", durationMs > 5_000L)
            mainSync { controller.seekTo(durationMs - 1_200L) }

            // 6. ★ 自然播完后应当暂停，且不会继续多播下一首
            val stopped = waitUntilTrue(timeoutMs = 30_000L) {
                !mainSync { controller.playWhenReady }
            }
            assertTrue("本曲自然播完后应当停止播放（不许多播下一首）", stopped)
        } finally {
            mainSync { runCatching { controller.release() } }
        }

        // 7. UI 也要跟上：按钮从「暂停」变回「播放」
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("播放").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** MediaController 必须在 App 主线程创建与调用 */
    private fun connectController(): MediaController {
        val context = instrumentation.targetContext
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

    private fun waitUntilTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(300L)
        }
        return false
    }
}
