package com.bilimusic.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bilimusic.app.data.player.PlaybackUiState
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.app.widget.PlayerWidgetContent
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-11 小组件渲染测试：用 Glance 官方单测框架把小组件的 composition 真的跑一遍
 * （渲染成 RemoteViews），验证曲目信息与三个控制按钮都出来了。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.PlayerWidgetRenderTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PlayerWidgetRenderTest {

    @Test
    fun 播放中时渲染曲目信息与三个控制按钮() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable {
            GlanceTheme {
                PlayerWidgetContent(
                    PlaybackUiState(
                        connected = true,
                        hasMedia = true,
                        title = "电棍：大石碎胸口",
                        artist = "沈默沈默",
                        isPlaying = true,
                        currentIndex = 2,
                        queueSize = 5,
                        qualityLabel = "192K",
                        playMode = PlayMode.REPEAT_ALL,
                    ),
                )
            }
        }
        awaitIdle()

        onNode(hasText("电棍：大石碎胸口")).assertExists()
        onNode(hasText("沈默沈默")).assertExists()
        onNode(hasText("第 3 首 / 共 5 首 · 192K · 列表循环")).assertExists()
        // 三个控制按钮
        onNode(hasContentDescription("暂停")).assertExists()
        onNode(hasContentDescription("上一首")).assertExists()
        onNode(hasContentDescription("下一首")).assertExists()
    }

    @Test
    fun 未播放时显示未在播放提示() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable {
            GlanceTheme {
                PlayerWidgetContent(PlaybackUiState())
            }
        }
        awaitIdle()

        onNode(hasText("未在播放")).assertExists()
        onNode(hasText("点这里打开「B站音乐」选一首歌")).assertExists()
    }

    @Test
    fun 暂停时按钮变成播放() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable {
            GlanceTheme {
                PlayerWidgetContent(
                    PlaybackUiState(
                        connected = true,
                        hasMedia = true,
                        title = "某首歌",
                        artist = "某UP主",
                        isPlaying = false,
                        queueSize = 1,
                    ),
                )
            }
        }
        awaitIdle()

        onNode(hasContentDescription("播放")).assertExists()
    }
}
