package org.jellyfin.mobile.ui.screens.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlaybackController
import org.jellyfin.mobile.player.interaction.PlaybackLauncher
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.player.ui.TrickplayHelper
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.lockOrientation
import org.jellyfin.mobile.utils.isAutoRotateOn
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.mobile.utils.koinInject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** 播放位置轮询间隔（毫秒）。 */
private const val POSITION_UPDATE_INTERVAL_MS = 200L

/** seek 后进度条保持目标位置的最长时间（毫秒）；超时后交还给实时轮询位置。 */
private const val SEEK_UI_SETTLE_TIMEOUT_MS = 1000L

/**
 * 阶段 5：播放器界面（Compose 化）。
 *
 * 结构：
 * - [PlayerView] 通过 AndroidView 全屏渲染（factory 只创建一次，update 里绑定/解绑 player）；
 * - 覆盖层全部为 Compose：手势层、控件栏、播放信息/选集面板、锁屏、跳过分段、加载圈；
 * - 平台逻辑保留：`PlayerFullscreenHelper`（沉浸式）、`SmartOrientationListener`（方向）、
 *   PiP 经 [PlaybackController] 由 MainActivity 的 onUserLeaveHint 桥接；
 * - MpvPlayer 的 SurfaceView attach 与 AssSubtitleView 逻辑不变（同一个 PlayerView 实例）。
 */
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
    playbackLauncher: PlaybackLauncher = koinInject(),
    playbackController: PlaybackController = koinInject(),
    appPreferences: AppPreferences = koinInject(),
    qualityOptionsProvider: QualityOptionsProvider = koinInject(),
    assHandler: AssHandler = koinInject(),
) {
    val context = LocalContext.current
    // MainActivity 为 ComponentActivity；PiP 监听注册 API 在 androidx ComponentActivity 上
    val activity = context as ComponentActivity
    val window = activity.window
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ViewModel state
    val player by viewModel.player.observeAsState()
    val playerState by viewModel.playerState.observeAsState()
    val mediaSource by viewModel.queueManager.currentMediaSource.observeAsState()
    val decoderType by viewModel.decoderType.observeAsState()
    val detectedDecoderType by viewModel.detectedDecoderType.observeAsState()
    val chapterMarkings by viewModel.chapterMarkings.collectAsStateWithLifecycle()
    val activeSkipSegment by viewModel.activeAskToSkipSegment.collectAsStateWithLifecycle()

    // UI state
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsToken by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var unlockButtonVisible by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf<PlayerMenuType?>(null) }
    var infoPanelVisible by remember { mutableStateOf(false) }
    var episodePickerVisible by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var gestureSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    // 松手后正在生效的 seek 目标位置：轮询位置追上目标（或超时）后置空
    var pendingSeekPositionMs by remember { mutableStateOf<Long?>(null) }
    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    var subtitlesEnabled by remember { mutableStateOf(false) }
    var episodeItemMap by remember { mutableStateOf<Map<UUID, BaseItemDto>?>(null) }
    var episodeItemsLoaded by remember { mutableStateOf(false) }
    var unlockHideJob by remember { mutableStateOf<Job?>(null) }

    val playerViewHolder = remember { arrayOfNulls<PlayerView>(1) }
    val trickplayHelper = remember(context) { TrickplayHelper(context) }
    val orientationListener = remember(activity) { SmartOrientationListener(activity) }
    val playerFullscreenHelper = remember(window) { PlayerFullscreenHelper(window) }
    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java)!! }

    // region helpers

    fun showControls() {
        controlsVisible = true
        controlsToken++
    }

    fun hideUnlockButton() {
        unlockHideJob?.cancel()
        unlockButtonVisible = false
    }

    fun peekUnlockButton() {
        unlockHideJob?.cancel()
        unlockButtonVisible = true
        unlockHideJob = scope.launch {
            delay(Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
            unlockButtonVisible = false
        }
    }

    fun lockScreen() {
        locked = true
        controlsVisible = false
        orientationListener.disable()
        activity.lockOrientation()
        peekUnlockButton()
    }

    fun unlockScreen() {
        hideUnlockButton()
        if (activity.isAutoRotateOn()) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        orientationListener.enable()
        showControls()
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

    // endregion

    // region effects

    // Handle playback options and start playback (once per composition)
    LaunchedEffect(Unit) {
        val request = playbackLauncher.consume()
        if (request == null) {
            // No pending playback request (e.g. restored to the player route), close
            onClose()
            return@LaunchedEffect
        }
        when (viewModel.queueManager.initializePlaybackQueue(request.playOptions, request.preferences)) {
            is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
            is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
            is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
            null -> Unit // success
        }
    }

    // Automatically close the screen when playback ended, unless we're in PiP mode
    LaunchedEffect(player) {
        if (player == null && !pipMode) {
            onClose()
        }
    }

    // Playback errors are surfaced as toasts
    val errorObserver = remember(viewModel) {
        Observer<String> { message ->
            val safeMessage = message.ifEmpty { context.getString(R.string.player_error_unspecific_exception) }
            context.toast(safeMessage)
        }
    }
    DisposableEffect(viewModel, errorObserver, lifecycleOwner) {
        viewModel.error.observe(lifecycleOwner, errorObserver)
        onDispose { viewModel.error.removeObserver(errorObserver) }
    }

    // Remember brightness setting on player start
    LaunchedEffect(Unit) {
        if (appPreferences.exoPlayerRememberBrightness) {
            window.brightness = appPreferences.exoPlayerBrightness
        }
    }

    // Keep the screen on while playing and poll the playback position
    LaunchedEffect(player) {
        val currentPlayer = player ?: return@LaunchedEffect
        while (isActive) {
            positionMs = currentPlayer.currentPosition
            durationMs = currentPlayer.duration
            bufferedMs = currentPlayer.bufferedPosition
            isPlaying = currentPlayer.isPlaying
            window.keepScreenOn = currentPlayer.isPlaying
            delay(POSITION_UPDATE_INTERVAL_MS)
        }
    }

    // Queue item changes: update fullscreen state, trickplay, menus and subtitle state
    LaunchedEffect(mediaSource) {
        val source = mediaSource
        if (source != null) {
            if (source.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            subtitlesEnabled = source.selectedSubtitleStream != null
            trickplayHelper.onMediaSourceChanged(source)
            viewModel.refreshPlayerMenuState()
        }
    }

    // Keep the fullscreen state in sync with the window insets (also covers transient
    // system-bar reveals), mirroring the old OnApplyWindowInsetsListener on the fragment root
    DisposableEffect(playerFullscreenHelper) {
        val listener = androidx.core.view.OnApplyWindowInsetsListener { _, insets ->
            playerFullscreenHelper.onWindowInsetsChanged(insets)
            isFullscreen = playerFullscreenHelper.isFullscreen
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, listener)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(window.decorView, null) }
    }

    // Orientation / lifecycle: orientation listener, fullscreen on resume, close when stopped
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> orientationListener.enable()
                Lifecycle.Event.ON_STOP -> orientationListener.disable()
                Lifecycle.Event.ON_RESUME -> {
                    // When returning from another app, fullscreen mode for landscape orientation has to be set again
                    if (isLandscapeOrientation()) {
                        playerFullscreenHelper.enableFullscreen()
                    }
                    // If playback ended during picture in picture we'll return to the main app when PiP is closed
                    if (viewModel.playerOrNull == null) {
                        onClose()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Configuration changes (orientation) are handled below, once the gesture
    // handler exists (zoom mode has to be refreshed with the new orientation).

    // Picture-in-Picture mode changes
    DisposableEffect(Unit) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
            if (info.isInPictureInPictureMode) {
                controlsVisible = false
                expandedMenu = null
                infoPanelVisible = false
                episodePickerVisible = false
                hideUnlockButton()
            }
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    // Bridge onUserLeaveHint (Home/Recents) into PiP
    DisposableEffect(playbackController) {
        playbackController.onUserLeaveHint = { enterPictureInPicture() }
        onDispose { playbackController.onUserLeaveHint = null }
    }

    // Reset window state when leaving the player (equivalent of the old onDestroy)
    DisposableEffect(Unit) {
        onDispose {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreenHelper.disableFullscreen()
            window.brightness = BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // Controls auto-hide
    // 拖动进度条期间（scrubPositionMs != null）不自动隐藏：控件栏消失会中断 scrub，
    // 导致 DefaultTimeBar 被释放、onScrubStop 丢失，松手后无法 seek。
    val controlsSuppressed = expandedMenu != null || infoPanelVisible || episodePickerVisible ||
        scrubPositionMs != null
    LaunchedEffect(controlsVisible, controlsSuppressed, controlsToken) {
        if (controlsVisible && !controlsSuppressed) {
            delay(Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
            controlsVisible = false
        }
    }

    // Seek target hold-off: after a scrub-seek the progress bar keeps showing the target
    // position until the polled player position reaches it. The poll runs every
    // POSITION_UPDATE_INTERVAL_MS and still reports the pre-seek value until the seek is
    // applied, so handing control back immediately made the bar snap back to the seek
    // origin for up to one poll cycle before jumping to the target again. The timeout is
    // a safety net so a seek that never settles cannot pin the bar forever.
    LaunchedEffect(pendingSeekPositionMs) {
        val target = pendingSeekPositionMs ?: return@LaunchedEffect
        withTimeoutOrNull(SEEK_UI_SETTLE_TIMEOUT_MS) {
            snapshotFlow { positionMs }.first { position ->
                abs(position - target) <= Constants.SEEK_POSITION_SETTLE_EPSILON_MS
            }
        }
        pendingSeekPositionMs = null
    }

    // endregion

    // region derived state

    val title = mediaSource?.getName(context).orEmpty()
    val chapters = mediaSource?.item?.chapters
    val hasChapters = !chapters.isNullOrEmpty()
    val queueSize = viewModel.queueManager.queueSize
    val currentQueueIndex = viewModel.queueManager.currentIndex
    val videoStream = mediaSource?.selectedVideoStream
    val subtitleStreams = mediaSource?.subtitleStreams.orEmpty()
    val maxStreamingBitrate = (mediaSource as? RemoteJellyfinMediaSource)?.maxStreamingBitrate
    val effectiveDecoder = decoderType ?: detectedDecoderType

    val infoModel = rememberPlaybackInfoModel(
        mediaSource = mediaSource,
        player = player,
        decoderType = effectiveDecoder,
        appPreferences = appPreferences,
    )

    val volumeTextFormat = stringResource(R.string.gesture_overlay_volume)
    val brightnessTextFormat = stringResource(R.string.gesture_overlay_brightness)
    val autoLabel = stringResource(R.string.menu_item_auto)
    val noneLabel = stringResource(R.string.menu_item_none)
    val hardwareLabel = stringResource(R.string.menu_item_hardware_decoding)
    val softwareLabel = stringResource(R.string.menu_item_software_decoding)

    // selectedAudioStream/selectedSubtitleStream 是 JellyfinMediaSource 上的普通 var（非快照状态），
    // 切换轨后 mediaSource 引用不变，必须把当前选中轨作为 remember 的 key，才能在下次重组时
    // 重建列表、刷新 RadioButton 的选中态（切换时 subtitlesEnabled 等状态变化会触发重组）。
    val audioTracks = remember(mediaSource, mediaSource?.selectedAudioStream) {
        mediaSource?.let { source ->
            val selected = source.selectedAudioStream ?: source.audioStreams.firstOrNull()
            source.audioStreams.map { stream ->
                AudioTrackItem(
                    index = stream.index,
                    title = formatStreamTitle(stream.displayTitle, stream.language, stream.codec),
                    selected = stream === selected,
                )
            }
        }.orEmpty()
    }

    val subtitleTracks = remember(mediaSource, noneLabel, mediaSource?.selectedSubtitleStream) {
        val source = mediaSource
        if (source == null) {
            emptyList()
        } else {
            buildList {
                add(
                    SubtitleTrackItem(
                        index = -1,
                        title = noneLabel,
                        selected = source.selectedSubtitleStream == null,
                    ),
                )
                source.subtitleStreams.forEach { stream ->
                    add(
                        SubtitleTrackItem(
                            index = stream.index,
                            title = formatStreamTitle(stream.displayTitle, stream.language, stream.codec),
                            selected = stream === source.selectedSubtitleStream,
                        ),
                    )
                }
            }
        }
    }

    val currentSpeed = player?.playbackParameters?.speed ?: 1f
    val speeds = remember(currentSpeed) {
        SPEED_MENU_STEPS.map { step -> SpeedItem(speed = step, selected = step == currentSpeed) }
    }

    val qualities = remember(mediaSource, maxStreamingBitrate, autoLabel) {
        val source = mediaSource
        if (source is RemoteJellyfinMediaSource) {
            // MediaStream 来自外部模块（jellyfin-sdk），公开属性无法智能转换，先取局部值
            val width = source.selectedVideoStream?.width
            val height = source.selectedVideoStream?.height
            if (width != null && height != null) {
                val options = qualityOptionsProvider.getApplicableQualityOptions(width, height)
                val selectedBitrate = maxStreamingBitrate?.takeIf { bitrate ->
                    options.any { option -> option.bitrate == bitrate }
                } ?: 0
                options.map { option ->
                    QualityItem(
                        bitrate = option.bitrate,
                        title = formatQualityTitle(option.maxHeight, option.bitrate, autoLabel),
                        selected = option.bitrate == selectedBitrate,
                    )
                }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val decoders = remember(effectiveDecoder, hardwareLabel, softwareLabel) {
        DecoderType.values().map { type ->
            DecoderItem(
                type = type,
                title = decoderLabel(type, hardwareLabel, softwareLabel),
                selected = type == effectiveDecoder,
            )
        }
    }

    val episodeItems = remember(episodeItemMap, currentQueueIndex) {
        episodeItemMap?.let { map ->
            buildEpisodePickerItems(
                queueIds = viewModel.queueManager.queueIds,
                items = map,
                selectedPosition = currentQueueIndex,
            )
        }
    }

    // endregion

    // region insets

    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val controlsPadding = if (isFullscreen) {
        val cutout = WindowInsets.displayCutout
        PaddingValues(
            start = with(density) {
                max(
                    cutout.getLeft(density, layoutDirection),
                    WindowInsets.systemBars.getLeft(density, layoutDirection),
                ).toDp()
            },
            top = with(density) {
                max(
                    cutout.getTop(density),
                    WindowInsets.systemBars.getTop(density),
                ).toDp()
            },
            end = with(density) {
                max(
                    cutout.getRight(density, layoutDirection),
                    WindowInsets.systemBars.getRight(density, layoutDirection),
                ).toDp()
            },
            bottom = with(density) {
                max(
                    cutout.getBottom(density),
                    WindowInsets.systemBars.getBottom(density),
                ).toDp()
            },
        )
    } else {
        systemBarsPadding
    }
    // The video surface (and with it the gesture surface) is padded by the system bars
    // unless we're in fullscreen, where it goes edge to edge.
    val playerViewPadding = if (isFullscreen) PaddingValues(0.dp) else systemBarsPadding

    // endregion

    // region gesture handler

    val exclusionSizeVerticalPx = with(density) {
        Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL.dp.roundToPx()
    }
    val exclusionSizeHorizontalPx = with(density) {
        Constants.SWIPE_GESTURE_EXCLUSION_SIZE_HORIZONTAL.dp.roundToPx()
    }

    val gestureHandler = rememberPlayerGestureHandler(
        appPreferences = appPreferences,
        audioManager = audioManager,
        window = window,
        sizeProvider = { gestureSurfaceSize },
        volumeTextFormat = volumeTextFormat,
        brightnessTextFormat = brightnessTextFormat,
        isLandscape = { isLandscapeOrientation() },
        isLocked = { locked },
        getPlayer = { viewModel.playerOrNull },
        onToggleControls = {
            if (controlsVisible) controlsVisible = false else showControls()
        },
        onPeekUnlockButton = { peekUnlockButton() },
        onTogglePlayPause = {
            viewModel.playerOrNull?.let { currentPlayer ->
                if (currentPlayer.isPlaying) currentPlayer.pause() else currentPlayer.play()
            }
        },
        onSeekTo = { position -> viewModel.seekTo(position) },
        onFastForward = { viewModel.fastForward() },
        onRewind = { viewModel.rewind() },
        onPressSpeedUp = { pressing ->
            viewModel.setPressSpeedUp(pressing, Constants.HOLD_SPEEDUP_MULTIPLIER)
        },
        onZoomChanged = { enabled ->
            playerViewHolder[0]?.resizeMode = if (enabled) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        onShowControls = { showControls() },
        longPressTimeoutMs = android.view.ViewConfiguration.getLongPressTimeout().toLong(),
        exclusionSizeVerticalPx = exclusionSizeVerticalPx,
        exclusionSizeHorizontalPx = exclusionSizeHorizontalPx,
    )

    // endregion

    // Configuration changes (orientation): fullscreen state + zoom mode
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        updateFullscreenState(configuration)
        gestureHandler.handleConfiguration()
    }

    // Back closes open panels/menus first
    BackHandler(enabled = infoPanelVisible || episodePickerVisible || expandedMenu != null) {
        expandedMenu = null
        infoPanelVisible = false
        episodePickerVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video surface
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    // The controls are Compose overlays; the built-in controller is unused
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    if (appPreferences.exoPlayerDirectPlayAss) {
                        subtitleView?.addView(AssSubtitleView(viewContext, assHandler))
                    }
                    playerViewHolder[0] = this
                }
            },
            update = { playerView -> playerView.player = player },
            onRelease = { playerView ->
                playerView.player = null
                playerView.subtitleView?.removeAllViews()
                playerViewHolder[0] = null
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(playerViewPadding),
        )

        // Gesture layer (brightness/volume/seek/double tap/long press/zoom)
        GestureOverlay(
            handler = gestureHandler,
            onSurfaceSizeChanged = { size -> gestureSurfaceSize = size },
            modifier = Modifier
                .matchParentSize()
                .padding(playerViewPadding),
        )

        // Overlay layer: always padded by the system bars (as the old player_overlay was)
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(systemBarsPadding),
        ) {
            // Loading indicator. Fade instead of popping: the player reports BUFFERING for
            // a single frame on cold start, and a hard show/hide reads as a stutter.
            AnimatedVisibility(
                visible = playerState == Player.STATE_BUFFERING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
            }

            // Skip media segment button
            AnimatedVisibility(
                visible = activeSkipSegment != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 96.dp),
            ) {
                val segment: MediaSegmentDto? = activeSkipSegment
                Surface(
                    color = Color(0xFF202124),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.skip_media_segment),
                        color = Color(0xFFDDDDDD),
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable { segment?.let { viewModel.skipMediaSegment(it) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // Unlock screen button
            AnimatedVisibility(
                visible = unlockButtonVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(start = 12.dp, top = 48.dp, end = 12.dp),
            ) {
                Surface(
                    color = Color(0x60000000),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screen_unlock_white_24dp),
                        contentDescription = stringResource(R.string.playback_lock_screen),
                        modifier = Modifier
                            .clickable { unlockScreen() }
                            .padding(16.dp)
                            .size(24.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        // Controls
        if (controlsVisible) {
            PlayerControls(
                state = PlayerControlsState(
                    title = title,
                    isFullscreen = isFullscreen,
                    hasChapters = hasChapters,
                    hasNext = viewModel.queueManager.hasNext(),
                    queueSize = queueSize,
                    currentQueueIndex = currentQueueIndex,
                    subtitleCount = subtitleStreams.size,
                    subtitlesEnabled = subtitlesEnabled,
                    qualityAvailable = mediaSource is RemoteJellyfinMediaSource,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    isPlaying = isPlaying,
                    chapterMarkings = chapterMarkings,
                    trickplay = trickplayHelper.state,
                    scrubPositionMs = scrubPositionMs,
                    pendingSeekPositionMs = pendingSeekPositionMs,
                ),
                menus = PlayerMenusState(
                    expanded = expandedMenu,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    speeds = speeds,
                    qualities = qualities,
                    decoders = decoders,
                ),
                actions = PlayerControlsActions(
                    onBack = { onClose() },
                    onTogglePlayPause = {
                        viewModel.playerOrNull?.let { currentPlayer ->
                            if (currentPlayer.isPlaying) currentPlayer.pause() else currentPlayer.play()
                        }
                    },
                    onPrevious = { viewModel.skipToPrevious() },
                    onNext = { viewModel.skipToNext() },
                    onPreviousChapter = { viewModel.previousChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onLockScreen = { lockScreen() },
                    onToggleSubtitles = {
                        scope.launch {
                            subtitlesEnabled = viewModel.trackSelectionHelper.toggleSubtitles()
                        }
                    },
                    onAudioTrackSelected = { index ->
                        scope.launch {
                            viewModel.trackSelectionHelper.selectAudioTrack(index)
                        }
                    },
                    onSubtitleTrackSelected = { index ->
                        scope.launch {
                            if (viewModel.trackSelectionHelper.selectSubtitleTrack(index)) {
                                subtitlesEnabled = index >= 0
                            }
                        }
                    },
                    onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                    onQualitySelected = { bitrate ->
                        scope.launch { viewModel.changeBitrate(bitrate) }
                    },
                    onDecoderSelected = { type -> viewModel.updateDecoderType(type) },
                    onShowInfo = {
                        if (episodePickerVisible) episodePickerVisible = false
                        infoPanelVisible = !infoPanelVisible
                    },
                    onShowEpisodes = {
                        if (infoPanelVisible) infoPanelVisible = false
                        if (episodePickerVisible) {
                            episodePickerVisible = false
                        } else {
                            if (!episodeItemsLoaded) {
                                episodeItemsLoaded = true
                                scope.launch {
                                    episodeItemMap = viewModel.queueManager.getQueueItemMap()
                                }
                            }
                            episodePickerVisible = true
                        }
                    },
                    onToggleFullscreen = {
                        val videoTrack = videoStream
                        if (videoTrack == null || videoTrack.isLandscape) {
                            val current = activity.resources.configuration.orientation
                            activity.requestedOrientation = when (current) {
                                Configuration.ORIENTATION_PORTRAIT ->
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            // No need to call playerFullscreenHelper in this case,
                            // since the configuration change triggers updateFullscreenState.
                        } else {
                            playerFullscreenHelper.toggleFullscreen()
                        }
                    },
                    onMenuExpand = { menu ->
                        val wasExpanded = expandedMenu != null
                        expandedMenu = menu
                        if (wasExpanded && menu == null && !AndroidVersion.isAtLeastR) {
                            updateFullscreenState(activity.resources.configuration)
                        }
                    },
                    onScrubStart = {
                        // 记录 scrub 起点，拖动期间位置文本显示 scrub 位置
                        scrubPositionMs = positionMs
                        showControls()
                    },
                    onScrubMove = { position ->
                        scrubPositionMs = position
                        trickplayHelper.onScrubMove(position)
                    },
                    onScrubStop = { position ->
                        scrubPositionMs = null
                        // 松手后执行 seek（旧版仅展示 trickplay，遗漏了 seek）
                        viewModel.seekTo(position)
                        trickplayHelper.onScrubStop()
                        // 进度条继续显示目标位置直到播放器轮询位置追上：轮询间隔内
                        // 位置上仍是旧值，直接置空会让进度条先跳回 seek 起点、再跳到目标。
                        pendingSeekPositionMs = position
                    },
                ),
                modifier = Modifier
                    .matchParentSize()
                    .padding(controlsPadding),
            )
        }

        // Panels are drawn last so they stay above the controls layer (the info card must
        // not be covered by the title bar / control bar).
        if (infoPanelVisible) {
            PlaybackInfoPanel(
                model = infoModel,
                onDismiss = { infoPanelVisible = false },
            )
        }

        if (episodePickerVisible) {
            EpisodePickerPanel(
                currentIndex = currentQueueIndex,
                queueSize = queueSize,
                items = episodeItems,
                onSelect = { position -> viewModel.selectQueueItem(position) },
                onDismiss = { episodePickerVisible = false },
            )
        }
    }
}

/**
 * LiveData → Compose State。
 *
 * lifecycle-runtime-compose 未提供 LiveData 版 `observeAsState`（仅有 Flow 版），
 * 且不引入已废弃的 `runtime-livedata` 工件，这里用 DisposableEffect 自行桥接，
 * 生命周期宿主取当前组合的 [LocalLifecycleOwner]（NavBackStackEntry）。
 */
@Composable
private fun <T> LiveData<T>.observeAsState(): State<T?> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(value) }
    DisposableEffect(this, lifecycleOwner) {
        val observer = Observer<T> { value -> state.value = value }
        observe(lifecycleOwner, observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
