package com.bilimusic.app

import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.widget.NextAction
import com.bilimusic.app.widget.PlayPauseAction
import com.bilimusic.app.widget.PlayerWidgetEntryPoint
import com.bilimusic.app.widget.PreviousAction
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-11 小组件按钮功能测试：直接调用 Glance 的 ActionCallback，
 * 验证「上一首 / 播放暂停 / 下一首」真的能控制播放（走的是和桌面点击完全相同的代码路径）。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.WidgetControlTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WidgetControlTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val entryPoint: PlayerWidgetEntryPoint
        get() = EntryPointAccessors.fromApplication(context, PlayerWidgetEntryPoint::class.java)

    private val fakeGlanceId = object : GlanceId {}

    /** 空参数（Glance 的 ActionParameters 构造器是 internal，用公开的 actionParametersOf 造） */
    private val emptyParams: ActionParameters = actionParametersOf()

    @Test
    fun 小组件三个按钮都能控制播放() = runBlocking {
        val connection = entryPoint.playbackConnection()
        connection.connect()

        // 用设备上已有歌单的前几首当队列
        val repository = entryPoint.playlistRepository()
        val playlistId = withTimeout(20_000) {
            repository.observePlaylists().first().firstOrNull()?.id
        } ?: error("设备上没有歌单，无法测试")
        val songs = withTimeout(20_000) { repository.observeSongs(playlistId).first() }
        assertTrue("歌单里至少要有 3 首才能测上下曲", songs.size >= 3)

        connection.playSongs(songs, 0)
        withTimeout(30_000) {
            connection.state.first { it.hasMedia && it.isPlaying }
        }
        val firstIndex = connection.state.value.currentIndex

        // 1) 播放/暂停按钮：点一下应当暂停
        PlayPauseAction().onAction(context, fakeGlanceId, emptyParams)
        assertTrue(
            "点小组件暂停后应当暂停",
            waitUntil(10_000) { !connection.state.value.isPlaying },
        )

        // 2) 再点一下应当恢复播放
        PlayPauseAction().onAction(context, fakeGlanceId, emptyParams)
        assertTrue(
            "再点小组件应当恢复播放",
            waitUntil(10_000) { connection.state.value.isPlaying },
        )

        // 3) 下一首按钮
        NextAction().onAction(context, fakeGlanceId, emptyParams)
        assertTrue(
            "点小组件下一首应当切歌",
            waitUntil(15_000) { connection.state.value.currentIndex == firstIndex + 1 },
        )

        // 4) 上一首按钮
        PreviousAction().onAction(context, fakeGlanceId, emptyParams)
        assertTrue(
            "点小组件上一首应当切回",
            waitUntil(15_000) { connection.state.value.currentIndex == firstIndex },
        )

        // 5) 队列长度不应被按钮改动
        assertEquals(
            "小组件按钮不应改变队列长度",
            songs.size,
            connection.state.value.queueSize,
        )

        // 收尾：暂停
        if (connection.state.value.isPlaying) {
            connection.togglePlayPause()
        }
        assertTrue("收尾时应当是暂停状态", waitUntil(10_000) { !connection.state.value.isPlaying })
        assertFalse(connection.state.value.isPlaying)
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
