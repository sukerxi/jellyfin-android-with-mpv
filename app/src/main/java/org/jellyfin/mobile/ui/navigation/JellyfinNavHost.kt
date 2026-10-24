package org.jellyfin.mobile.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import org.jellyfin.mobile.ui.screens.web.WebViewHost
import org.jellyfin.mobile.ui.screens.web.WebViewHostState
import org.jellyfin.mobile.utils.koinInject
import org.jellyfin.mobile.webapp.WebappFunctionChannel

object Routes {
    const val CONNECT = "connect"
    const val WEB = "web"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val MPV_CONFIG_EDITOR = "mpv_config_editor"
}

/**
 * 应用主框架：常驻网页宿主 + 导航层 + 播放器浮层。
 *
 * 层序（自下而上）：
 * 1. [WebViewHost]：网页与主界面同寿命，路由切换只切可见性，永不 detach；
 * 2. [NavHost]：`web` 是空壳目的地，只为占住返回栈位置；设置/下载等页面不透明，
 *    盖在网页之上；
 * 3. [PlayerScreen]：全屏浮层，覆盖在一切之上。
 *
 * 返回键在整个应用只注册一次（本文件末尾），语义固定为：
 * 登记者（播放器浮层、下载页多选）→ webapp 内后退 → 导航栈弹出 → 系统。
 */
@Composable
fun JellyfinNavHost(
    mainViewModel: MainViewModel,
    activityEventHandler: ActivityEventHandler = koinInject(),
    webappFunctionChannel: WebappFunctionChannel = koinInject(),
) {
    val navController = rememberNavController()
    val serverState by mainViewModel.serverState.collectAsStateWithLifecycle()
    val appPreferences: AppPreferences = koinInject()

    // WebView 连接失败时，回到选服页并展示外部连接错误；"use different server" 不展示。
    var connectExternalError by rememberSaveable { mutableStateOf(false) }

    // 播放器以全屏浮层覆盖在当前界面之上，而非独立的导航目标（见下方 NavHost 注释）。
    var playerVisible by remember { mutableStateOf(false) }

    val server = (serverState as? ServerState.Available)?.server

    // 常驻宿主的连接状态：与 WebView 实例同寿命（随 server.id 重建），
    // 返回键仲裁点用它判断"返回是否该交给 webapp"。
    val webViewState = remember(server?.id) { WebViewHostState() }

    // 返回键的唯一仲裁点，见 [BackNavigationDispatcher]。
    val backDispatcher = remember { BackNavigationDispatcher() }

    val currentEntry by navController.currentBackStackEntryAsState()
    // 网页只在 web 路由位于栈顶时显示。播放器浮层不改变路由，页面因此保持可见：
    // 播放中 webapp 仍要上报进度，也不能吃 document.visibilityState=hidden 的节流。
    val webVisible = currentEntry?.destination?.route == Routes.WEB

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

    Box(modifier = Modifier.fillMaxSize()) {
        // 常驻网页宿主，位于导航层之下。路由切换只是切可见性（WebView 留在窗口里），
        // 这是"返回网页偶发全黑、点一下才恢复"在结构上的根治点，详见 [WebViewHost]。
        if (server != null) {
            WebViewHost(
                server = server,
                state = webViewState,
                visible = webVisible,
                mainViewModel = mainViewModel,
                onSelectServer = { error ->
                    connectExternalError = error
                    // 离开网页：server 变为 null，宿主组合随之销毁并释放 WebView，
                    // 下次连接一定从新页面开始。
                    mainViewModel.resetServer()
                },
            )
        }

        NavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize(),
            startDestination = Routes.CONNECT,
            // 默认 700ms 淡入淡出必须关掉：web 已是空壳，转场只会让设置页在半透明
            // 阶段露出下方的网页，也把"路由切换"拉成一段可见性竞态窗口。
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    mainViewModel = mainViewModel,
                    showExternalConnectionError = connectExternalError,
                )
            }
            // 空壳目的地：网页由 NavHost 下方的常驻宿主渲染。它仍然需要存在 ——
            // 它占住返回栈位置（设置/下载页 pop 回来就是它），也是"网页在栈顶"的依据。
            composable(Routes.WEB) { }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    onBackPressed = { navController.popBackStack() },
                    backDispatcher = backDispatcher,
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
            PlayerScreen(
                onClose = { playerVisible = false },
                backDispatcher = backDispatcher,
            )
        }

        // 全应用唯一的 BackHandler。
        //
        // 必须声明在 NavHost 与浮层之后：OnBackPressedDispatcher 里后注册者优先，
        // NavController 的返回回调是在组合 NavHost 时注册的，声明在前面会被它抢先。
        //
        // enabled 为 false 时不拦截，返回自然落到 NavController（弹栈）或系统（退出）。
        BackHandler(
            enabled = playerVisible ||
                backDispatcher.hasHandlers ||
                (webVisible && webViewState.connected),
        ) {
            // 优先级 1：界面自己登记的语义（播放器面板/关闭、下载页多选）。
            if (backDispatcher.dispatchBack()) return@BackHandler
            // 优先级 2：网页在栈顶且已连接 —— 交还 webapp 处理 SPA 内后退。
            if (webVisible && webViewState.connected) {
                webappFunctionChannel.goBack()
                return@BackHandler
            }
            // 优先级 3：没有可处理的语义（浮层刚组合、处理器尚未登记）——吞掉，
            // 不把按键误传给下方页面，也避免误退出应用。
        }
    }
}
