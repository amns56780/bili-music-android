package com.bilimusic.app.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * FR-7 页面 1：登录页。
 * 主路径：扫码（180 秒倒计时、4 种状态文案、过期自动刷新、2 秒轮询 + 退避）
 * 保底路径：手动粘贴 Cookie（含「如何获取 Cookie」折叠说明）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginEvent.LoggedIn -> snackbarHostState.showSnackbar("登录成功，欢迎 ${event.name}")
            }
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("登录 B 站账号") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "用 B 站手机客户端扫码登录，登录态只保存在本机",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            QrPanel(
                uiState = uiState,
                onRefresh = viewModel::refreshQr,
            )

            Spacer(Modifier.height(16.dp))

            QrStatusRow(uiState = uiState)

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "二维码有效期剩余 ${formatCountdown(uiState.remainSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            TextButton(onClick = viewModel::refreshQr) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("刷新二维码")
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            ManualCookieSection(uiState = uiState, viewModel = viewModel)

            Spacer(Modifier.height(16.dp))

            Text(
                text = "登录信息（SESSDATA 等）使用 AndroidX EncryptedSharedPreferences 加密保存在本机，" +
                    "不会上传到任何第三方服务器。退出登录会彻底清空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QrPanel(
    uiState: LoginUiState,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(232.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val image = uiState.qrImage
        if (image != null && uiState.status != QrStatus.Loading) {
            Image(
                bitmap = image,
                contentDescription = "B 站登录二维码",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            )
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        if (uiState.status == QrStatus.Expired || uiState.status == QrStatus.Error) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.74f))
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (uiState.status == QrStatus.Error) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.Outlined.Refresh
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = if (uiState.status == QrStatus.Error) "加载失败" else "二维码已过期",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "点击刷新",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (uiState.status == QrStatus.Success) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.74f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = "登录成功",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QrStatusRow(uiState: LoginUiState) {
    val (icon, tint) = when (uiState.status) {
        QrStatus.Loading -> Icons.Outlined.Refresh to MaterialTheme.colorScheme.onSurfaceVariant
        QrStatus.WaitingScan -> Icons.Outlined.QrCodeScanner to MaterialTheme.colorScheme.primary
        QrStatus.ScannedNotConfirmed -> Icons.Outlined.HowToReg to MaterialTheme.colorScheme.tertiary
        QrStatus.Success -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        QrStatus.Expired -> Icons.Outlined.Refresh to MaterialTheme.colorScheme.error
        QrStatus.Error -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            text = uiState.statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.status == QrStatus.Error || uiState.status == QrStatus.Expired) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ManualCookieSection(
    uiState: LoginUiState,
    viewModel: LoginViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleManualInput() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("扫码不方便？手动粘贴 Cookie", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "支持粘贴整段 Cookie，或只粘贴 SESSDATA 的值",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (uiState.showManualInput) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = if (uiState.showManualInput) "收起" else "展开",
                )
            }

            if (uiState.showManualInput) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = uiState.manualCookie,
                        onValueChange = viewModel::onManualCookieChange,
                        label = { Text("Cookie 或 SESSDATA") },
                        placeholder = {
                            Text(
                                "SESSDATA=xxxx%2Cxxxx; bili_jct=xxxx; DedeUserID=123456",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::submitManualCookie,
                        enabled = !uiState.submittingManual && uiState.manualCookie.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.submittingManual) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("正在校验…")
                        } else {
                            Text("用 Cookie 登录")
                        }
                    }
                    TextButton(
                        onClick = viewModel::toggleHowToGetCookie,
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text(if (uiState.showHowToGetCookie) "收起获取方法" else "如何获取 Cookie？")
                    }
                    if (uiState.showHowToGetCookie) {
                        Text(
                            text = "1. 电脑浏览器打开并登录 bilibili.com\n" +
                                "2. 按 F12 打开开发者工具\n" +
                                "3. 选择 Application（应用）标签\n" +
                                "4. 左侧 Storage → Cookies → https://www.bilibili.com\n" +
                                "5. 找到 SESSDATA，复制它的 Value 粘到上面\n" +
                                "   （也可以把整行 Cookie 一起复制，本 App 会自动解析）\n" +
                                "6. 点「用 Cookie 登录」\n\n" +
                                "注意：Cookie 等同于账号密码，不要发给任何人。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatCountdown(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return "%d:%02d".format(minutes, seconds)
}
