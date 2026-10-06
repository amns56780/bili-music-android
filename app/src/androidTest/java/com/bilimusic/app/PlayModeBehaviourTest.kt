package com.bilimusic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.app.widget.PlayerWidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 播放模式改动验证（用户反馈：随机播完就停 / 要默认列表循环 / 顺序与列表循环图标分不清）。
 *
 * 1. 默认模式是**列表循环**（`PlayMode.DEFAULT`，单测也覆盖）
 * 2. `playSongs` 不再把模式硬写成「顺序播放」，而是**沿用保存的模式**
 * 3. 「随机播放」按钮（shuffle = true）强制切随机并记住
 *
 * 走真实播放器链路（MediaController → PlaybackService），不依赖登录态；
 * 队列用设备上已有歌单的曲目，不新造数据。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.PlayModeBehaviourTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PlayModeBehaviourTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val entry: PlayerWidgetEntryPoint
        get() = EntryPointAccessors.fromApplication(context, PlayerWidgetEntryPoint::class.java)

    @Test
    fun 播放会沿用保存的模式而不是硬写成顺序播放() = runBlocking {
        val prefs = entry.playbackPrefs()
        val connection = entry.playbackConnection()
        val songs = firstPlaylistSongs()

        // 先等播放服务连上（冷启动时 MediaController 建连要一会儿，不等的话模式还没应用上来）
        connection.connect()
        assertTrue(
            "MediaController 应当连接成功",
            waitUntil(30_000) { connection.state.value.connected },
        )

        // 1) 保存「列表循环」→ 播放全部（不是随机按钮）→ 应当就是列表循环
        prefs.setPlayMode(PlayMode.REPEAT_ALL.name)
        connection.playSongs(songs, 0)
        assertEquals(
            "保存列表循环时，播放全部应当保持列表循环",
            PlayMode.REPEAT_ALL,
            awaitMode(connection, PlayMode.REPEAT_ALL),
        )

        // 2) 保存「顺序播放」→ 播放全部 → 应沿用顺序播放（这正是以前被硬写死的那个 bug）
        prefs.setPlayMode(PlayMode.SEQUENTIAL.name)
        connection.playSongs(songs, 0)
        assertEquals(
            "保存顺序播放时，播放全部应当保持顺序播放",
            PlayMode.SEQUENTIAL,
            awaitMode(connection, PlayMode.SEQUENTIAL),
        )

        // 3) 「随机播放」按钮 → 强制随机
        connection.playSongs(songs, 0, shuffle = true)
        assertEquals(
            "点随机播放应当切到随机模式",
            PlayMode.SHUFFLE,
            awaitMode(connection, PlayMode.SHUFFLE),
        )
        assertEquals(
            "随机模式应当被记住",
            PlayMode.SHUFFLE.name,
            withTimeout(5_000) { prefs.playMode.first() },
        )

        // 4) 随机模式下再点「播放全部」→ 沿用随机的队列顺序（模式保持随机）
        connection.playSongs(songs, 0)
        assertEquals(
            "随机模式下播放全部应保持随机模式",
            PlayMode.SHUFFLE,
            awaitMode(connection, PlayMode.SHUFFLE),
        )

        // 收尾：恢复成默认的列表循环，别把用户设置留在随机上
        connection.setPlayMode(PlayMode.DEFAULT)
        assertEquals(PlayMode.REPEAT_ALL, awaitMode(connection, PlayMode.REPEAT_ALL))
        if (connection.state.value.isPlaying) connection.togglePlayPause()
    }

    @Test
    fun 默认模式常量是列表循环() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.DEFAULT)
    }

    private suspend fun firstPlaylistSongs(): List<com.bilimusic.app.domain.model.Song> {
        val repo: PlaylistRepository = entry.playlistRepository()
        val playlistId = withTimeout(20_000) {
            repo.observePlaylists().first().firstOrNull()?.id
        } ?: error("设备上没有歌单，无法测试")
        val songs = withTimeout(20_000) { repo.observeSongs(playlistId).first() }
        assertTrue("歌单里至少要有 1 首", songs.isNotEmpty())
        return songs
    }

    private suspend fun awaitMode(connection: PlaybackConnection, expected: PlayMode): PlayMode {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val mode = connection.state.value.playMode
            if (mode == expected && connection.state.value.hasMedia) return mode
            delay(200)
        }
        return connection.state.value.playMode
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
