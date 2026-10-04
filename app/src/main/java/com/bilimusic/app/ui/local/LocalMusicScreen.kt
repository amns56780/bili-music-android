package com.bilimusic.app.ui.local

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilimusic.app.data.local.LocalSong
import com.bilimusic.app.domain.model.formatDuration
import com.bilimusic.app.ui.components.EmptyState
import com.bilimusic.app.ui.components.LoadingState

/**
 * 本地音乐页：扫描系统媒体库，可以**直接播放**，也可以**导入到歌单**
 * 跟 B 站曲目混在一个队列里（这样随机 / 定时 / 小组件这些能力全都能用上）。
 *
 * 只读音频文件，不申请任何写入权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // 不管授予与否都重新判断一次：拒绝了就继续显示引导卡片
        viewModel.refreshPermissionAndScan()
    }

    // 回到页面时如果用户去系统设置里开过权限，这里能自动刷新
    LaunchedEffect(Unit) {
        viewModel.refreshPermissionAndScan()
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.songs.isEmpty()) {
                            "本地音乐"
                        } else {
                            "本地音乐（${uiState.songs.size} 首）"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.permissionGranted) {
                        TextButton(onClick = viewModel::selectAll, enabled = uiState.songs.isNotEmpty()) {
                            Text("全选")
                        }
                        TextButton(onClick = viewModel::invertSelection, enabled = uiState.songs.isNotEmpty()) {
                            Text("反选")
                        }
                        IconButton(onClick = viewModel::scan) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !uiState.permissionGranted -> PermissionGate(
                    onRequest = { permissionLauncher.launch(viewModel.requiredPermission) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )

                uiState.scanning -> LoadingState(text = "正在扫描本地音乐…")

                uiState.songs.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LibraryMusic,
                    title = "没找到本地音乐",
                    description = "媒体库里没有音频文件。可以把 mp3/flac 拷到手机后重新扫描，" +
                        "或者确认系统「音乐」App 里能看到这些文件。",
                    actionLabel = "重新扫描",
                    onAction = viewModel::scan,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 148.dp),
                ) {
                    items(uiState.songs, key = { it.id }) { song ->
                        LocalSongRow(
                            song = song,
                            selected = song.id in uiState.selectedIds,
                            onToggle = { viewModel.toggleSelect(song) },
                            onClick = {
                                // 点整行 = 直接播这一首（当本地播放器用）；勾选请点左侧复选框
                                viewModel.playFrom(song)
                                onOpenPlayer()
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            if (uiState.permissionGranted && uiState.songs.isNotEmpty()) {
                LocalActionBar(
                    selectedCount = uiState.selectedCount,
                    totalCount = uiState.songs.size,
                    selectedDurationMs = uiState.totalDurationMs,
                    onPlay = {
                        viewModel.playSelected()
                        onOpenPlayer()
                    },
                    onImport = viewModel::openPlaylistPicker,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (uiState.pickerOpen) {
        LocalPlaylistPickerDialog(
            playlists = uiState.playlists,
            onPick = { id, title -> viewModel.importToPlaylist(id, title) },
            onDismiss = viewModel::closePlaylistPicker,
        )
    }
}

/** 没权限时的引导卡片（不硬刚，讲清楚为什么要这个权限） */
@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("需要读取音频文件的权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "用来扫描手机里的 mp3 / flac 等音频，做成歌单直接播放。\n" +
                "只读音频，不碰照片和视频，也不申请任何写入权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("授予权限") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) { Text("已拒绝过？去系统设置里开") }
    }
}

@Composable
private fun LocalSongRow(
    song: LocalSong,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = buildString {
                    append(song.artist)
                    if (song.album.isNotBlank()) append(" · ${song.album}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        },
        trailingContent = {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 底部操作栏：播放选中 / 导入到歌单 */
@Composable
private fun LocalActionBar(
    selectedCount: Int,
    totalCount: Int,
    selectedDurationMs: Long,
    onPlay: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = selectedCount > 0
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hasSelection) "已选 $selectedCount / 共 $totalCount" else "共 $totalCount 首",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasSelection) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (hasSelection) "播放选中" else "播放全部")
                }
                OutlinedButton(
                    onClick = onImport,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("导入到歌单")
                }
            }
        }
    }
}

@Composable
private fun LocalPlaylistPickerDialog(
    playlists: List<com.bilimusic.app.domain.model.Playlist>,
    onPick: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入到哪个歌单？") },
        text = {
            if (playlists.isEmpty()) {
                Text("还没有歌单，先去歌单页新建一个吧。")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist.id, playlist.title) },
                            headlineContent = {
                                Text(
                                    text = playlist.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text("${playlist.songCount} 首") },
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

/** 歌单详情页里给本地曲目用的小标签 */
@Composable
fun LocalBadge(modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Text(
            text = "本地",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
