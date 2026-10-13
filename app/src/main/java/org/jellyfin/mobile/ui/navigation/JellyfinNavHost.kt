package org.jellyfin.mobile.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import org.jellyfin.mobile.webapp.WebViewRetainer
import org.koin.compose.koinInject

object Routes {
    const val CONNECT = "connect"
    const val WEB = "web"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val MPV_CONFIG_EDITOR = "mpv_config_editor"
}

@Composable
fun JellyfinNavHost(
    mainViewModel: MainViewModel,
    activityEventHandler: ActivityEventHandler = koinInject(),
    webViewRetainer: WebViewRetainer = koinInject(),
) {
    val navController = rememberNavController()
    val serverState by mainViewModel.serverState.collectAsStateWithLifecycle()
    val appPreferences: AppPreferences = koinInject()

    // WebView 连接失败时，回到选服页并展示外部连接错误；"use different server" 不展示。
    var connectExternalError by rememberSaveable { mutableStateOf(false) }

    // 播放器以全屏浮层覆盖在当前界面之上，而非独立的导航目标（见下方 NavHost 注释）。
    var playerVisible by remember { mutableStateOf(false) }

    // JS 桥 / Connect 屏发起的导航命令
    LaunchedEffect(activityEventHandler) {
        activityEventHandler.navigationEvents.collect { event ->
            when (event) {
                NavigationEvent.OpenSettings -> navController.navigate(Routes.SETTINGS)
                NavigationEvent.OpenDownloads -> navController.navigate(Routes.DOWNLOADS)
                NavigationEvent.LaunchPlayer -> {
                    // 防御：仅 ExoPlayer/mpv 两种内核会进入原生播放器
                    // （PlayerViewModel.setupPlayer() 对其他类型会抛 IllegalArgumentException；
                    // 外置播放器不经此事件，下载页直发 LaunchNativePlayer 时按偏好过滤）。
                    playerVisible = appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER ||
                        appPreferences.videoPlayerType == VideoPlayerType.MPV_PLAYER
                }
            }
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

    // 播放器作为浮层而非独立路由：旧版以 add Fragment 的方式叠加，WebView 始终存活，
    // 退出播放后回到原页面；推入独立导航目标会销毁 WebViewScreen（AndroidView.onRelease
    // 中 destroy WebView），返回时重新 loadUrl 直跳首页。
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize(),
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
                            // Leaving the web screen for good: drop the retained WebView so the
                            // next connection starts from a fresh page load
                            webViewRetainer.destroy()
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
        }

        if (playerVisible) {
            // 系统返回优先关闭播放器（与旧版 PlayerFragment 叠加在 WebViewFragment 之上时
            // 的返回行为一致）。此回调注册晚于 WebViewScreen 的返回处理，优先级更高；
            // PlayerScreen 内部的面板返回处理注册更晚，优先关闭面板再关播放器。
            BackHandler { playerVisible = false }
            PlayerScreen(
                onClose = { playerVisible = false },
            )
        }
    }
}
