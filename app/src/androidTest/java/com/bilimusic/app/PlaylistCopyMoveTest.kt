package com.bilimusic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.SongDraft
import com.bilimusic.app.widget.PlayerWidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 歌单之间批量**复制 / 移动**的仓库层测试。
 *
 * 用两个临时歌单跑，跑完在 finally 里删掉 —— 不碰用户真实歌单
 * （「移动」会真的删源歌单的歌，所以不能拿真实数据试）。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.PlaylistCopyMoveTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PlaylistCopyMoveTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val repository: PlaylistRepository
        get() = EntryPointAccessors.fromApplication(context, PlayerWidgetEntryPoint::class.java)
            .playlistRepository()

    @Test
    fun 复制不动源歌单_移动会从源歌单移除_重复自动跳过() = runBlocking {
        val repo = repository
        val src = repo.createPlaylist("__测试源歌单__")
        val dst = repo.createPlaylist("__测试目标歌单__")
        try {
            // 造 3 首假曲目（用不存在的 bvid，只验证库内搬运动作）
            val drafts = (1..3).map { i ->
                SongDraft(
                    bvid = "BVtest000000$i",
                    cid = 900000L + i,
                    title = "测试曲目 $i",
                    upperName = "测试UP",
                    coverUrl = null,
                    durationMs = 60_000L * i,
                )
            }
            val added = repo.addSongs(src, drafts)
            assertEquals("源歌单应当插入 3 首", 3, added.inserted)
            val sourceSongs = withTimeout(10_000) { repo.getSongs(src) }
            assertEquals(3, sourceSongs.size)

            // ---- 复制：源不变，目标拿到 2 首 ----
            val toCopy = sourceSongs.take(2).map { it.id }.toSet()
            val copyResult = repo.copySongsTo(src, dst, toCopy)
            assertEquals("应当复制 2 首", 2, copyResult.inserted)
            assertEquals("复制不能动源歌单", 3, repo.getSongs(src).size)
            assertEquals("目标歌单应当有 2 首", 2, repo.getSongs(dst).size)

            // ---- 再复制一次：全部判重跳过 ----
            val again = repo.copySongsTo(src, dst, toCopy)
            assertEquals("重复复制应当插 0 首", 0, again.inserted)
            assertEquals("重复复制应当全部判重", 2, again.skippedAsDuplicate)
            assertEquals("重复复制不应改变目标数量", 2, repo.getSongs(dst).size)

            // ---- 移动：源少一首，目标多一首 ----
            val toMove = setOf(sourceSongs.last().id)
            val moveResult = repo.moveSongsTo(src, dst, toMove)
            assertEquals("应当移动 1 首", 1, moveResult.inserted)
            assertEquals("移动后源歌单应当只剩 2 首", 2, repo.getSongs(src).size)
            assertEquals("移动后目标歌单应当有 3 首", 3, repo.getSongs(dst).size)
        } finally {
            // 清理：删掉两个临时歌单（级联删曲目）
            repo.deletePlaylist(src)
            repo.deletePlaylist(dst)
        }

        // 收尾确认：临时歌单确实被清掉了
        val titles = withTimeout(10_000) { repo.observePlaylists().first() }.map { it.title }
        assertTrue(
            "临时歌单应当已被清理，实际还剩：${titles.filter { it.startsWith("__测试") }}",
            titles.none { it.startsWith("__测试") },
        )
    }
}
