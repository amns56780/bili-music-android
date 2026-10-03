package com.bilimusic.app.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilimusic.app.domain.model.Episode
import com.bilimusic.app.domain.model.formatDuration
import com.bilimusic.app.ui.components.EmptyState
import com.bilimusic.app.ui.components.ErrorState
import com.bilimusic.app.ui.components.LoadingState

/**
 * FR-7 页面 5：选集页（FR-3）。
 *
 * 逐条列出全部分P / 所有集（序号、标题、时长、Checkbox），默认全选，
 * 提供 全选 / 全不选 / 反选，底部固定操作栏显示「已选 n / 共 m」，
 * 一集都没选时按钮置灰并提示「请至少选择 1 集」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelectionScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: EpisodeSelectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EpisodeSelectionEvent.Message -> snackbarHostState.showSnackbar(event.text)
                EpisodeSelectionEvent.PlayStarted -> onOpenPlayer()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title.ifBlank { "选集" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.loading -> LoadingState(text = "正在读取分P/合集…")

                uiState.error != null -> ErrorState(
                    message = uiState.error.orEmpty(),
                    onRetry = viewModel::load,
                )

                uiState.episodes.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.PlaylistAdd,
                    title = "没有可选的集",
                    description = "这个视频既没有分P也没有合集内容。",
                    actionLabel = "重新读取",
                    onAction = viewModel::load,
                )

                else -> {
                    // 快捷操作
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${uiState.upperName.ifBlank { "本组" }} · 共 ${uiState.totalCount} 集",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::selectAll) { Text("全选") }
                        TextButton(onClick = viewModel::clearAll) { Text("全不选") }
                        TextButton(onClick = viewModel::invertSelection) { Text("反选") }
                    }
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(uiState.episodes, key = { it.epCid }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                onToggle = { viewModel.toggleEpisode(episode) },
                            )
                        }
                    }
                }
            }

            BottomActionBar(
                selectedCount = uiState.selectedCount,
                totalCount = uiState.totalCount,
                selectedDurationMs = uiState.selectedDurationMs,
                onPlaySelected = viewModel::playSelected,
                onAddToPlaylist = viewModel::openPlaylistPicker,
            )
        }
    }

    if (uiState.playlistPickerOpen) {
        PlaylistPickerDialog(
            playlists = uiState.playlists,
            adding = uiState.addingToPlaylist,
            onPick = { id, title -> viewModel.addSelectedToPlaylist(id, title) },
            onDismiss = viewModel::closePlaylistPicker,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    onToggle: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        headlineContent = {
            Text(
                text = episode.epTitle.ifBlank { "第 ${episode.pageIndex} 集" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "第 ${episode.pageIndex} 集 · ${formatDuration(episode.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Checkbox(checked = episode.selected, onCheckedChange = { onToggle() })
        },
    )
}

/** 底部固定操作栏：已选 n / 共 m + 播放选中 / 加入歌单 */
@Composable
private fun BottomActionBar(
    selectedCount: Int,
    totalCount: Int,
    selectedDurationMs: Long,
    onPlaySelected: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val hasSelection = selectedCount > 0
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已选 $selectedCount / 共 $totalCount",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasSelection) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.weight(1f))
                if (hasSelection) {
                    Text(
                        text = "合计 ${formatDuration(selectedDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!hasSelection) {
                Text(
                    text = "请至少选择 1 集",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlaySelected,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("播放选中")
                }
                OutlinedButton(
                    onClick = onAddToPlaylist,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("加入歌单")
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerDialog(
    playlists: List<com.bilimusic.app.domain.model.Playlist>,
    adding: Boolean,
    onPick: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入哪个歌单？") },
        text = {
            if (playlists.isEmpty()) {
                Text("还没有歌单，先去歌单页新建一个吧。")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !adding) { onPick(playlist.id, playlist.title) },
                            headlineContent = {
                                Text(
                                    text = playlist.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text("${playlist.songCount} 首") },
                            trailingContent = {
                                Box(modifier = Modifier.width(8.dp))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
