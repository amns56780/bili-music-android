package com.bilimusic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilimusic.app.data.local.LocalMusicRepository
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
 * 本地音乐：扫描系统媒体库 + 导入歌单时**本地 URI 必须存下来**（不然导入后播不了）。
 *
 * 用临时歌单跑，跑完删掉 —— 不碰用户真实歌单。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.LocalMusicImportTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalMusicImportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val entry: PlayerWidgetEntryPoint
        get() = EntryPointAccessors.fromApplication(context, PlayerWidgetEntryPoint::class.java)

    @Test
    fun 本地歌曲能扫描到并带URI导入歌单() = runBlocking {
        val playlists: PlaylistRepository = entry.playlistRepository()
        val localMusic: LocalMusicRepository = entry.localMusicRepository()

        assertTrue("设备上应当已授予读取音频的权限", localMusic.hasPermission())

        val scanned = withTimeout(30_000) { localMusic.scan() }
        assertTrue("媒体库里应当能扫到本地音乐（当前 ${scanned.size} 首）", scanned.isNotEmpty())
        assertTrue("扫描结果必须有 content:// URI", scanned.all { it.uri.startsWith("content://") })
        assertTrue("时长应当是正数", scanned.all { it.durationMs > 0L })

        val temp = playlists.createPlaylist("__本地导入测试__")
        try {
            val picked = scanned.take(2)
            val drafts = picked.map { song ->
                SongDraft(
                    // 本地曲目统一用 "local" 当 bvid，cid 用 MediaStore id
                    bvid = "local",
                    cid = song.id,
                    title = song.title,
                    upperName = song.artist,
                    coverUrl = song.artworkUri,
                    durationMs = song.durationMs,
                    localUri = song.uri,
                )
            }

            val result = playlists.addSongs(temp, drafts)
            assertEquals("应当导入 2 首", 2, result.inserted)

            val stored = playlists.getSongs(temp)
            assertEquals(2, stored.size)
            assertTrue("导入后必须仍是本地曲目", stored.all { it.isLocal })
            assertEquals("本地 URI 必须原样存下来", picked[0].uri, stored[0].localUri)
            assertEquals("mediaKey 必须是 local-<id>", "local-${picked[0].id}", stored[0].mediaKey)

            // 再导入一次：同一文件应当判重跳过，不会出现两份
            val again = playlists.addSongs(temp, drafts)
            assertEquals("重复导入应当跳过", 0, again.inserted)
            assertEquals("重复导入不应新增", 2, playlists.getSongs(temp).size)
        } finally {
            playlists.deletePlaylist(temp)
        }

        val titles = withTimeout(10_000) { playlists.observePlaylists().first() }.map { it.title }
        assertTrue(
            "临时歌单应当已被清理，实际还剩：${titles.filter { it.startsWith("__本地") }}",
            titles.none { it.startsWith("__本地") },
        )
    }
}
