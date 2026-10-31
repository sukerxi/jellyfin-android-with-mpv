package org.jellyfin.mobile.ui.screens.player

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerUiError
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlaybackController
import org.jellyfin.mobile.player.interaction.PlaybackLauncher
import org.jellyfin.mobile.player.interaction.PlaybackRequest
import org.jellyfin.mobile.player.mpv.MpvPlayer
import org.jellyfin.mobile.player.mpv.bench.FrameCompareViewModel
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.ChapterMarkingState
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.player.ui.TrickplayHelper
import org.jellyfin.mobile.ui.components.SnackbarController
import org.jellyfin.mobile.ui.navigation.BackNavigationDispatcher
import org.jellyfin.mobile.ui.navigation.RegisterBackHandler
import org.jellyfin.mobile.ui.screens.mpvcompare.InPlayerCompareOverlay
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.extensions.lockOrientation
import org.jellyfin.mobile.utils.isAutoRotateOn
import org.jellyfin.mobile.utils.isOnCellularNetwork
import org.jellyfin.mobile.utils.koinInject
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlayMethod

/**
 * 播放进度的媒体时钟 tick 间隔（毫秒），交给 media3-ui-compose 的
 * [rememberProgressStateWithTickInterval] 调度：播放时按媒体时钟对齐（并考虑倍速），
 * 位置向下取整到 tick 网格；暂停/未就绪时自动降频（库内 1s fallback），
 * seek / 时长变化等播放器事件会触发即时刷新。
 */
private const val POSITION_TICK_INTERVAL_MS = 200L

/** seek 后进度条保持目标位置的最长时间（毫秒）；超时后交还给播放器实时位置。 */
private const val SEEK_UI_SETTLE_TIMEOUT_MS = 1000L

/**
 * 加载指示器隐藏延迟（毫秒）。
 *
 * mpv 的状态机已按 ExoPlayer 语义修复（READY = 「现在就能播」，初始缓存填充期间保持
 * BUFFERING，见 MpvPlayer 的 FileLoaded 处理），正常情况下加载期间不再有状态抖动。
 * 这里保留一层滞回作为兜底：万一某条路径仍出现短暂的 READY 闪烁（如网络抖动导致
 * paused-for-cache 快速翻转），指示器保持挂载、旋转不中断——不确定态进度条一旦被
 * 销毁重建，旋转就会从 0° 重启，视觉上即是卡顿。
 *
 * 真正的连续性由「起播前 + 缓冲中」合成单条可见性状态保证（见 [PlayerScreen] 里
 * showLoadingIndicator 的注释），因此这里不需要长延迟；剩下的时间交给淡出动画，
 * 指示器不会在首帧上停留。
 */
private const val LOADING_INDICATOR_HIDE_DELAY_MS = 250L

/** 加载指示器淡入 / 淡出时长（毫秒）：入场要快，出场略慢，避免首帧上「啪」地消失。 */
private const val LOADING_INDICATOR_FADE_IN_MS = 150
private const val LOADING_INDICATOR_FADE_OUT_MS = 180

/** 控件层淡入 / 淡出时长（毫秒）：自动收起、点按收起、起播完成后的收起共用同一条过渡。 */
private const val CONTROLS_FADE_IN_MS = 120
private const val CONTROLS_FADE_OUT_MS = 140

/** 加载指示器补上说明文案的延迟（毫秒）：前几秒保持纯转圈，避免一闪而过的提示。 */
private const val LOADING_HINT_DELAY_MS = 3_000L

/** 加载指示器升级出「重试 / 退出」的二次延迟（毫秒，累计约 12s）。 */
private const val LOADING_STUCK_DELAY_MS = 9_000L

/** 加载指示器的阶段：静默转圈 → 文案说明 → 兜底操作。 */
private enum class PlayerLoadingStage { ACTIVE, SLOW, STUCK }

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
/**
 * 播放失败的落地状态。
 *
 * 保留原始播放请求，重试时按同样的参数（含移动网络码率上限）再走一遍，
 * 不需要用户退回网页重新点一次播放。
 */
private class PlaybackFailure(
    val messageRes: Int,
    val request: PlaybackRequest,
    val maxStreamingBitrateOverride: Int?,
)

/**
 * 自动隐藏计时器的观察输入：当前是否该计时，以及最近一次用户交互的序号。
 *
 * 两个字段一起作为 [snapshotFlow] 的输出，[interactionTick] 变化即重置倒计时；
 * 计时条件本身变化（菜单打开、手指按下、暂停……）则取消倒计时。
 */
private data class AutoHideSignal(
    val eligible: Boolean,
    val interactionTick: Int,
)

