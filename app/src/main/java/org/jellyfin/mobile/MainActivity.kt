package org.jellyfin.mobile

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.cast.Chromecast
import org.jellyfin.mobile.player.cast.IChromecast
import org.jellyfin.mobile.player.interaction.PlaybackController
import org.jellyfin.mobile.ui.components.AppDialogHost
import org.jellyfin.mobile.ui.navigation.JellyfinNavHost
import org.jellyfin.mobile.ui.theme.JellyfinTheme
import org.jellyfin.mobile.update.UpdateCheckResult
import org.jellyfin.mobile.update.UpdateChecker
import org.jellyfin.mobile.update.UpdateDialogStateHolder
import org.jellyfin.mobile.update.UpdateDownloadWorker
import org.jellyfin.mobile.utils.BluetoothPermissionHelper
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.isWebViewSupported
import org.jellyfin.mobile.webapp.RemotePlayerService
import org.jellyfin.mobile.webapp.WebViewRetainer
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 阶段 5：单 Activity（ComponentActivity），全部界面由 navigation-compose 承载，
 * 播放器控制层也已 Compose 化（见 ui/screens/player/PlayerScreen.kt）。
 */
class MainActivity : ComponentActivity() {
    private val activityEventHandler: ActivityEventHandler = get()
    val mainViewModel: MainViewModel by viewModel()
    val bluetoothPermissionHelper: BluetoothPermissionHelper = BluetoothPermissionHelper(this, get())
    val chromecast: IChromecast = Chromecast()
    private val permissionRequestHelper: PermissionRequestHelper by inject()
    private val appPreferences: AppPreferences by inject()
    private val updateChecker: UpdateChecker by inject()
    private val updateDialogState: UpdateDialogStateHolder by inject()
    private val playbackController: PlaybackController by inject()
    private val webViewRetainer: WebViewRetainer by inject()

    /**
     * 当前设备不支持 WebView 时置位，由根 Compose 宿主渲染不可取消的提示框。
     */
    private var webViewUnsupported by mutableStateOf(false)

    var serviceBinder: RemotePlayerService.ServiceBinder? = null
        private set
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            serviceBinder = binder as? RemotePlayerService.ServiceBinder
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            serviceBinder = null
        }
    }

    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            JellyfinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        JellyfinNavHost(mainViewModel = mainViewModel)

                        // 全局 Compose 对话框宿主（更新/蓝牙权限/WebView 提示）
                        AppDialogHost(
                            bluetoothHelper = bluetoothPermissionHelper,
                            isWebViewUnsupported = { webViewUnsupported },
                            onCloseApp = { finishAfterTransition() },
                        )
                    }
                }
            }
        }

        // Check WebView support
        if (!isWebViewSupported()) {
            webViewUnsupported = true
            return
        }

        // Bind player service
        bindService(Intent(this, RemotePlayerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)

        // Subscribe to activity events
        with(activityEventHandler) { subscribe(mainViewModel) }

        // Setup Chromecast
        chromecast.initializePlugin(this)

        // Check for updates (at most once per 24h)
        maybeCheckForUpdates()
    }

    private fun maybeCheckForUpdates() {
        if (!appPreferences.autoCheckUpdates) return
        val now = System.currentTimeMillis()
        if (now - appPreferences.lastUpdateCheckTime < AUTO_UPDATE_CHECK_INTERVAL_MS) return
        appPreferences.lastUpdateCheckTime = now

        lifecycleScope.launch {
            when (val result = updateChecker.check()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    if (appPreferences.silentUpdateDownload) {
                        UpdateDownloadWorker.enqueue(this@MainActivity, result.info)
                    } else if (!isFinishing && !isDestroyed) {
                        updateDialogState.requestUpdate(
                            result.info,
                            allowDismiss = true,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionRequestHelper.handleRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 阶段 5：播放器已 Compose 化，PiP 入口经 PlaybackController 桥接
        playbackController.notifyUserLeaveHint()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        chromecast.destroy()
        // Destroy the WebView retained while navigating to other routes (e.g. the player)
        webViewRetainer.destroy()
        super.onDestroy()
    }
}
