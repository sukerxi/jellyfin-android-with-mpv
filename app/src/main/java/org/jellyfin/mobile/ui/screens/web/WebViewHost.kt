package org.jellyfin.mobile.ui.screens.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.core.view.doOnNextLayout
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.ui.components.SnackbarController
import org.jellyfin.mobile.ui.dialog.WebViewDialogState
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.utils.koinInject
import org.jellyfin.mobile.webapp.JellyfinWebChromeClient
import org.jellyfin.mobile.webapp.JellyfinWebViewClient
import org.jellyfin.mobile.webapp.WebLoadFailure
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import timber.log.Timber

/**
 * 常驻的网页宿主。
 *
 * 网页不再是某个导航目的地的内容，而是覆盖在导航层**之下**、与主界面同寿命的宿主：
 * 进入设置/下载页只是关掉它的可见性，返回时再打开。
 *
 * 为什么必须常驻：WebView 一旦脱离窗口（目的地销毁组合），Chromium 侧会停止出帧并
 * 丢掉最后一帧；重新挂回窗口时补帧请求又常常落在窗口还不可见的时刻被丢弃，于是停在
 * 空帧上 —— 表现就是"从设置页返回后网页全黑，点一下屏幕才恢复"。可见性变化
 * （[View.setVisibility]）是 Chromium 明确监听、能确定性恢复出帧的信号，所以路由
 * 进出只切可见性，绝不让 WebView detach/re-attach。
 *
 * 页面状态（SPA 路由、滚动位置、JS 上下文）也因此不需要"保留/复用"逻辑：实例与
 * 页面同寿命，只有切换服务器时才随 [server] 的 id 整体重建（旧实例在 onRelease 销毁）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewHost(
    server: ServerEntity,
    state: WebViewHostState,
    visible: Boolean,
    mainViewModel: MainViewModel,
    onSelectServer: (error: Boolean) -> Unit,
    appPreferences: AppPreferences = koinInject(),
    webappFunctionChannel: WebappFunctionChannel = koinInject(),
    snackbarController: SnackbarController = koinInject(),
    webViewDialogState: WebViewDialogState = koinInject(),
    nativePlayer: NativePlayer = koinInject(),
    mediaSegments: MediaSegments = koinInject(),
) {
    // WebView、已加载的页面和连接状态都属于当前这台服务器：换服必须整体重建，
    // 由旧组合的 onRelease 销毁旧实例。
    key(server.id) {
        WebViewHostContent(
            server = server,
            state = state,
            visible = visible,
            mainViewModel = mainViewModel,
            onSelectServer = onSelectServer,
            appPreferences = appPreferences,
            webappFunctionChannel = webappFunctionChannel,
            snackbarController = snackbarController,
            webViewDialogState = webViewDialogState,
            nativePlayer = nativePlayer,
            mediaSegments = mediaSegments,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewHostContent(
    server: ServerEntity,
    state: WebViewHostState,
    visible: Boolean,
    mainViewModel: MainViewModel,
    onSelectServer: (error: Boolean) -> Unit,
    appPreferences: AppPreferences,
    webappFunctionChannel: WebappFunctionChannel,
    snackbarController: SnackbarController,
    webViewDialogState: WebViewDialogState,
    nativePlayer: NativePlayer,
    mediaSegments: MediaSegments,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 在组合期读取，保证连接状态变化能驱动重组（AndroidView 的 update 不宜作为状态订阅点）。
    val connected = state.connected

    var showLoading by remember { mutableStateOf(false) }
    var showBatterySnackbar by remember { mutableStateOf(false) }
    // 主框架加载失败（断网/5xx/证书/超时）。非 null 时用覆盖层替代网页，
    // 可重试的错误原地重试，不可恢复的（证书）只给「换个服务器」。
    var loadFailure by remember { mutableStateOf<WebLoadFailure?>(null) }

    // Plain holder: written from AndroidView factory (must not touch snapshot state there)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    // 每个 WebView 实例只在首次出现（连接成功）时淡入一次；之后路由进出只切可见性，
    // 不再给 WebView 套 alpha 动画（alpha 层合成是另一类黑帧来源）。
    val shownOnce = remember { booleanArrayOf(false) }

    // External file chooser
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
        fileChooserCallback = null
    }

    // External video player apps
    lateinit var externalPlayerLauncher: ActivityResultLauncher<Intent>
    val externalPlayer = remember {
        ExternalPlayer(context) { intent -> externalPlayerLauncher.launch(intent) }
    }
    externalPlayerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        externalPlayer.onActivityResult(result.resultCode, result.data)
    }

    val assetsPathHandler = remember { AssetsPathHandler(context) }

    val timeoutRunnable = remember {
        Runnable {
            state.connected = false
            // 超时后必须同时收掉加载圈。否则错误页与半透明加载态叠在屏幕正中：
            // 正文被按钮压住，还会看到两个「换服务器」按钮（各来自一层）。
            showLoading = false
            // 超时不再直接清服务器会话：先让用户就地重试
            loadFailure = WebLoadFailure(WebLoadFailure.Kind.TIMEOUT)
        }
    }
    val showLoadingRunnable = remember { Runnable { showLoading = true } }

    fun reloadWebapp() {
        val webView = webViewHolder[0] ?: return
        loadFailure = null
        showLoading = false
        state.connected = false
        webView.removeCallbacks(timeoutRunnable)
        webView.removeCallbacks(showLoadingRunnable)
        webView.loadUrl("${server.hostname.trimEnd('/')}/")
        webView.postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
        webView.postDelayed(showLoadingRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
    }

    val jellyfinWebViewClient = remember {
        object : JellyfinWebViewClient(
            scope,
            server,
            assetsPathHandler,
            mainViewModel,
        ) {
            override fun onConnectedToWebapp() {
                val webView = webViewHolder[0] ?: return
                // shouldInterceptRequest runs on a WebView background thread,
                // so all WebView/View calls and Compose state writes must be
                // dispatched to the main thread
                webView.post {
                    webView.removeCallbacks(timeoutRunnable)
                    webView.removeCallbacks(showLoadingRunnable)
                    state.connected = true
                    showLoading = false
                    loadFailure = null
                    showBatterySnackbar = true
                }
            }

            override fun onLoadFailed(failure: WebLoadFailure) {
                val webView = webViewHolder[0]
                if (webView == null) {
                    onSelectServer(failure.retryable)
                    return
                }
                webView.post {
                    webView.removeCallbacks(timeoutRunnable)
                    webView.removeCallbacks(showLoadingRunnable)
                    state.connected = false
                    showLoading = false
                    loadFailure = failure
                }
            }
        }
    }

    // 绑定客户端与 JS 桥
    fun WebView.setupClients() {
        webViewClient = jellyfinWebViewClient
        webChromeClient = JellyfinWebChromeClient(
            object : JellyfinWebChromeClient.FileChooserListener {
                override fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>) {
                    fileChooserCallback = filePathCallback
                    fileChooserLauncher.launch(intent)
                }
            },
        )
        settings.applyDefault()
        addJavascriptInterface(NativeInterface(context), "NativeInterface")
        addJavascriptInterface(nativePlayer, "NativePlayer")
        addJavascriptInterface(externalPlayer, "ExternalPlayer")
        addJavascriptInterface(mediaSegments, "MediaSegments")
    }

    fun WebView.initialize() {
        if (!appPreferences.ignoreWebViewChecks && isOutdated()) { // Check WebView version
            // 由根 Compose 宿主渲染 M3 对话框
            val webView = this
            val webViewPackageName = WebViewCompat.getCurrentWebViewPackage(context)?.packageName
            webViewDialogState.showOutdatedWebViewDialog(webViewPackageName) {
                appPreferences.ignoreWebViewChecks = true
                // Re-initialize
                webView.initialize()
            }
            return
        }
        setupClients()
        loadUrl("${server.hostname.trimEnd('/')}/")
        postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
        postDelayed(showLoadingRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // 先隐藏，等 webapp 连接成功的第一次出现再淡入
                    visibility = View.INVISIBLE
                    webViewHolder[0] = this
                    setupGestureExclusions()
                    initialize()
                }
            },
            update = { webView ->
                // 只有 WEB 路由在栈顶、且页面已连接时才显示。播放器浮层不改变路由，
                // 网页保持可见：播放中 webapp 要继续上报进度，也不能吃
                // document.visibilityState=hidden 的定时器节流。
                //
                // INVISIBLE -> VISIBLE 是 Chromium 明确响应的信号（AwContents 收到后
                // 通知渲染侧 WasShown 并重出新帧），也是这里唯一依赖的补帧机制。
                val show = visible && connected
                when {
                    !show -> webView.visibility = View.INVISIBLE
                    !shownOnce[0] -> {
                        shownOnce[0] = true
                        webView.fadeIn()
                    }
                    else -> webView.visibility = View.VISIBLE
                }
            },
            onRelease = { webView ->
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingRunnable)
                webViewHolder[0] = null
                // 常驻宿主只会在换服或应用退出时被释放，实例寿命到此为止。
                webView.destroy()
            },
        )

        // 浮层与提示只在宿主可见时挂载，否则会画在设置页/下载页上面。
        if (visible) {
            // 主框架加载失败：整屏替换，给出原因与出口
            loadFailure?.let { failure ->
                WebViewLoadErrorOverlay(
                    failure = failure,
                    onRetry = { reloadWebapp() },
                    onSelectOtherServer = { onSelectServer(false) },
                )
            }

            // Loading overlay
            // 错误页出现时不再画加载圈：两层都居中，叠起来会互相遮挡
            // （曾经的表现为正文被按钮压住、屏幕上出现两个「换服务器」按钮）。
            AnimatedVisibility(
                visible = showLoading && loadFailure == null,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    modifier = Modifier.systemBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        webViewHolder[0]?.let { webView ->
                            webView.removeCallbacks(timeoutRunnable)
                            webView.stopLoading()
                        }
                        showLoading = false
                        onSelectServer(false)
                    }) {
                        Text(text = stringResource(R.string.button_use_different_server))
                    }
                }
            }

        }
    }

    // Process JS functions called from other components (e.g. the PlayerService)
    DisposableEffect(webappFunctionChannel) {
        val job = scope.launch {
            for (function in webappFunctionChannel) {
                webViewHolder[0]?.evaluateJavascript(function, null)
            }
        }
        onDispose { job.cancel() }
    }

    // Battery optimization reminder after the webapp connected
    val batteryMessage = stringResource(R.string.battery_optimizations_message)
    val okLabel = stringResource(android.R.string.ok)
    LaunchedEffect(showBatterySnackbar) {
        if (!showBatterySnackbar) return@LaunchedEffect
        val powerManager = context.getSystemService(Activity.POWER_SERVICE) as? PowerManager
        if (
            appPreferences.ignoreBatteryOptimizations ||
            powerManager == null ||
            powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)
        ) {
            showBatterySnackbar = false
            return@LaunchedEffect
        }
        showBatterySnackbar = false
        snackbarController.show(
            text = batteryMessage,
            actionLabel = okLabel,
            duration = SnackbarDuration.Indefinite,
            action = {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    Timber.e(e)
                }

                // Ignore after the user interacted with the snackbar at least once
                appPreferences.ignoreBatteryOptimizations = true
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewDialogState.dismiss()
        }
    }
}

/**
 * 网页加载失败整屏提示。
 *
 * 与旧的「一句 toast + 退回选服页」相比：
 * - 可重试的失败（断网、超时、5xx、部分 4xx）就地给「重试」，不必重新选服务器；
 * - 不可恢复的失败（证书）才引导换服务器，并明确说明原因；
 * - 保留服务器地址，用户能确认自己连的是哪台机器。
 */
