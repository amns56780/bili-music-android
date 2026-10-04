package com.bilimusic.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bilimusic.app.MainActivity
import com.bilimusic.app.R
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.player.PlaybackUiState
import com.bilimusic.app.domain.model.PlayMode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * FR-11 桌面小组件（Glance，4x2）。
 *
 * - 显示：封面缩略图由系统媒体控件负责，这里显示曲名 / UP主 / 第几首 / 音质
 * - 三个按钮：上一首 / 播放暂停 / 下一首，**通过 MediaController 真的控制播放**
 *   （ActionCallback 里拿 PlaybackConnection，它会连上 MediaSessionService）
 * - 播放器没在放时显示「未在播放」，点整块打开 App
 */
class PlayerWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val connection = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerWidgetEntryPoint::class.java,
        ).playbackConnection()

        provideContent {
            val state by connection.state.collectAsState()
            GlanceTheme {
                PlayerWidgetContent(state)
            }
        }
    }
}

/**
 * 小组件内容（internal 是为了能用 Glance 官方单测框架验证渲染结果）。
 */
@Composable
internal fun PlayerWidgetContent(state: PlaybackUiState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (!state.hasMedia) {
            Text(
                text = "未在播放",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "点这里打开「bilimusic」选一首歌",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
            return@Column
        }

        Text(
            text = state.title.ifBlank { "未知曲目" },
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = state.artist.ifBlank { "—" },
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = buildString {
                append(state.indexText)
                if (state.qualityLabel.isNotBlank()) append(" · ${state.qualityLabel}")
                if (state.playMode != PlayMode.SEQUENTIAL) {
                    append(" · ${state.playMode.displayName}")
                }
            },
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            maxLines = 1,
        )

        Spacer(GlanceModifier.defaultWeight())
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetButton(
                iconRes = R.drawable.ic_widget_previous,
                description = "上一首",
                action = actionRunCallback<PreviousAction>(),
            )
            Spacer(GlanceModifier.size(18.dp))
            WidgetButton(
                iconRes = if (state.isPlaying) {
                    R.drawable.ic_widget_pause
                } else {
                    R.drawable.ic_widget_play
                },
                description = if (state.isPlaying) "暂停" else "播放",
                action = actionRunCallback<PlayPauseAction>(),
            )
            Spacer(GlanceModifier.size(18.dp))
            WidgetButton(
                iconRes = R.drawable.ic_widget_next,
                description = "下一首",
                action = actionRunCallback<NextAction>(),
            )
        }
    }
}

@Composable
private fun WidgetButton(
    iconRes: Int,
    description: String,
    action: Action,
) {
    Image(
        provider = ImageProvider(iconRes),
        contentDescription = description,
        modifier = GlanceModifier
            .size(44.dp)
            .clickable(action),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
    )
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/** Hilt 入口：小组件的 ActionCallback / provideGlance 里拿播放连接（测试也复用它） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerWidgetEntryPoint {
    fun playbackConnection(): PlaybackConnection

    fun playlistRepository(): com.bilimusic.app.data.repository.PlaylistRepository

    fun playbackPrefs(): com.bilimusic.app.data.local.prefs.PlaybackPrefs

    fun sleepTimer(): com.bilimusic.app.data.player.SleepTimer

    fun localMusicRepository(): com.bilimusic.app.data.local.LocalMusicRepository
}
