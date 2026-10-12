package org.jellyfin.mobile.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.bridge.JavascriptCallback
import org.jellyfin.mobile.player.interaction.PlaybackLauncher
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.utils.requestDownload
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import timber.log.Timber

class ActivityEventHandler(
    private val webappFunctionChannel: WebappFunctionChannel,
    private val playbackLauncher: PlaybackLauncher,
) {
    private val eventsFlow = MutableSharedFlow<ActivityEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * 导航命令流，由根 NavHost 在组合中 collect。
     */
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    fun MainActivity.subscribe(mainViewModel: MainViewModel) {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                eventsFlow.collect { event ->
                    handleEvent(event, mainViewModel)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun MainActivity.handleEvent(event: ActivityEvent, mainViewModel: MainViewModel) {
        when (event) {
            is ActivityEvent.ChangeFullscreen -> {
                val fullscreenHelper = PlayerFullscreenHelper(window)
                if (event.isFullscreen) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    fullscreenHelper.enableFullscreen()
                    window.setBackgroundDrawable(null)
                } else {
                    // Reset screen orientation
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    fullscreenHelper.disableFullscreen()
                    // Reset window background color
                    window.setBackgroundDrawableResource(R.color.theme_background)
                }
            }
            is ActivityEvent.LaunchNativePlayer -> {
                playbackLauncher.launch(event.playOptions, event.preferences)
                _navigationEvents.tryEmit(NavigationEvent.LaunchPlayer)
            }
            is ActivityEvent.OpenUrl -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, event.uri.toUri())
                    if (event.grantReadPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Timber.e("openIntent: %s", e.message)
                }
            }
            is ActivityEvent.DownloadItems -> {
                lifecycleScope.launch {
                    requestDownload(this@handleEvent, mainViewModel, itemIds = event.itemIds)
                }
            }
            ActivityEvent.OpenDownloads -> {
                _navigationEvents.tryEmit(NavigationEvent.OpenDownloads)
            }
            is ActivityEvent.CastMessage -> {
                val action = event.action
                chromecast.execute(
                    action,
                    event.args,
                    object : JavascriptCallback() {
                        override fun callback(keep: Boolean, err: String?, result: String?) {
                            webappFunctionChannel.call(
                                """window.NativeShell.castCallback("$action", $keep, $err, $result);""",
                            )
                        }
                    },
                )
            }
            ActivityEvent.RequestBluetoothPermission -> {
                lifecycleScope.launch {
                    bluetoothPermissionHelper.requestBluetoothPermissionIfNecessary()
                }
            }
            ActivityEvent.OpenSettings -> {
                _navigationEvents.tryEmit(NavigationEvent.OpenSettings)
            }
            ActivityEvent.SelectServer -> {
                mainViewModel.resetServer()
            }
            ActivityEvent.ExitApp -> {
                if (serviceBinder?.isPlaying == true) {
                    moveTaskToBack(false)
                } else {
                    finish()
                }
            }
        }
    }

    fun emit(event: ActivityEvent) {
        eventsFlow.tryEmit(event)
    }
}