@Composable
private fun WebViewLoadErrorOverlay(
    failure: WebLoadFailure,
    onRetry: () -> Unit,
    onSelectOtherServer: () -> Unit,
) {
    val message = when (failure.kind) {
        WebLoadFailure.Kind.TIMEOUT -> stringResource(R.string.web_error_message_timeout)
        WebLoadFailure.Kind.SSL -> stringResource(R.string.web_error_message_ssl)
        WebLoadFailure.Kind.HTTP -> stringResource(
            R.string.web_error_message_http,
            failure.httpStatus ?: 0,
        )
        WebLoadFailure.Kind.NETWORK -> stringResource(R.string.web_error_message_generic)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // 200% 字号下「标题 + 原因 + 两个按钮」会高过一屏，居中布局会把首尾切掉；
            // fillMaxSize + 垂直滚动：空间够时仍然居中，不够时可以滚到底。
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(
                    if (failure.retryable) R.string.web_error_title_retryable else R.string.web_error_title_fatal,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!failure.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = failure.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            if (failure.retryable) {
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            TextButton(onClick = onSelectOtherServer) {
                Text(text = stringResource(R.string.button_use_different_server))
            }
        }
    }
}

/**
 * 把屏幕左侧边缘的返回手势区域让给 app。
 *
 * 网页横向滚动与系统返回手势在左边缘上重叠，不作排除时右滑返回会误触发。
 */
private fun WebView.setupGestureExclusions() {
    if (!AndroidVersion.isAtLeastQ) return
    doOnNextLayout {
        // Maximum allowed exclusion rect height is 200dp,
        // offsetting 100dp from the center in both directions uses the maximum available space
        val verticalCenter = measuredHeight / 2
        val offset = resources.dip(100)

        // Arbitrary, currently 2x minimum touch target size
        val exclusionWidth = resources.dip(96)

        systemGestureExclusionRects = listOf(
            Rect(
                0,
                verticalCenter - offset,
                exclusionWidth,
                verticalCenter + offset,
            ),
        )
    }
}
