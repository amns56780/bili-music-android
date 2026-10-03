package com.bilimusic.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bilimusic.app.data.player.PlaybackUiState
import com.bilimusic.app.domain.model.PlayMode

/**
 * FR-7 页面 6：全屏播放页。
 *
 * 已实现：封面、标题、UP主、**「第 X 首 / 共 N 首」常驻大字**、可拖动进度条、
 * 当前时间/总时长、上一首/播放暂停/下一首、播放模式按钮、当前音质标签。
 * 队列 BottomSheet 与定时关闭在 Phase 6 接上（不放点了没反应的按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onCollapse: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val queueSheetOpen by viewModel.queueSheetOpen.collectAsStateWithLifecycle()
    val timerSheetOpen by viewModel.timerSheetOpen.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    // 定时关闭的一次性提示（已取消定时停止 / 定时到点已停止播放）
    LaunchedEffect(sleepTimerState.notice) {
        val notice = sleepTimerState.notice
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            viewModel.consumeSleepNotice()
        }
    }

    // FR-10 音质降级提示
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice.orEmpty())
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正在播放", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Outlined.ExpandMore, contentDescription = "收起播放页")
                    }
                },
                actions = {
                    // FR-5 定时关闭入口（放在顶栏，底部边缘在部分 ROM 上不好点）
                    IconButton(onClick = viewModel::openSleepTimerSheet) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = "定时关闭",
                            tint = if (sleepTimerState.enabled || sleepTimerState.waitingForTrackEnd) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val isWide = maxWidth > maxHeight
            val sleepLabel = sleepTimerState.labelText
            if (isWide) {
                // 横屏 / 平板：左右分栏（左封面右控制），不拉伸变形
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    CoverArt(
                        artworkUrl = state.artworkUrl,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                    PlayerControls(
                        state = state,
                        viewModel = viewModel,
                        sleepLabel = sleepLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverArt(
                        artworkUrl = state.artworkUrl,
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(1f),
                    )
                    Spacer(Modifier.height(20.dp))
                    PlayerControls(
                        state = state,
                        viewModel = viewModel,
                        sleepLabel = sleepLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // FR-4 队列 BottomSheet
    if (queueSheetOpen) {
        QueueSheet(
            items = queue,
            onDismiss = viewModel::closeQueueSheet,
            onJump = viewModel::jumpToQueueItem,
            onMove = viewModel::moveQueueItem,
            onRemove = viewModel::removeQueueItem,
        )
    }

    // FR-5 定时关闭 BottomSheet
    if (timerSheetOpen) {
        SleepTimerSheet(
            state = sleepTimerState,
            onDismiss = viewModel::closeSleepTimerSheet,
            onStart = viewModel::startSleepTimer,
            onCancel = viewModel::cancelSleepTimer,
        )
    }
}

@Composable
private fun CoverArt(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        if (artworkUrl.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
            }
        } else {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackUiState,
    viewModel: PlayerViewModel,
    sleepLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.title.ifBlank { "还没有在播放" },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = state.artist.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        // ★ FR-4 硬性要求：播放页常驻显示「第 X 首 / 共 N 首」，字号足够大
        Text(
            text = state.indexText,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(20.dp))

        SeekBar(state = state, onSeek = viewModel::seekTo)

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = viewModel::cyclePlayMode) {
                Icon(
                    imageVector = when (state.playMode) {
                        PlayMode.SEQUENTIAL -> Icons.Outlined.Repeat
                        PlayMode.REPEAT_ALL -> Icons.Outlined.Repeat
                        PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
                        PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
                    },
                    contentDescription = "播放模式：${state.playMode.displayName}",
                    tint = if (state.playMode == PlayMode.SEQUENTIAL) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            IconButton(onClick = viewModel::previous, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(36.dp),
                )
            }

            FilledTonalIconButton(
                onClick = viewModel::togglePlayPause,
                modifier = Modifier.size(68.dp),
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            IconButton(onClick = viewModel::next, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(36.dp),
                )
            }

            // FR-4 队列按钮
            IconButton(onClick = viewModel::openQueueSheet) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "播放队列",
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.qualityLabel.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = state.qualityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = state.playMode.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))

            // FR-5 定时关闭入口 / 剩余时间标签
            TextButton(onClick = viewModel::openSleepTimerSheet) {
                Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(sleepLabel.ifBlank { "定时关闭" })
            }
        }
    }
}

@Composable
private fun SeekBar(
    state: PlaybackUiState,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: state.progress
    val durationMs = state.durationMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val target = dragging
                if (target != null && durationMs > 0L) {
                    onSeek((target * durationMs).toLong())
                }
                dragging = null
            },
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (dragging != null && durationMs > 0L) {
                    com.bilimusic.app.domain.model.formatDuration((dragging!! * durationMs).toLong())
                } else {
                    state.positionText
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.durationText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 播放页背景（给 MiniPlayer 展开动画用，保持一致） */
@Composable
internal fun PlayerBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
