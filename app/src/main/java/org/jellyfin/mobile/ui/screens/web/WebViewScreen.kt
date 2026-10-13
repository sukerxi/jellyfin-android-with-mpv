package org.jellyfin.mobile.ui.screens.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import org.jellyfin.mobile.ui.dialog.WebViewDialogState
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.webapp.JellyfinWebChromeClient
import org.jellyfin.mobile.webapp.JellyfinWebViewClient
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.koin.compose.koinInject
import android.os.PowerManager
import androidx.core.content.getSystemService
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    server: ServerEntity,
    mainViewModel: MainViewModel,
    onSelectServer: (error: Boolean) -> Unit,
    appPreferences: AppPreferences = koinInject(),
    webappFunctionChannel: WebappFunctionChannel = koinInject(),
    webViewDialogState: WebViewDialogState = koinInject(),
    nativePlayer: NativePlayer = koinInject(),
    mediaSegments: MediaSegments = koinInject(),
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var connected by remember(server.id) { mutableStateOf(false) }
    var showLoading by remember(server.id) { mutableStateOf(false) }
    var showBatterySnackbar by remember(server.id) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // External file chooser
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
        fileChooserCallback = null
    }

    // External video player apps
    lateinit var externalPlayerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    val externalPlayer = remember {
        ExternalPlayer(context) { intent -> externalPlayerLauncher.launch(intent) }
    }
    externalPlayerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        externalPlayer.onActivityResult(result.resultCode, result.data)
    }

    val assetsPathHandler = remember { AssetsPathHandler(context) }

    // Plain holder: written from AndroidView factory (must not touch snapshot state there)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    val timeoutRunnable = remember(server.id) {
        Runnable {
            connected = false
            onSelectServer(true)
        }
    }
    val showLoadingRunnable = remember(server.id) {
        Runnable { showLoading = true }
    }

    val jellyfinWebViewClient = remember(server.id) {
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
                    connected = true
                    showLoading = false
                    webView.fadeIn()
                    showBatterySnackbar = true
                }
            }

            override fun onErrorReceived() {
                connected = false
                onSelectServer(true)
            }
        }
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
                    visibility = View.INVISIBLE
                    webViewHolder[0] = this

                    // Setup exclusion rects for gestures
                    if (AndroidVersion.isAtLeastQ) {
                        @Suppress("MagicNumber")
                        doOnNextLayout {
                            // Maximum allowed exclusion rect height is 200dp,
                            // offsetting 100dp from the center in both directions
                            // uses the maximum available space
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

                    initialize()
                }
            },
            onRelease = { webView ->
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingRunnable)
                webView.stopLoading()
                webView.destroy()
                webViewHolder[0] = null
            },
        )

        // Loading overlay
        AnimatedVisibility(visible = showLoading, modifier = Modifier.align(Alignment.Center)) {
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding(),
        )
    }

    // While connected let the webapp handle back presses (NavigationHelper.goBack)
    BackHandler(enabled = connected) {
        webappFunctionChannel.goBack()
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
        val result = snackbarHostState.showSnackbar(
            message = batteryMessage,
            actionLabel = okLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite,
        )
        showBatterySnackbar = false
        if (result == SnackbarResult.ActionPerformed) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Timber.e(e)
            }

            // Ignore after the user interacted with the snackbar at least once
            appPreferences.ignoreBatteryOptimizations = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewDialogState.dismiss()
        }
    }
}
