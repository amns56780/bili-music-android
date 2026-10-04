package com.bilimusic.app.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bilimusic.app.data.repository.LoginState
import com.bilimusic.app.ui.components.LoadingState
import com.bilimusic.app.ui.components.MiniPlayer
import com.bilimusic.app.ui.collection.EpisodeSelectionScreen
import com.bilimusic.app.ui.login.LoginScreen
import com.bilimusic.app.ui.local.LocalMusicScreen
import com.bilimusic.app.ui.player.PlayerScreen
import com.bilimusic.app.ui.player.PlayerViewModel
import com.bilimusic.app.ui.playlist.ImportScreen
import com.bilimusic.app.ui.playlist.PlaylistDetailScreen
import com.bilimusic.app.ui.playlist.PlaylistListScreen
import com.bilimusic.app.ui.settings.SettingsScreen

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    BottomTab(Routes.PLAYLISTS, "歌单", Icons.Filled.LibraryMusic),
    BottomTab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

/**
 * App 根容器。
 *
 * 登录态流程（FR-1）：
 * - 启动时校验一次登录态；校验中显示加载态
 * - 未登录 → 起始页是登录页
 * - 登录成功 / 退出登录 → 自动校正导航栈（popUpTo(0)），保证「退出后所有需要鉴权的页面都回到登录页」
 *
 * 迷你播放条（FR-7）：除播放页外所有页面底部常驻，挂在底部导航上方。
 */
@Composable
fun BiliMusicApp(
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val loginState by sessionViewModel.loginState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { sessionViewModel.validateOnce() }

    when (loginState) {
        LoginState.Unknown -> LoadingState(text = "正在校验登录状态…")
        else -> MainNavigation(
            loginState = loginState,
            navController = navController,
            playerViewModel = playerViewModel,
        )
    }
}

@Composable
private fun MainNavigation(
    loginState: LoginState,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val playback by playerViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomTabs.any { it.route == currentRoute }
    val loggedIn = loginState is LoginState.LoggedIn

    // 记住首帧的起始页，避免未登录时先闪一下歌单页
    val startDestination = remember { if (loggedIn) Routes.PLAYLISTS else Routes.LOGIN }

    // 连上播放服务（幂等），让 MiniPlayer 随时有状态
    LaunchedEffect(Unit) { playerViewModel.connect() }

    // FR-8：Android 13+ 在用户第一次真正开始播放时请求通知权限（不在一进 App 就弹）
    val context = LocalContext.current
    var notificationRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 被拒绝也不影响播放，设置页里有「去授权」入口（Phase 5） */ }
    LaunchedEffect(playback.isPlaying) {
        if (playback.isPlaying && !notificationRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationRequested = true
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 登录态变化时校正导航栈
    LaunchedEffect(loggedIn) {
        val route = navController.currentBackStackEntry?.destination?.route
        if (loggedIn) {
            if (route == Routes.LOGIN) {
                navController.navigate(Routes.PLAYLISTS) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else if (route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (playback.hasMedia && currentRoute != Routes.PLAYER) {
                    MiniPlayer(
                        state = playback,
                        onTogglePlay = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onExpand = {
                            navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                        },
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        bottomTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    if (currentRoute != tab.route) {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen()
            }

            composable(Routes.PLAYLISTS) {
                PlaylistListScreen(
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Routes.playlistDetail(playlistId))
                    },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenLocalMusic = { navController.navigate(Routes.LOCAL_MUSIC) },
                )
            }

            composable(Routes.LOCAL_MUSIC) {
                LocalMusicScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = {
                        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.IMPORT) {
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Routes.playlistDetail(playlistId)) {
                            popUpTo(Routes.IMPORT) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenLogin = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(
                route = Routes.PLAYLIST_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ARG_PLAYLIST_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                val playlistId = entry.arguments?.getLong(Routes.ARG_PLAYLIST_ID) ?: -1L
                PlaylistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = {
                        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                    },
                    onOpenCollection = { collectionKey ->
                        navController.navigate(Routes.episodeSelection(playlistId, collectionKey))
                    },
                )
            }

            composable(
                route = Routes.EPISODE_SELECTION,
                arguments = listOf(
                    navArgument(Routes.ARG_PLAYLIST_ID) { type = NavType.LongType },
                    navArgument(Routes.ARG_COLLECTION_KEY) { type = NavType.StringType },
                ),
            ) {
                EpisodeSelectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = {
                        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.PLAYER) {
                PlayerScreen(onCollapse = { navController.popBackStack() })
            }
        }
    }
}
