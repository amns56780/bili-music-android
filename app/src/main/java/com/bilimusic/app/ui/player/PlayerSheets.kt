package com.bilimusic.app.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bilimusic.app.data.player.QueueItem
import com.bilimusic.app.data.player.SleepTimerState

private val SLEEP_PRESETS = listOf(15, 30, 45, 60, 90, 120)

/**
 * FR-4 队列页（BottomSheet）：按**真实播放顺序**列出，行首标序号，当前项高亮 + 动画指示。
 * 点击跳转、长按调整顺序、左滑从队列移除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    items: List<QueueItem>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放队列", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "共 ${items.size} 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "序号就是当前真实播放顺序；左滑移除，长按可上移/下移",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            if (items.isEmpty()) {
                Text(
                    text = "队列是空的",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    items(items, key = { it.mediaId + "#" + it.index }) { item ->
                        QueueRow(
                            item = item,
                            total = items.size,
                            onJump = { onJump(item.index) },
                            onMoveUp = { onMove(item.index, item.index - 1) },
                            onMoveDown = { onMove(item.index, item.index + 1) },
                            onRemove = { onRemove(item.index) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    total: Int,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        },
    )

    val highlight by animateFloatAsState(
        targetValue = if (item.isCurrent) 1f else 0f,
        label = "queueHighlight",
    )
    val background = if (item.isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f + 0.25f * highlight)
    } else {
        Color.Transparent
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "从队列移除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Box(modifier = Modifier.background(background)) {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onJump,
                        onLongClick = { menuOpen = true },
                    ),
                headlineContent = {
                    Text(
                        text = item.title.ifBlank { "未知曲目" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = item.artist.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!item.subtitle.isNullOrBlank()) {
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${item.index + 1}.",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (item.isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.width(28.dp),
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.artworkUrl.isNullOrBlank()) {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                AsyncImage(
                                    model = item.artworkUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
                trailingContent = {
                    if (item.isCurrent) {
                        PlayingIndicator()
                    }
                },
            )

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("上移") },
                    enabled = item.index > 0,
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text("下移") },
                    enabled = item.index < total - 1,
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMoveDown()
                    },
                )
            }
        }
    }
}

/** 当前播放项的动画指示（呼吸效果） */
@Composable
private fun PlayingIndicator() {
    val transition = rememberInfiniteTransition(label = "playing")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "playingAlpha",
    )
    Icon(
        imageVector = Icons.Outlined.GraphicEq,
        contentDescription = "正在播放",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(20.dp)
            .alpha(alpha),
    )
}

/**
 * FR-5 定时关闭 BottomSheet：
 * 预设 15/30/45/60/90/120 分钟 + 自定义（1~720 分钟）+ 「播完当前歌曲后停止」开关（默认开）+ 取消定时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    onDismiss: () -> Unit,
    onStart: (minutes: Int, stopAfterCurrent: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customText by remember { mutableStateOf("") }
    var stopAfterCurrent by remember { mutableStateOf(state.stopAfterCurrentEnabled) }

    val customMinutes = customText.trim().toIntOrNull()
    val customValid = customMinutes != null && customMinutes in 1..720

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("定时关闭", style = MaterialTheme.typography.titleMedium)

            if (state.enabled || state.waitingForTrackEnd) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.labelText,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (state.waitingForTrackEnd) {
                                Text(
                                    text = "到点了，但为了不截断当前歌曲，等它自然播完再停",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        TextButton(onClick = onCancel) { Text("取消定时") }
                    }
                }
            }

            Text(
                text = "预设",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SLEEP_PRESETS.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { minutes ->
                        FilterChip(
                            selected = false,
                            onClick = { onStart(minutes, stopAfterCurrent) },
                            label = { Text("$minutes 分钟") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Text(
                text = "自定义（1~720 分钟）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input -> customText = input.filter { it.isDigit() }.take(3) },
                    label = { Text("分钟") },
                    singleLine = true,
                    isError = customText.isNotBlank() && !customValid,
                    supportingText = {
                        if (customText.isNotBlank() && !customValid) Text("请输入 1~720 的整数")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { customMinutes?.let { onStart(it, stopAfterCurrent) } },
                    enabled = customValid,
                ) {
                    Text("开始")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("播完当前歌曲后停止", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "开启：到点不打断当前歌曲，播完这一首才停（默认）\n关闭：到点立即暂停",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = stopAfterCurrent,
                        onCheckedChange = { stopAfterCurrent = it },
                    )
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("关闭")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
