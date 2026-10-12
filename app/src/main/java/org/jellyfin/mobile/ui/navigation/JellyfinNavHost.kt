package org.jellyfin.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.ServerState
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.events.NavigationEvent
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.ui.screens.connect.ConnectScreen
import org.jellyfin.mobile.ui.screens.downloads.DownloadsScreen
import org.jellyfin.mobile.ui.screens.player.PlayerScreen
import org.jellyfin.mobile.ui.screens.settings.MpvConfigEditorScreen
import org.jellyfin.mobile.ui.screens.settings.SettingsScreen
import org.jellyfin.mobile.ui.screens.web.WebViewScreen
import org.koin.compose.koinInject

object Routes {
    const val CONNECT = "connect"
    const val WEB = "web"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val MPV_CONFIG_EDITOR = "mpv_config_editor"
    const val PLAYER = "player"
}

@Composable
fun JellyfinNavHost(
    mainViewModel: MainViewModel,
    activityEventHandler: ActivityEventHandler = koinInject(),
) {
    val navController = rememberNavController()
    val serverState by mainViewModel.serverState.collectAsStateWithLifecycle()

    // WebView 连接失败时，回到选服页并展示外部连接错误；"use different server" 不展示。
    var connectExternalError by rememberSaveable { mutableStateOf(false) }

    // JS 桥 / Connect 屏发起的导航命令
    LaunchedEffect(activityEventHandler) {
        activityEventHandler.navigationEvents.collect { event ->
            val route = when (event) {
                NavigationEvent.OpenSettings -> Routes.SETTINGS
                NavigationEvent.OpenDownloads -> Routes.DOWNLOADS
                NavigationEvent.LaunchPlayer -> Routes.PLAYER
            }
            navController.navigate(route)
        }
    }

    // serverState 驱动 connect <-> web 的切换（与旧 Fragment replace 行为一致）
    LaunchedEffect(serverState) {
        when (val state = serverState) {
            is ServerState.Available -> {
                if (navController.currentDestination?.route != Routes.WEB) {
                    navController.navigate(Routes.WEB) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = true
                        }
                    }
                }
                connectExternalError = false
            }
            ServerState.Unset -> {
                if (navController.currentDestination?.route == Routes.WEB) {
                    navController.navigate(Routes.CONNECT) {
                        popUpTo(Routes.WEB) { inclusive = true }
                    }
                }
            }
            ServerState.Pending -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.CONNECT,
    ) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                mainViewModel = mainViewModel,
                showExternalConnectionError = connectExternalError,
            )
        }
        composable(Routes.WEB) {
            val server = (serverState as? ServerState.Available)?.server
            if (server != null) {
                WebViewScreen(
                    server = server,
                    mainViewModel = mainViewModel,
                    onSelectServer = { error ->
                        connectExternalError = error
                        mainViewModel.resetServer()
                    },
                )
            }
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onBackPressed = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenMpvConfigEditor = { navController.navigate(Routes.MPV_CONFIG_EDITOR) },
            )
        }
        composable(Routes.MPV_CONFIG_EDITOR) {
            MpvConfigEditorScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PLAYER) {
            // 阶段 5：播放器控制层 Compose 化，临时 FragmentHost 已移除。
            // 防御：仅 ExoPlayer/mpv 两种内核会进入原生播放路由（PlayerViewModel 构造时
            // setupPlayer() 对其他类型会抛 IllegalArgumentException；外置播放器不走此路由）。
            val appPreferences: AppPreferences = koinInject()
            val nativePlayerType = appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER ||
                appPreferences.videoPlayerType == VideoPlayerType.MPV_PLAYER
            if (nativePlayerType) {
                PlayerScreen(
                    onClose = {
                        if (navController.currentDestination?.route == Routes.PLAYER) {
                            navController.popBackStack()
                        }
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
}
