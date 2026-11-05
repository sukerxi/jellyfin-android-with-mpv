package org.jellyfin.mobile.ui.screens.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlaybackLauncher
import org.jellyfin.mobile.player.interaction.PlaybackRequest
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.player.ui.TrickplayHelper
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.isOnCellularNetwork
import org.jellyfin.mobile.webapp.WebappFunctionChannel

/**
 * 一次播放会话的规则：起播 / 关闭 / PiP / 方向 / 亮度。
 *
 * 与 3a、3b 一样，状态仍由 PlayerScreen 通过 remember 持有（这里只拿到
 * [PlayerSessionState] 里的 MutableState 引用）；副作用本身（LaunchedEffect /
 * DisposableEffect）也仍留在屏幕里，因为它们必须跟着组合生命周期走。搬出来的是
 * 「事件发生时该改什么」这组规则，逐条对应抽之前的分支，没有新增或删减条件。
 *
 * 宿主能力通过构造参数注入，不持有 Activity 之外的临时组合对象；[onClose] 用
 * [State] 传递，保证 remember 住的本类在回调触发时读到的是最新路由引用。
 */
@Stable
internal class PlayerSessionCoordinator(
    private val viewModel: PlayerViewModel,
    private val activity: ComponentActivity,
    private val window: Window,
    private val appPreferences: AppPreferences,
    private val playbackLauncher: PlaybackLauncher,
    private val playerFullscreenHelper: PlayerFullscreenHelper,
    private val orientationListener: SmartOrientationListener,
    private val trickplayHelper: TrickplayHelper,
    private val webappFunctionChannel: WebappFunctionChannel,
    private val playerViewHolder: Array<PlayerView?>,
    private val state: PlayerSessionState,
    private val onClose: State<() -> Unit>,
) {
    /**
     * Leave the player screen: the web app takes playback over again and the native player
     * is stopped unless the user enabled background playback (or the web app is casting).
     * Every exit path (back button, the toolbar back arrow, the WebView's back handling)
     * goes through here, otherwise the foreground service keeps playing behind the UI.
     */
    fun closePlayer() {
        if (state.closingSoon.value || state.playbackEnded.value) return
        state.closingSoon.value = true
        viewModel.onPlayerClosed()

        // The player has to be gone by the time the session ends: PlayerViewModel.onPlayerClosed
        // only stops it (and only when the user did not opt into background playback), leaving a
        // live player behind for the next session. Binding the next media source to a player
        // that is still attached to the previous one is what made the second "play" of a session
        // run into the state it was left in.
        if (!viewModel.keepPlaybackOnExit()) {
            state.playbackEnded.value = true
        }
        onClose.value()
    }

    /**
     * 真正开始播放：确保播放器已就绪后初始化播放队列。
     *
     * [maxStreamingBitrateOverride] 非空时为移动网络播放：该码率上限会下发到服务端，
     * 强制转码到该码率以下，避免直连原画质偷跑流量。
     */
    suspend fun startPlayback(request: PlaybackRequest, maxStreamingBitrateOverride: Int?) {
        if (viewModel.playerOrNull == null) {
            viewModel.setupPlayer()
        }
        val error = viewModel.queueManager.initializePlaybackQueue(
            request.playOptions,
            request.preferences,
            maxStreamingBitrateOverride,
        )
        if (error != null) {
            // 播放起不来：停在播放器里说明原因并给「重试 / 返回」两个出口。
            // 直接 toast + closePlayer 会让用户在 2 秒后连错误都看不到，
            // 也没有换内核/降清晰度的机会。
            val messageRes = when (error) {
                is PlayerException.InvalidPlayOptions -> R.string.player_error_invalid_play_options
                is PlayerException.NetworkFailure -> R.string.player_error_network_failure
                is PlayerException.UnsupportedContent -> R.string.player_error_unsupported_content
                is PlayerException.PlayerNotAvailable -> R.string.player_error_unspecific_exception
            }
            state.playbackFailure.value = PlaybackFailure(messageRes, request, maxStreamingBitrateOverride)
        } else {
            state.playbackFailure.value = null
        }
    }

    /** 每次会话消费一次播放请求（含移动网络确认分流）。 */
    suspend fun resolveInitialPlayback() {
        val request = playbackLauncher.consume()
        if (request == null) {
            // No pending playback request (e.g. restored to the player route), close
            closePlayer()
            return
        }

        // 移动网络（蜂窝）下的远程播放：开启提示时先等用户选择，否则直接按设定码率播放。
        // 本地下载播放不经过网络，不受影响。
        val mobileDataPlayback = request.playOptions.playFromDownloads != true && activity.isOnCellularNetwork
        if (mobileDataPlayback && appPreferences.mobileDataPlaybackPrompt) {
            state.pendingMobileDataRequest.value = request
        } else {
            startPlayback(request, if (mobileDataPlayback) appPreferences.mobileDataMaxBitrate else null)
        }
    }

    /**
     * 播放器实例出现 / 消失。只有「确实出现过再消失」才算播放自然结束，否则会话刚开始
     * 时 player 合法地为 null（解析播放请求要跑网络往返），会刚打开就被关掉。
     */
    fun onPlayerChanged(player: Player?) {
        if (player != null) {
            state.playbackStarted.value = true
        } else if (state.playbackStarted.value && !state.pipMode.value &&
            !state.closingSoon.value && !state.playbackEnded.value
        ) {
            // Playback ended on its own; the session is over, and PlayerViewModel has already
            // released the player. Dismiss without running the close path again.
            state.playbackEnded.value = true
            onClose.value()
        }
    }

    fun onIsPlayingChanged(playing: Boolean) {
        state.isPlaying.value = playing
    }

    /** 播放/暂停驱动屏幕常亮（暂停时允许系统熄屏）。 */
    fun applyKeepScreenOn() {
        window.keepScreenOn = state.isPlaying.value
    }

    /** Remember brightness setting on player start. */
    fun rememberBrightness() {
        if (appPreferences.exoPlayerRememberBrightness) {
            window.brightness = appPreferences.exoPlayerBrightness
        }
    }

    /** Queue item changes: update fullscreen state, trickplay, menus and subtitle state. */
    fun onMediaSourceChanged(source: JellyfinMediaSource?) {
        if (source != null) {
            if (source.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            state.subtitlesEnabled.value = source.selectedSubtitleStream != null
            trickplayHelper.onMediaSourceChanged(source)
            viewModel.refreshPlayerMenuState()
        }
    }

    /** Keep the fullscreen state in sync with the window insets. */
    fun onWindowInsetsChanged(insets: WindowInsetsCompat): WindowInsetsCompat {
        playerFullscreenHelper.onWindowInsetsChanged(insets)
        state.isFullscreen.value = playerFullscreenHelper.isFullscreen
        return insets
    }

    /** Orientation / lifecycle: orientation listener, fullscreen on resume, close when stopped. */
    fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> orientationListener.enable()
            Lifecycle.Event.ON_STOP -> orientationListener.disable()
            Lifecycle.Event.ON_RESUME -> {
                // When returning from another app, fullscreen mode for landscape orientation has to be set again
                if (isLandscapeOrientation()) {
                    playerFullscreenHelper.enableFullscreen()
                }
                // If playback ended during picture in picture we'll return to the main app when PiP is closed
                if (viewModel.playerOrNull == null && state.playbackStarted.value) {
                    closePlayer()
                }
            }
            else -> Unit
        }
    }

    fun isLandscapeOrientation(): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun updateFullscreenState(configuration: Configuration) {
        // Do not handle any orientation changes while being in Picture-in-Picture mode
        if (AndroidVersion.isAtLeastN && activity.isInPictureInPictureMode) {
            return
        }

        when {
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> {
                // Landscape orientation is always fullscreen
                playerFullscreenHelper.enableFullscreen()
            }
            viewModel.mediaSourceOrNull?.selectedVideoStream?.isLandscape != false -> {
                // Disable fullscreen for landscape video in portrait orientation
                playerFullscreenHelper.disableFullscreen()
            }
        }
    }

    fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean) {
        state.pipMode.value = inPictureInPictureMode
    }

    fun enterPictureInPicture() {
        if (!AndroidVersion.isAtLeastN || viewModel.playerOrNull == null) return
        val playerView = playerViewHolder[0] ?: return

        if (AndroidVersion.isAtLeastO) {
            val aspectRational = viewModel.mediaSourceOrNull?.selectedVideoStream?.aspectRational?.let { rational ->
                when {
                    rational < Constants.PIP_MIN_RATIONAL -> Constants.PIP_MIN_RATIONAL
                    rational > Constants.PIP_MAX_RATIONAL -> Constants.PIP_MAX_RATIONAL
                    else -> rational
                }
            }
            val params = PictureInPictureParams.Builder().apply {
                setAspectRatio(aspectRational)
                val contentFrame: View = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)
                val contentRect = with(contentFrame) {
                    val (x, y) = intArrayOf(0, 0).also(::getLocationInWindow)
                    Rect(x, y, x + width, y + height)
                }
                setSourceRectHint(contentRect)
            }.build()
            activity.enterPictureInPictureMode(params)
        } else {
            @Suppress("DEPRECATION")
            activity.enterPictureInPictureMode()
        }
    }

    /**
     * Reset window state when leaving the player (equivalent of the old onDestroy), and make
     * sure playback is stopped even when the screen is dismissed without going through
     * closePlayer() (e.g. a server switch pops the web route while the overlay is open).
     */
    fun onSessionDispose() {
        if (!state.playbackEnded.value) {
            state.playbackEnded.value = true
            viewModel.endPlayback()
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerFullscreenHelper.disableFullscreen()
        window.brightness = BRIGHTNESS_OVERRIDE_NONE
        // 窗口常亮标志必须显式清除，否则播放中退出后 FLAG_KEEP_SCREEN_ON 会残留，
        // 导致主界面等非播放场景屏幕也不熄屏
        window.keepScreenOn = false
        // 网页端的全屏加载圈不归原生层管：jellyfin-web 的 playbackManager.play() 在
        // 取条目/PlaybackInfo 请求失败时不会隐藏它，用户退出播放器后转圈会继续挂在
        // 网页正中。这里兜底关一次，覆盖「网络问题导致播放没起来」的那条路径。
        webappFunctionChannel.hideWebLoading()
    }
}