@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    backDispatcher: BackNavigationDispatcher,
    viewModel: PlayerViewModel = viewModel(),
    compareViewModel: FrameCompareViewModel = viewModel(),
    playbackLauncher: PlaybackLauncher = koinInject(),
    playbackController: PlaybackController = koinInject(),
    appPreferences: AppPreferences = koinInject(),
    qualityOptionsProvider: QualityOptionsProvider = koinInject(),
    assHandler: AssHandler = koinInject(),
    snackbarController: SnackbarController = koinInject(),
    webappFunctionChannel: WebappFunctionChannel = koinInject(),
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
    val transcodeReasons by viewModel.transcodeReasons.observeAsState()

    // 播放进度 state：media3 官方 Compose 实现，替代原先手写的 200ms while(delay) 轮询。
    // 库内部按媒体时钟调度（播放 200ms tick / 暂停 1s / 非 READY 不调度），
    // 并监听 seek、时长、播放态等事件立即重排，无需本屏维护轮询协程。
    //
    // 注意：这里只创建 state 对象，不读取 currentPositionMs / durationMs。
    // 这三个值每 200ms 变一次，若在本屏顶层读取，整个 PlayerScreen（包括视频 AndroidView、
    // 手势层、浮层）都会跟着 5Hz 重组；读取点下沉到 PlayerProgressRow 后，tick 只重组
    // 进度行，控件隐藏时甚至没有任何观察者。
    val isTranscoding = mediaSource?.playMethod == PlayMethod.TRANSCODE

    val progressState = rememberProgressStateWithTickInterval(
        player = player,
        tickIntervalMs = POSITION_TICK_INTERVAL_MS,
    )

    // UI state
    var controlsVisible by remember { mutableStateOf(true) }
    /**
     * 交互信号：控件层上每发生一次触摸就自增。
     *
     * 只在事件回调里写、在协程里读（见下方的自动隐藏计时）。若在组合期读取（例如当成
     * `LaunchedEffect` 的 key），用户每按一下屏幕都会让整个播放器界面重组一次，而它
     * 的语义仅仅是「重置一个计时器」。
     */
    val interactionTick = remember { mutableIntStateOf(0) }
    // 手指是否还按在控件层上：按住期间不隐藏，抬手后重新计时。
    var touchActive by remember { mutableStateOf(false) }
    // 自动隐藏时长由用户决定（3s / 5s / 10s / 永不），不再写死。
    val controlsTimeoutMs by appPreferences.controlsTimeoutFlow
        .collectAsState(initial = appPreferences.controlsTimeoutMs)
    var locked by remember { mutableStateOf(false) }
    /**
     * 播放失败的落地状态。非 null 时播放器停在原位显示错误卡片，而不是
     * "toast 一句然后被踢回网页"——后者让用户看不到原因，也没有重试入口。
     */
    var playbackFailure by remember { mutableStateOf<PlaybackFailure?>(null) }
    var unlockButtonVisible by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf<PlayerMenuType?>(null) }
    var infoPanelVisible by remember { mutableStateOf(false) }
    // mpv configuration comparison panel; only reachable while the mpv engine is playing and the
    // user keeps the entry enabled.
    var compareVisible by remember { mutableStateOf(false) }
    val mpvCompareEntryEnabled by appPreferences.mpvShowCompareEntryFlow
        .collectAsState(initial = appPreferences.mpvShowCompareEntry)
    var episodePickerVisible by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var gestureSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    // 拖动状态只关心「有没有在拖」：直接读 scrubPositionMs 会让整个屏幕在拖动时跟着
    // 每个触摸事件重组，用派生态把订阅收窄成布尔值（开始/结束各触发一次）。
    val isScrubbing by remember { derivedStateOf { scrubPositionMs != null } }
    // 松手后正在生效的 seek 目标位置：进度 state 的位置追上目标（或超时）后置空
    var pendingSeekPositionMs by remember { mutableStateOf<Long?>(null) }
    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    var subtitlesEnabled by remember { mutableStateOf(false) }
    var episodeItemMap by remember { mutableStateOf<Map<UUID, BaseItemDto>?>(null) }
    var episodeItemsLoaded by remember { mutableStateOf(false) }
    var unlockHideJob by remember { mutableStateOf<Job?>(null) }
    // 移动网络播放确认：蜂窝网络下待用户选择的播放请求；非空时展示确认弹窗且尚未开始播放。
    var pendingMobileDataRequest by remember { mutableStateOf<PlaybackRequest?>(null) }

    val playerViewHolder = remember { arrayOfNulls<PlayerView>(1) }
    val trickplayHelper = remember(context) { TrickplayHelper(context) }
    val orientationListener = remember(activity) { SmartOrientationListener(activity) }
    val playerFullscreenHelper = remember(window) { PlayerFullscreenHelper(window) }
    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java)!! }

    // region helpers

    fun showControls() {
        controlsVisible = true
        interactionTick.intValue++
    }

    /*
     * 单击 = 显示/收起控件。
     *
     * 控件隐藏时由手势层收到点击，控件可见时手势层被控件层挡住、由控件层的空白处点击收到
     * —— 两个入口都调到这里。极少数情况下同一次抬手会同时命中两层（重叠命中），因此加一个
     * 很短的窗口去抖，避免「收起又立刻弹出」。
     */
    var lastControlsToggleUptime by remember { mutableStateOf(0L) }
    fun toggleControls() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastControlsToggleUptime < Constants.CONTROLS_TOGGLE_DEBOUNCE_MS) return
        lastControlsToggleUptime = now
        if (controlsVisible) {
            controlsVisible = false
        } else {
            showControls()
        }
    }

    // 读屏/开关控制开启时，控件栏与解锁入口都不自动收起：这类用户需要沿着可聚焦节点
    // 逐项浏览，几秒后把目标从手指底下抽走等于让他们重新摸索一遍。
    val accessibilityManager = remember(context) {
        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    }
    fun isScreenReaderActive(): Boolean =
        accessibilityManager?.let { it.isEnabled && it.isTouchExplorationEnabled } == true

    // Set while the screen is being closed, so the "player == null" auto-close effect does
    // not dismiss the (newly started) player again right after it was closed.
    val closingSoon = remember { mutableStateOf(false) }

    // Set once a player instance has been attached during this session. The player starts
    // out null while the playback request is resolved, so "player == null" only means
    // "playback ended" after a player has existed to end.
    var playbackStarted by remember { mutableStateOf(false) }

    // Set (once) when this session is over, either because the user left the screen or
    // because playback ended on its own. Guards every other effect from running again and
    // keeps the closing paths idempotent.
    val playbackEnded = remember { mutableStateOf(false) }

    // 加载指示器。三段时间线：
    // - 起播前（还在向服务端要播放信息，mediaSource 仍为空）立刻显示：此前这段时间
    //   只有一块黑屏，网络慢时用户完全不知道应用在做什么；
    // - 缓冲态沿用滞回（见 LOADING_INDICATOR_HIDE_DELAY_MS），mpv 状态机按
    //   ExoPlayer 语义修复后仍可能出现的 READY 闪烁不会让转圈反复重启；
    // - 3s 后补一句说明，12s 仍未就绪给「重试 / 退出」——长时间卡在网络上时，
    //   用户不必靠猜，也不用退出重进才能重来。
    //
    // 关键：可见性由**一条**状态驱动，把「起播前」「播放器还没就绪」「缓冲中」合成同一个
    // 等待区间。之前几段各自驱动、最后 OR 起来，媒体源解析完成（preparingPlayback 立刻变假）
    // 到播放器真正进入 BUFFERING 之间存在一段空档（播放器正在创建 / 处于 IDLE）：指示器被
    // 销毁后马上重建，CircularProgressIndicator 的不确定态动画从 0° 重新开始——用户看到的
    // 就是「转圈突然跳一下」。合成之后这条空档不存在，同一个指示器一路转到首帧。
    val isBuffering = playerState == Player.STATE_BUFFERING
    val preparingPlayback = mediaSource == null && playbackFailure == null &&
        pendingMobileDataRequest == null && !closingSoon.value && !playbackEnded.value
    val waitingForPlayback = playbackFailure == null &&
        pendingMobileDataRequest == null &&
        !closingSoon.value && !playbackEnded.value &&
        (
            mediaSource == null ||
                isBuffering ||
                playerState == null ||
                playerState == Player.STATE_IDLE
            )

    var showLoadingIndicator by remember { mutableStateOf(true) }
    // 每次重试自增，让下面的计时序列从头开始
    var loadingAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(waitingForPlayback, loadingAttempt) {
        if (waitingForPlayback) {
            showLoadingIndicator = true
        } else {
            delay(LOADING_INDICATOR_HIDE_DELAY_MS)
            showLoadingIndicator = false
        }
    }

    var loadingStage by remember { mutableStateOf(PlayerLoadingStage.ACTIVE) }
    LaunchedEffect(showLoadingIndicator, preparingPlayback, loadingAttempt) {
        loadingStage = PlayerLoadingStage.ACTIVE
        if (!showLoadingIndicator) return@LaunchedEffect
        delay(LOADING_HINT_DELAY_MS)
        loadingStage = PlayerLoadingStage.SLOW
        delay(LOADING_STUCK_DELAY_MS)
        loadingStage = PlayerLoadingStage.STUCK
    }

    // 控件层的 actions 只在首次组合时创建（见下方 remember），内部的 closePlayer() 会
    // 捕获当次组合的 onClose 参数。onClose 来自调用方，可能随上级重组换实例，
    // 用 rememberUpdatedState 保证事件触发时读到的是最新引用。
    val currentOnClose by rememberUpdatedState(onClose)

    /**
     * Leave the player screen: the web app takes playback over again and the native player
     * is stopped unless the user enabled background playback (or the web app is casting).
     * Every exit path (back button, the toolbar back arrow, the WebView's back handling)
     * goes through here, otherwise the foreground service keeps playing behind the UI.
     */
    fun closePlayer() {
        if (closingSoon.value || playbackEnded.value) return
        closingSoon.value = true
        viewModel.onPlayerClosed()

        // The player has to be gone by the time the session ends: [PlayerViewModel.onPlayerClosed]
        // only stops it (and only when the user did not opt into background playback), leaving a
        // live player behind for the next session. Binding the next media source to a player
        // that is still attached to the previous one is what made the second "play" of a session
        // run into the state it was left in.
        if (!viewModel.keepPlaybackOnExit()) {
            playbackEnded.value = true
        }
        currentOnClose()
    }

    fun hideUnlockButton() {
        unlockHideJob?.cancel()
        unlockButtonVisible = false
    }

    fun peekUnlockButton() {
        unlockHideJob?.cancel()
        unlockButtonVisible = true
        if (isScreenReaderActive()) return
        // 解锁按钮跟随同一个「自动隐藏」设置：选了「永不隐藏」的人，锁定后也不该被抽走
        // 唯一的出口。
        val timeout = controlsTimeoutMs
        if (timeout <= 0) return
        unlockHideJob = scope.launch {
            delay(timeout.toLong())
            unlockButtonVisible = false
        }
    }

    fun lockScreen() {
        locked = true
        controlsVisible = false
        orientationListener.disable()
        activity.lockOrientation()
        peekUnlockButton()
        // 锁定后控件与解锁按钮都会按超时消失；没有任何提示时用户会以为应用卡死或触控失灵。
        snackbarController.show(
            context.getString(R.string.playback_locked_hint),
            duration = SnackbarDuration.Long,
        )
    }

    fun unlockScreen() {
        locked = false
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
            playbackFailure = PlaybackFailure(messageRes, request, maxStreamingBitrateOverride)
        } else {
            playbackFailure = null
        }
    }

    // Handle playback options and start playback (once per composition).
    // The ViewModel lives in the activity's store, so it outlives this screen; the player
    // does not (see [closePlayer]). Every session therefore has to build its own player -
    // the old per-fragment ViewModel did that from its lifecycle observer, and dropping it
    // is what left the second playback of a session without a player.
    LaunchedEffect(Unit) {
        val request = playbackLauncher.consume()
        if (request == null) {
            // No pending playback request (e.g. restored to the player route), close
            closePlayer()
            return@LaunchedEffect
        }

        // 移动网络（蜂窝）下的远程播放：开启提示时先等用户选择，否则直接按设定码率播放。
        // 本地下载播放不经过网络，不受影响。
        val mobileDataPlayback = request.playOptions.playFromDownloads != true && context.isOnCellularNetwork
        if (mobileDataPlayback && appPreferences.mobileDataPlaybackPrompt) {
            pendingMobileDataRequest = request
        } else {
            startPlayback(request, if (mobileDataPlayback) appPreferences.mobileDataMaxBitrate else null)
        }
    }

    // Automatically close the screen when playback ended, unless we're in PiP mode.
    // Only a player that actually appeared and then went away means "playback ended":
    // at the start of a session the player is legitimately still null (it is created while
    // the playback request is being resolved, which involves network round trips), and
    // closing on that would dismiss the screen the user just opened.
    LaunchedEffect(player) {
        if (player != null) {
            playbackStarted = true
        } else if (playbackStarted && !pipMode && !closingSoon.value && !playbackEnded.value) {
            // Playback ended on its own; the session is over, and PlayerViewModel has already
            // released the player. Dismiss without running the close path again.
            playbackEnded.value = true
            onClose()
        }
    }

    // 播放过程中的运行期错误（解码失败、断流等）：走全局 Snackbar，可回溯、可复制，
    // 也不再与播放器底栏抢位置。断流这类「链路好了就能继续看」的错误附带重试动作，
    // 让用户不必退出播放器再重进（重进还会丢掉当前进度）。
    val errorObserver = remember(viewModel) {
        Observer<PlayerUiError> { error ->
            val safeMessage = error.message.ifEmpty {
                context.getString(R.string.player_error_unspecific_exception)
            }
            if (error.retryable) {
                snackbarController.show(
                    text = safeMessage,
                    actionLabel = context.getString(R.string.retry),
                    duration = SnackbarDuration.Long,
                    action = viewModel::retryPlayback,
                )
            } else {
                snackbarController.showLong(safeMessage)
            }
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

    // 播放态事件驱动：官方 ProgressState 只含进度三要素，不含 isPlaying。
    // 原先它由 200ms 位置轮询顺带读取（暂停也空转），改为 Player.Listener 回调，
    // 同时驱动播放/暂停图标与屏幕常亮。
    DisposableEffect(player) {
        val currentPlayer = player
        if (currentPlayer == null) {
            isPlaying = false
            onDispose { }
        } else {
            isPlaying = currentPlayer.isPlaying
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            }
            currentPlayer.addListener(listener)
            onDispose { currentPlayer.removeListener(listener) }
        }
    }
    LaunchedEffect(isPlaying) {
        window.keepScreenOn = isPlaying
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
                    if (viewModel.playerOrNull == null && playbackStarted) {
                        closePlayer()
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
                compareVisible = false
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

    // Reset window state when leaving the player (equivalent of the old onDestroy), and make
    // sure playback is stopped even when the screen is dismissed without going through
    // closePlayer() (e.g. a server switch pops the web route while the overlay is open).
    // The player itself is torn down here too: a player that outlives its session would be
    // reused by the next one, still bound to the media source it was left with.
    DisposableEffect(Unit) {
        onDispose {
            if (!playbackEnded.value) {
                playbackEnded.value = true
                viewModel.endPlayback()
            }
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    // Controls auto-hide
    // 拖动进度条期间（scrubPositionMs != null）不自动隐藏：控件栏消失会中断 scrub，
    // 导致 DefaultTimeBar 被释放、onScrubStop 丢失，松手后无法 seek。
    //
    // 隐藏规则（按用户反馈重做）：
    // - 暂停时不隐藏 —— 暂停就是在读信息/做选择，此时抽走控件是干扰；
    // - 手指按在控件上时不隐藏 —— 只「重置计时」不够，按住超过计时长度照样会消失；
    // - 时长为 0 表示永不隐藏，完全交给用户的意愿；
    // - 菜单/面板/拖动进度条期间不隐藏（原有规则）。
    //
    // 这些抑制条件与「最近一次交互」全部由 snapshotFlow 在协程里观察，组合期一个都不读：
    // 否则按下/抬起手指、每拖一下进度条、开关一次菜单，都会让整个播放器界面（含视频
    // AndroidView 与两侧控件栏）重组一遍——而它们的作用只是「重置一个倒计时」。
    fun isControlsSuppressed(): Boolean = expandedMenu != null || infoPanelVisible ||
        episodePickerVisible || compareVisible || isScrubbing || touchActive

    LaunchedEffect(controlsTimeoutMs) {
        val timeout = controlsTimeoutMs
        if (timeout <= 0) return@LaunchedEffect
        snapshotFlow {
            AutoHideSignal(
                eligible = controlsVisible && !isControlsSuppressed() && isPlaying &&
                    !isScreenReaderActive(),
                interactionTick = interactionTick.intValue,
            )
        }.collectLatest { signal ->
            if (!signal.eligible) return@collectLatest
            delay(timeout.toLong())
            controlsVisible = false
        }
    }

    // 起播完成即收起控件（每次会话只做一次）。
    //
    // 起播阶段控件是挂着的（用户需要知道「正在做什么」），一旦首帧出来、缓冲结束，
    // 画面本身才是主角 —— 这时候再让控件占满一个自动隐藏时长纯属干等。播放器类应用的
    // 通行做法是「加载完成 = 让出画面」。
    //
    // 只在本次会话第一次真正播起来时触发：中途缓冲（网络抖动）结束时，若用户正开着菜单
    // 或在看进度，收起控件反而是干扰。
    //
    // 抑制条件在协程里现读（不走 composition），触摸与菜单开关不再触发本屏重组。
    var initialLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(showLoadingIndicator, isPlaying) {
        if (initialLoadFinished || showLoadingIndicator || !isPlaying) return@LaunchedEffect
        initialLoadFinished = true
        val timeout = controlsTimeoutMs
        if (controlsVisible && !isControlsSuppressed() && timeout > 0 && !isScreenReaderActive()) {
            controlsVisible = false
        }
    }

    // Seek target hold-off: after a scrub-seek the progress bar keeps showing the target
    // position until the player's reported position reaches it. The official progress state
    // refreshes on POSITION_DISCONTINUITY, but mpv still reports the pre-seek time-pos until
    // the seek is actually applied, so handing control back immediately made the bar snap
    // back to the seek origin before jumping to the target again. The timeout is a safety
    // net so a seek that never settles cannot pin the bar forever.
    LaunchedEffect(pendingSeekPositionMs) {
        val target = pendingSeekPositionMs ?: return@LaunchedEffect
        withTimeoutOrNull(SEEK_UI_SETTLE_TIMEOUT_MS) {
            // 直接读官方 snapshot state：局部别名 positionMs 是普通 Long，
            // 在 lambda 中只会捕获当次组合的值，无法驱动 flow。
            snapshotFlow { progressState.currentPositionMs }.first { position ->
                abs(position - target) <= Constants.SEEK_POSITION_SETTLE_EPSILON_MS
            }
        }
        pendingSeekPositionMs = null
    }

    // endregion

    // region derived state

    // getName walks the item's metadata on every call, and the position tick
    // recomposes ~5x/second. The name only depends on the media source, so it is
    // computed once per source instead of per tick.
    val title = remember(mediaSource) { mediaSource?.getName(context).orEmpty() }
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
        transcodeReasons = transcodeReasons.orEmpty(),
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

    val configuration = LocalConfiguration.current

    // 横屏恒为全屏（见 [updateFullscreenState]）。系统栏的隐藏是异步的：隐藏动画跑完、
    // insets 回调到达之前，isFullscreen 仍是 false。布局若跟着它走，竖屏切横屏后的头几帧
    // 里，画面和控件层都会按"系统栏还在"缩进一圈，看上去就是没铺满；而它是否出现，
    // 取决于旋转前是否已经处于沉浸模式，所以是"有几率"。横屏的留白因此只认刘海 ——
    // 这恰好等于系统栏隐藏完成后的结果。
    val isLandscapeConfiguration = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    // 刘海留白。PaddingValues 没有 equals 实现，每次重组新建实例会破坏 Compose 的跳过逻辑
    // （位置轮询每秒触发约 5 次重组），按像素值 remember 复用同一个实例。
    val cutoutLeft = with(density) { WindowInsets.displayCutout.getLeft(density, layoutDirection) }
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(density) }
    val cutoutRight = with(density) { WindowInsets.displayCutout.getRight(density, layoutDirection) }
    val cutoutBottom = with(density) { WindowInsets.displayCutout.getBottom(density) }
    val cutoutPadding = remember(cutoutLeft, cutoutTop, cutoutRight, cutoutBottom) {
        PaddingValues(
            start = with(density) { cutoutLeft.toDp() },
            top = with(density) { cutoutTop.toDp() },
            end = with(density) { cutoutRight.toDp() },
            bottom = with(density) { cutoutBottom.toDp() },
        )
    }

    val controlsPadding = when {
        // 横屏不等系统栏真正隐藏：见上面的说明。
        isLandscapeConfiguration -> cutoutPadding
        isFullscreen -> {
            // 竖屏下的全屏（竖屏视频、或用户手动进入）：系统栏可能被临时划出，
            // 因此取系统栏与刘海的最大值，控件不会压到临时栏下面。
            val systemBars = WindowInsets.systemBars
            val barsLeft = with(density) { systemBars.getLeft(density, layoutDirection) }
            val barsTop = with(density) { systemBars.getTop(density) }
            val barsRight = with(density) { systemBars.getRight(density, layoutDirection) }
            val barsBottom = with(density) { systemBars.getBottom(density) }
            remember(
                cutoutLeft,
                cutoutTop,
                cutoutRight,
                cutoutBottom,
                barsLeft,
                barsTop,
                barsRight,
                barsBottom,
            ) {
                PaddingValues(
                    start = with(density) { max(cutoutLeft, barsLeft).toDp() },
                    top = with(density) { max(cutoutTop, barsTop).toDp() },
                    end = with(density) { max(cutoutRight, barsRight).toDp() },
                    bottom = with(density) { max(cutoutBottom, barsBottom).toDp() },
                )
            }
        }
        else -> systemBarsPadding
    }
    // The video surface (and with it the gesture surface) is padded by the system bars
    // unless we're in fullscreen, where it goes edge to edge.
    val playerViewPadding =
        if (isLandscapeConfiguration || isFullscreen) PaddingValues(0.dp) else systemBarsPadding

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
        onToggleControls = { toggleControls() },
        onPeekUnlockButton = { peekUnlockButton() },
        onTogglePlayPause = { viewModel.togglePlayPause() },
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
        // 双击只做「跳转 / 暂停」这两件事：不再顺手把控件弹出来。以前每次双击都会
        // showControls()，控件可见时用户的第一次单击又只会把控件收起——于是「双击暂停」
        // 需要三下才生效。双击时顺手收起控件（画面让位给反馈图标），语义才对。
        onHideControls = { if (controlsVisible) controlsVisible = false },
        // 双击跳转量与快进/快退按钮共用服务端下发的步长，文案必须反映真实值，
        // 否则用户会以为自己跳的是固定 10 秒。
        seekStepLabel = { forward ->
            val seconds = viewModel.seekStepMs(forward) / 1000
            context.getString(
                if (forward) R.string.playback_seek_forward_step else R.string.playback_seek_back_step,
                seconds,
            )
        },
        exclusionSizeVerticalPx = exclusionSizeVerticalPx,
        exclusionSizeHorizontalPx = exclusionSizeHorizontalPx,
    )

    // endregion

    // Configuration changes (orientation): fullscreen state + zoom mode
    LaunchedEffect(configuration.orientation) {
        updateFullscreenState(configuration)
        gestureHandler.handleConfiguration()
    }

    // 系统返回与顶栏返回箭头共用这一套语义（见 PlayerControlsActions.onBack）：
    // 先关面板/菜单；锁屏时把解锁按钮叫出来；否则退出播放器。
    //
    // 「收起控件」不再由返回键承担：点画面就能收起，让返回键兼职收控件会让
    // 「按一次收控件、再按一次退出」和顶栏箭头「按一次退出」变成两套答案，
    // 用户无法预测同一个返回动作为什么结果不同。
    fun handlePlayerBack() {
        when {
            infoPanelVisible || episodePickerVisible || expandedMenu != null || compareVisible -> {
                expandedMenu = null
                infoPanelVisible = false
                episodePickerVisible = false
                compareVisible = false
            }
            // 锁屏时解锁按钮会随超时消失；返回键什么都不做会被当成按键失灵。
            locked -> peekUnlockButton()
            else -> closePlayer()
        }
    }

    // 返回键交给宿主（唯一的仲裁点）派发，本屏只声明"按下返回时怎么处理"。
    backDispatcher.RegisterBackHandler { handlePlayerBack() }

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
                    // Mirror the renderer setup: the subtitle view is only needed when this
                    // playback session actually renders SSA/ASS locally, and the device profile
                    // guarantees the server does not burn them in when that is the case.
                    if (appPreferences.resolveExoPlayerDirectPlayAss(isVideoTranscoding = isTranscoding)) {
                        subtitleView?.addView(AssSubtitleView(viewContext, assHandler))
                    }
                    playerViewHolder[0] = this
                }
            },
            // Only rebind when the player instance actually changes. `update` runs on *every*
            // recomposition - the position poll alone recomposes this screen ~5x/s - and
            // PlayerView.setPlayer() unconditionally calls clearVideoSurfaceView() followed by
            // setVideoSurfaceView() (see media3's PlayerView.setPlayer). For an ExoPlayer that
            // is a cheap no-op, but MpvPlayer implements the pair as
            // removeCallback()/addCallback() on the SurfaceHolder, which destroys and recreates
            // the platform Surface - and with it mpv's video output - every time. The audio
            // track is unaffected, so playback keeps running with a black picture.
            // Binding per player instance keeps that teardown to the one time it is meant to
            // happen.
            update = { playerView ->
                if (playerView.player !== player) playerView.player = player
            },
            onRelease = { playerView ->
                playerView.player = null
                playerView.subtitleView?.removeAllViews()
                playerViewHolder[0] = null
                // The video output is a SurfaceView: it punches a hole into the window and is
                // composited from its own SurfaceControl, outside the WebView's layer. Both
                // the hole and the Surface are only reclaimed by the *next* window traversal,
                // and a removal that happens while one is already running has its
                // requestLayout dropped by ViewRootImpl - the hole then stays on screen,
                // covered by an empty Surface, so returning to the (still alive) WebView
                // shows a black page until a touch forces another redraw. Ask for one more
                // traversal after the view is gone, which also repaints the WebView that was
                // sitting underneath the hole.
                forceWindowRedraw(window)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(playerViewPadding),
        )

        // Double tap seek ripple. Sits below the gesture layer and does not take part in
        // gesture handling.
        SeekRippleLayer(
            handler = gestureHandler,
            modifier = Modifier
                .matchParentSize()
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

        // Overlay layer for the unlock entry. Uses the same content padding as the controls so
        // a landscape display cutout cannot clip the only way out of the locked state.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(controlsPadding),
        ) {
            // Unlock screen button
            AnimatedVisibility(
                visible = unlockButtonVisible,
                enter = fadeIn(tween(CONTROLS_FADE_IN_MS)) +
                    scaleIn(tween(CONTROLS_FADE_IN_MS), initialScale = 0.94f),
                exit = fadeOut(tween(CONTROLS_FADE_OUT_MS)) +
                    scaleOut(tween(CONTROLS_FADE_OUT_MS), targetScale = 0.96f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(start = 12.dp, top = 48.dp, end = 12.dp),
            ) {
                // 只画一个图标时用户要猜「这是不是解锁」；带上文字，并与其他浮层共用玻璃卡片。
                Row(
                    modifier = Modifier
                        .playerGlass(RoundedCornerShape(12.dp))
                        // 让按下的 ripple 也止步于圆角，而不是在圆角外面露出一圈方形水波
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { unlockScreen() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screen_unlock_white_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = PlayerGlassIconTint,
                    )
                    Text(
                        text = stringResource(R.string.playback_unlock_screen),
                        color = PlayerGlassTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Controls
        // 控件层：整层淡入淡出。自动收起、点按收起、起播完成后的收起共用同一条
        // 过渡，不再是「一帧之内整层消失/出现」。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_IN_MS)),
            exit = fadeOut(tween(CONTROLS_FADE_OUT_MS)),
            modifier = Modifier.matchParentSize(),
        ) {
            // 回调实例只建一次：内部捕获的都是 remember 的 MutableState 委托与稳定对象，
            // 在事件真正触发时读到的仍是最新值。若每次重组都新建 actions，控件层所有子
            // Composable 的入参都会"变"，进度 tick 就能穿透整棵控件树。
            val controlsActions = remember {
                PlayerControlsActions(
                    // 与系统返回同语义：面板/锁屏优先，最后才是退出播放。
                    onBack = { handlePlayerBack() },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
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
                        compareVisible = false
                        infoPanelVisible = !infoPanelVisible
                    },
                    onShowCompare = {
                        // The comparison panel and the info panel share the area above the video.
                        infoPanelVisible = false
                        episodePickerVisible = false
                        compareVisible = !compareVisible
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
                        // 读当前媒体源而不是组合时捕获的 videoStream：这个回调在控件显示期间
                        // 只会创建一次（remember），捕获值会停留在旧队列项上。
                        val videoTrack = viewModel.mediaSourceOrNull?.selectedVideoStream
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
                        // 记录 scrub 起点，拖动期间位置文本显示 scrub 位置。
                        // 直接问播放器的实时位置：这里不再有 200ms tick 驱动的组合局部变量。
                        scrubPositionMs = viewModel.playerOrNull?.currentPosition
                            ?: progressState.currentPositionMs
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
                        // 进度条继续显示目标位置直到播放器上报位置追上：seek 生效前
                        // 位置上仍是旧值，直接置空会让进度条先跳回 seek 起点、再跳到目标。
                        pendingSeekPositionMs = position
                    },
                    // 只重置自动隐藏计时，不改变可见性：能走到这里说明控件本来就是可见的，
                    // 而空白处单击要能把它收起——旧的 showControls() 会在收起后立刻又弹回来。
                    onUserInteraction = { interactionTick.intValue++ },
                    onTouchActiveChanged = { active -> touchActive = active },
                )
            }
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
                    isPlaying = isPlaying,
                    mpvCompareEnabled = mpvCompareEntryEnabled && player is MpvPlayer,
                    // 与下面 onToggleFullscreen 的判定保持一致：横屏（或未知）视频走转屏，
                    // 竖屏视频才是沉浸式切换。
                    rotatesOnFullscreen = videoStream == null || videoStream.isLandscape,
                    // 加载等待期间中央那排按钮让位给转圈（见 PlayerControlsState.loading）。
                    loading = showLoadingIndicator,
                ),
                menus = PlayerMenusState(
                    expanded = expandedMenu,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    speeds = speeds,
                    qualities = qualities,
                    decoders = decoders,
                ),
                actions = controlsActions,
                // 进度行自己读取 200ms tick / scrub / trickplay / 章节状态：这些高频值
                // 不进 PlayerControlsState。读取点还要再下沉一层（见 PlayerProgressSlot）：
                // 这个 lambda 不是 restartable 的，在 lambda 体内读状态会让整条控件栏
                // 一起重组——写上值在这里、读取在更里层，才算真正隔离。
                progress = {
                    PlayerProgressSlot(
                        progressState = progressState,
                        trickplayHelper = trickplayHelper,
                        chapterMarkingsFlow = viewModel.chapterMarkings,
                        scrubPositionMs = { scrubPositionMs },
                        pendingSeekPositionMs = { pendingSeekPositionMs },
                        actions = controlsActions,
                    )
                },
                gestureHandler = gestureHandler,
                // 刘海/系统栏只作用于按钮内容，遮罩与点击区域仍铺满（见 PlayerControls 的
                // contentPadding 说明）。
                contentPadding = controlsPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 手势反馈指示器（亮度/音量、横向 seek、长按倍速角标）。
        // 画在控件层之上：控件可见时滑动 seek / 调亮度音量已经可用（空白处的手势层），
        // 指示器若留在控件层下面，会被上下渐变压暗、被中央按钮盖住。
        GestureIndicatorsOverlay(
            handler = gestureHandler,
            modifier = Modifier
                .matchParentSize()
                .padding(playerViewPadding),
        )

        // Skip media segment button
        // 画在控件层之上：控件可见时，控件层的空白点击层会先吃掉按下事件，按钮放在下面
        // 就会被判成"点了没反应"——先收控件，再点才生效。位置在底栏上方，不与任何
        // 常驻按钮或进度条重叠。
        //
        // 锁定屏幕时隐藏：锁定就是为了防止误触，跳过分段同样是"会改变播放位置"的操作，
        // 不该在锁定后还能点。内边距与控件层一致（横屏只避刘海），免得被右侧刘海裁掉。
        //
        // 单独抽成一个 Composable：它订阅的「当前分段」状态每秒都会更新，若在 PlayerScreen
        // 顶层收集，播放中每秒都会整屏重组一次。
        SkipMediaSegmentLayer(
            viewModel = viewModel,
            controlsPadding = controlsPadding,
            locked = locked,
        )

        // Loading indicator - a Material3 spinner restyled to be thick enough to read at
        // this size and deliberately polished: 6dp stroke, 24%-alpha track, rounded caps.
        //
        // Visibility is driven by the single continuous state above, NOT by the raw
        // buffering state: mpv's state machine now follows ExoPlayer semantics (READY only
        // once playback can actually proceed), and the synthesised state absorbs any
        // residual flicker - disposing and recreating the indicator restarts its rotation
        // from 0 degrees, which is exactly what made it stutter.
        //
        // The fade keeps the unmount off-screen: the spinner leaves as a fade instead of
        // disappearing between two frames, so handing over to the first video frame reads
        // as one motion rather than a jump.
        //
        // 画在控件层之上：超时后的「重试 / 退出」必须始终可点 —— 控件可见时，控件层的
        // 空白点击层会覆盖整屏，这一块若在下面就点不到，画面也会被底部按钮压住。
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(systemBarsPadding),
        ) {
            AnimatedVisibility(
                visible = showLoadingIndicator,
                enter = fadeIn(tween(LOADING_INDICATOR_FADE_IN_MS)),
                exit = fadeOut(tween(LOADING_INDICATOR_FADE_OUT_MS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val indicatorColor = MaterialTheme.colorScheme.primary
                // 转圈锚定在画面正中，说明文案与按钮在它下方展开。
                // 如果把它们放进同一个居中的 Column，每多一行整列就被顶高一截（3s 的
                // 文案一次、12s 的按钮再一次），画面中心的转圈反而在"加载"过程中不断上移，
                // 与上一轮"起播不跳变"的目标相悖。
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                        color = indicatorColor,
                        strokeWidth = 6.dp,
                        trackColor = indicatorColor.copy(alpha = 0.24f),
                        strokeCap = StrokeCap.Round,
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = maxHeight / 2 + 44.dp)
                            .widthIn(max = 320.dp)
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedVisibility(
                            visible = loadingStage != PlayerLoadingStage.ACTIVE,
                            enter = fadeIn(tween(LOADING_INDICATOR_FADE_IN_MS)),
                            exit = fadeOut(tween(LOADING_INDICATOR_FADE_OUT_MS)),
                        ) {
                            // 与手势 HUD / 跳过按钮同一套玻璃语言：加载期间首帧可能已经
                            // 上屏，纯半透明底衬在亮画面上会糊成一片。
                            val hintShape = RoundedCornerShape(10.dp)
                            Text(
                                text = stringResource(
                                    when {
                                        loadingStage == PlayerLoadingStage.STUCK ->
                                            R.string.player_loading_check_network
                                        preparingPlayback -> R.string.player_loading_preparing
                                        else -> R.string.player_loading_buffering
                                    },
                                ),
                                color = PlayerGlassTextPrimary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    // 读屏用户看不到转圈：阶段文案变化要让 TalkBack 播报出来，
                                    // 否则整个加载过程在他们的耳朵里是静默的。
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                                    .playerGlass(hintShape, shadowElevation = 0.dp)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = loadingStage == PlayerLoadingStage.STUCK,
                            enter = fadeIn(tween(LOADING_INDICATOR_FADE_IN_MS)),
                            exit = fadeOut(tween(LOADING_INDICATOR_FADE_OUT_MS)),
                        ) {
                            val actionShape = RoundedCornerShape(12.dp)
                            Row(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .playerGlass(actionShape)
                                    // 两个按钮的 ripple 都在卡片边缘，不裁剪会露出圆角外的方角水波。
                                    .clip(actionShape)
                                    .padding(horizontal = 4.dp),
                            ) {
                                // 起播阶段（还没有播放器/媒体源）没有可"续播"的位置，
                                // 重试只会重复同一条网络请求，不如直接退出回网页重新点播；
                                // 缓冲阶段走 viewModel 的重试链路，从当前位置重载媒体源。
                                if (!preparingPlayback) {
                                    TextButton(
                                        onClick = {
                                            loadingAttempt++
                                            viewModel.retryPlayback()
                                        },
                                    ) {
                                        Text(
                                            text = stringResource(R.string.retry),
                                            color = PlayerGlassTextPrimary,
                                        )
                                    }
                                }
                                TextButton(onClick = { closePlayer() }) {
                                    Text(
                                        text = stringResource(R.string.player_loading_exit),
                                        color = PlayerGlassTextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 播放失败卡片：盖住控件栏（此时也点不动它们），只留「重试 / 返回」两条出路。
        playbackFailure?.let { failure ->
            PlaybackErrorCard(
                message = stringResource(failure.messageRes),
                onRetry = {
                    playbackFailure = null
                    scope.launch {
                        startPlayback(failure.request, failure.maxStreamingBitrateOverride)
                    }
                },
                onExit = { closePlayer() },
            )
        }

        // Panels are drawn last so they stay above the controls layer: their cards must not
        // be covered by the title bar / control bar. Their own bounds match the controls
        // layer, so the bars on top of them keep working (the requirement is "the video
        // info card sits above the play button layer").
        if (infoPanelVisible) {
            PlaybackInfoPanel(
                model = infoModel,
                onDismiss = { infoPanelVisible = false },
                onCopy = {
                    val title = context.getString(R.string.playback_info_title)
                    val text = infoModel.toPlainText(title)
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, text))
                    snackbarController.show(context.getString(R.string.playback_info_copied))
                },
                controlsBounds = controlsPadding,
            )
        }

        if (compareVisible) {
            InPlayerCompareOverlay(
                viewModel = viewModel,
                compareViewModel = compareViewModel,
                // The comparison renders the frame at the size the video is actually drawn at, in
                // pixels. The surface view is that size; the screen in dp is not.
                viewportSize = {
                    val surfaceView = playerViewHolder[0]?.videoSurfaceView
                    if (surfaceView == null) {
                        IntSize.Zero
                    } else {
                        IntSize(surfaceView.width, surfaceView.height)
                    }
                },
                onDismiss = { compareVisible = false },
            )
        }

        if (episodePickerVisible) {
            EpisodePickerPanel(
                currentIndex = currentQueueIndex,
                queueSize = queueSize,
                items = episodeItems,
                onSelect = { position -> viewModel.selectQueueItem(position) },
                onDismiss = { episodePickerVisible = false },
                controlsBounds = controlsPadding,
            )
        }

        // 移动网络播放确认：默认推荐低码率播放，也可选择继续原画质或取消（退出播放器返回网页）。
        pendingMobileDataRequest?.let { request ->
            val mobileDataBitrate = appPreferences.mobileDataMaxBitrate
            val qualityLabel = stringResource(
                when (mobileDataBitrate) {
                    Constants.MOBILE_DATA_BITRATE_480P -> R.string.mobile_data_bitrate_480p
                    Constants.MOBILE_DATA_BITRATE_1080P -> R.string.mobile_data_bitrate_1080p
                    else -> R.string.mobile_data_bitrate_720p
                },
            )
            Dialog(
                // 三个选项都是「是否开始播放」的明确决策：误触外部或按返回不应等同于取消播放。
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp),
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.mobile_data_playback_dialog_title),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.mobile_data_playback_dialog_message,
                                qualityLabel,
                                formatDataPerMinute(mobileDataBitrate),
                            ),
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // 竖排按钮：三个 TextButton 平铺时看起来同等重要，窄屏还会换行挤压。
                        Button(
                            onClick = {
                                pendingMobileDataRequest = null
                                scope.launch { startPlayback(request, mobileDataBitrate) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                        ) {
                            Text(stringResource(R.string.mobile_data_playback_play_low_bitrate))
                        }
                        OutlinedButton(
                            onClick = {
                                pendingMobileDataRequest = null
                                scope.launch { startPlayback(request, null) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.mobile_data_playback_play_original))
                        }
                        TextButton(
                            onClick = {
                                pendingMobileDataRequest = null
                                closePlayer()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.mobile_data_playback_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 跳过分段按钮层。
 *
 * 单独成一个 Composable 是为了隔离「当前分段」这条每秒更新的状态流：它的变化只应
 * 重组按钮本身，而不是把整块播放器画面（含视频 AndroidView）一起重组。
 */
@Composable
private fun SkipMediaSegmentLayer(
    viewModel: PlayerViewModel,
    controlsPadding: PaddingValues,
    locked: Boolean,
) {
    val activeSkipSegment by viewModel.activeAskToSkipSegment.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(controlsPadding),
    ) {
        AnimatedVisibility(
            visible = activeSkipSegment != null && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 96.dp),
        ) {
            val segment: MediaSegmentDto? = activeSkipSegment
            val shape = RoundedCornerShape(12.dp)
            // 整块 48dp 高都可点：之前只有文字本身可点，18sp 的「跳过」实际
            // 触控高度约 34dp，低于最小触控目标。
            Box(
                modifier = Modifier
                    .playerGlass(shape)
                    .clip(shape)
                    .clickable(role = Role.Button) {
                        segment?.let { viewModel.skipMediaSegment(it) }
                    }
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // 说明跳过的是什么（片头/片尾/…），只写「跳过」时用户不知道
                    // 这一下会跳到哪里。
                    text = stringResource(
                        segment?.let { skipSegmentLabelRes(it.type) } ?: R.string.skip_media_segment,
                    ),
                    color = PlayerGlassTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

/**
 * 跳过分段按钮的文案：按分段类型说明将要跳过什么，未知类型回退到通用的「跳过」。
 */
@StringRes
private fun skipSegmentLabelRes(type: MediaSegmentType): Int = when (type) {
    MediaSegmentType.INTRO -> R.string.skip_media_segment_intro
    MediaSegmentType.OUTRO -> R.string.skip_media_segment_outro
    MediaSegmentType.PREVIEW -> R.string.skip_media_segment_preview
    MediaSegmentType.RECAP -> R.string.skip_media_segment_recap
    MediaSegmentType.COMMERCIAL -> R.string.skip_media_segment_commercial
    else -> R.string.skip_media_segment
}

/**
 * 进度行插槽：把高频状态的全部读取收进一个 restartable 的组合作用域。
 *
 * 传给 [PlayerControls] 的 `progress` lambda 不是 restartable 的（编译器只为它生成
 * replace group），在 lambda 体内读快照状态会把失效上报给**外层** [PlayerControls]
 * 的重组作用域：一次 200ms tick、一个拖动触摸事件，重建的就是整条控件栏。读取移到这里
 * 之后，进度条、时间文本、trickplay 预览与章节标记的更新只重组这一行。
 */
@Composable
private fun PlayerProgressSlot(
    progressState: ProgressStateWithTickInterval,
    trickplayHelper: TrickplayHelper,
    chapterMarkingsFlow: StateFlow<List<ChapterMarkingState>>,
    scrubPositionMs: () -> Long?,
    pendingSeekPositionMs: () -> Long?,
    actions: PlayerControlsActions,
) {
    val chapterMarkings by chapterMarkingsFlow.collectAsStateWithLifecycle()
    PlayerProgressRow(
        progressState = progressState,
        trickplay = trickplayHelper.state,
        chapterMarkings = chapterMarkings,
        scrubPositionMs = scrubPositionMs(),
        pendingSeekPositionMs = pendingSeekPositionMs(),
        actions = actions,
    )
}

/**
 * 双击 seek 涟漪层：把 [PlayerGestureHandler.ripple] 的读取收进独立的 restartable 作用域。
 *
 * 直接在 [PlayerScreen] 组合期读取时，双击一次会让整个播放器界面重组两次——
 * 包括视频 [AndroidView] 的 update 与其余所有浮层。下沉之后，涟漪的
 * 显示/消失只重组这一层。
 */
@Composable
private fun SeekRippleLayer(
    handler: PlayerGestureHandler,
    modifier: Modifier = Modifier,
) {
    SeekRippleOverlay(
        trigger = handler.ripple,
        onFinished = handler::hideRipple,
        modifier = modifier,
    )
}

/**
 * 在播放器浮层移除后补一次窗口遍历。
 *
 * 视频输出是 SurfaceView：它从窗口里挖洞，由独立 [android.view.SurfaceControl]
 * 直接合成，不经过 WebView 所在的绘制层。洞与 Surface 都要等窗口的**下一次**遍历
 * 才回收；若这次移除正好发生在一次遍历过程中，ViewRootImpl 会丢弃随之而来的
 * requestLayout，洞便留在原处，盖住底下（从未被销毁的）WebView —— 表现就是返回后
 * 全黑，碰一下屏幕触发重绘才恢复。
 *
 * 这里在视图已经离开窗口之后排一次遍历：SurfaceView 的洞被收回，原先被压住的
 * WebView 同时重绘一帧。
 */
private fun forceWindowRedraw(window: Window) {
    val decorView = window.decorView
    // 两次：第一次紧跟移除，覆盖"移除发生在遍历中途、requestLayout 被丢弃"的情况；
    // 第二次稍晚一点，兜住窗口此刻不可见 / 正在转场导致第一次遍历被跳过的情况。
    // 窗口已经干净时两者都只是空转。
    for (delayMs in longArrayOf(0L, WINDOW_REDRAW_RETRY_MS)) {
        decorView.postDelayed({
            decorView.requestLayout()
            decorView.invalidate()
        }, delayMs)
    }
}

/** [forceWindowRedraw] 的二次兜底延迟。 */
private const val WINDOW_REDRAW_RETRY_MS = 80L

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
