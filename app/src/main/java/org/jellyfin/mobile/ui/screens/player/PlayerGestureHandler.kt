package org.jellyfin.mobile.ui.screens.player

import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import kotlin.math.abs

/**
 * 阶段 5：手势逻辑（亮度/音量/横向 seek/双击/长按倍速/缩放）。
 *
 * 由原 [org.jellyfin.mobile.player.ui.PlayerGestureHelper] 平移而来：
 * 所有 View 操作改为写入快照状态（[indicator]/[seekIndicator]/[doubleTapToggle]/[rippleSide]），
 * 由 GestureOverlay 渲染；与播放器的交互通过构造回调转发。
 */

/** 亮度/音量指示器状态。 */
data class VolumeBrightnessIndicator(
    @DrawableRes val iconRes: Int,
    val progress: Float,
    val text: String,
)

/** 横向 seek 指示器状态。 */
data class SeekIndicator(
    val forward: Boolean,
    val seekText: String,
    val positionText: String,
    val progress: Float,
)

/** 双击播放/暂停图标状态。[token] 用于重放动画。 */
data class DoubleTapToggleIndicator(
    val x: Float,
    val y: Float,
    val willPause: Boolean,
    val token: Long,
)

/** 双击 seek 的半屏涟漪（[token] 用于重放动画）。 */
data class RippleIndicator(
    val rightSide: Boolean,
    val token: Long,
)

private enum class GestureDirection {
    NONE,
    HORIZONTAL,
    VERTICAL,
}

class PlayerGestureHandler(
    private val appPreferences: AppPreferences,
    private val audioManager: AudioManager,
    private val window: Window,
    private val scope: CoroutineScope,
    private val sizeProvider: () -> IntSize,
    private val exclusionSizeVerticalPx: Int,
    private val exclusionSizeHorizontalPx: Int,
    private val volumeTextFormat: String,
    private val brightnessTextFormat: String,
    private val isLandscape: () -> Boolean,
    private val isLocked: () -> Boolean,
    private val getPlayer: () -> Player?,
    private val onToggleControls: () -> Unit,
    private val onPeekUnlockButton: () -> Unit,
    private val onTogglePlayPause: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onFastForward: () -> Unit,
    private val onRewind: () -> Unit,
    private val onPressSpeedUp: (Boolean) -> Unit,
    private val onZoomChanged: (Boolean) -> Unit,
    private val onShowControls: () -> Unit,
) {
    var indicator: VolumeBrightnessIndicator? by mutableStateOf(null)
        private set
    var seekIndicator: SeekIndicator? by mutableStateOf(null)
        private set
    var doubleTapToggle: DoubleTapToggleIndicator? by mutableStateOf(null)
        private set
    var ripple: RippleIndicator? by mutableStateOf(null)
        private set

    private var currentGesture = GestureDirection.NONE
    private var dragStartPosition: Offset? = null
    private var activePointerCount = 1

    /** Tracks a value during a swipe gesture; reset to -1f when a gesture starts. */
    private var swipeGestureValueTracker = -1f
    private var isHorizontalSeeking = false
    private var seekTimeAccumulator = 0L
    private var seekStartPosition = 0L
    private var mediaDuration = 0L
    private var isOnPressingSpeedUp = false
    private var longPressJob: Job? = null
    private var isZoomEnabled = false
    private var toggleToken = 0L
    private var rippleToken = 0L

    // region taps

    fun onTap() {
        if (isLocked()) {
            onPeekUnlockButton()
        } else {
            onToggleControls()
        }
    }

    fun onDoubleTap(position: Offset) {
        val viewWidth = sizeProvider().width
        val viewCenterX = viewWidth / 2

        if (appPreferences.exoPlayerDoubleTapPause) {
            // Toggle play/pause and show the resulting state as a brief icon overlay
            val willPause = getPlayer()?.isPlaying == true
            onTogglePlayPause()
            doubleTapToggle = DoubleTapToggleIndicator(
                x = position.x,
                y = position.y,
                willPause = willPause,
                token = ++toggleToken,
            )
        } else {
            // Show ripple effect and fast-forward/rewind
            ripple = RippleIndicator(rightSide = position.x > viewCenterX, token = ++rippleToken)
            scope.launch {
                delay(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS)
                ripple = null
            }
            if (position.x > viewCenterX) onFastForward() else onRewind()
        }

        // Cancel the auto-hide timer and restart it after seeking
        onShowControls()
    }

    /**
     * 长按开始（由 onPress 中的延迟任务触发，超过系统长按阈值且未发生拖拽时）。
     */
    fun onLongPressStarted() {
        if (isLocked() || !appPreferences.exoPlayerAllowPressSpeedUp) return
        isOnPressingSpeedUp = true
        onPressSpeedUp(true)
    }

    /** 隐藏双击播放/暂停浮层（退场动画结束后由覆盖层调用）。 */
    fun hideDoubleTapToggle() {
        doubleTapToggle = null
    }

    /**
     * 按压结束（抬起或取消）：取消长按检测并释放倍速。
     */
    fun onPressReleased() {
        longPressJob?.cancel()
        longPressJob = null
        releaseSpeedUp()
    }

    /**
     * 在 onPress 中启动长按检测；[timeoutMs] 通常取 ViewConfiguration 的长按阈值。
     */
    fun startLongPressDetection(timeoutMs: Long) {
        longPressJob?.cancel()
        longPressJob = scope.launch {
            delay(timeoutMs)
            onLongPressStarted()
        }
    }

    // endregion

    // region drags

    fun onDragStart(position: Offset) {
        // A drag supersedes any pending long press
        longPressJob?.cancel()
        longPressJob = null
        releaseSpeedUp()

        dragStartPosition = position
        currentGesture = GestureDirection.NONE
        swipeGestureValueTracker = -1f
        isHorizontalSeeking = false
        seekTimeAccumulator = 0L
        seekStartPosition = 0L
        mediaDuration = 0L
    }

    /**
     * 当前按下的指针数量（由覆盖层的非消耗型观察者更新）。
     * 大于 1 时忽略拖拽，避免捏合缩放触发亮度/音量/seek。
     */
    fun onPointerCountChanged(count: Int) {
        activePointerCount = count
    }

    fun onDrag(position: Offset, dragAmount: Offset) {
        val size = sizeProvider()
        if (size.width == 0 || size.height == 0) return
        // Pinch zoom is handled by the transform detector; ignore drags while multiple
        // pointers are down (mirrors the old per-event pointerCount dispatch).
        if (activePointerCount > 1) return
        val start = dragStartPosition ?: return

        // Check whether swipe was started in excluded region (vertical)
        if (start.y < exclusionSizeVerticalPx || start.y > size.height - exclusionSizeVerticalPx) {
            return
        }

        // Determine/lock gesture direction
        if (currentGesture == GestureDirection.NONE) {
            val deltaX = position.x - start.x
            val deltaY = position.y - start.y
            currentGesture = when {
                abs(deltaX) > abs(deltaY) * 2 -> GestureDirection.HORIZONTAL
                abs(deltaY) > abs(deltaX) * 2 -> GestureDirection.VERTICAL
                else -> return
            }
        }

        // Handle horizontal swipe for seek
        if (currentGesture == GestureDirection.HORIZONTAL) {
            if (!appPreferences.exoPlayerAllowHorizontalGesture) {
                // Horizontal seek gestures are disabled. Keep the direction locked for the rest of
                // this gesture so it can't turn into a brightness/volume gesture halfway through.
                return
            }

            // Check horizontal exclusion zones (edges of screen)
            if (start.x < exclusionSizeHorizontalPx || start.x > size.width - exclusionSizeHorizontalPx) {
                return
            }

            handleHorizontalSeek(dragAmount.x)
            return
        }

        // Handle vertical swipe for brightness/volume
        if (!appPreferences.exoPlayerAllowSwipeGestures) {
            return
        }
        handleVerticalSwipe(dragAmount.y)
    }

    private fun handleVerticalSwipe(dragAmountY: Float) {
        // Hide horizontal overlay
        seekIndicator = null

        val size = sizeProvider()
        val viewCenterX = size.width / 2

        // Distance to swipe to go from min to max
        val distanceFull = size.height * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
        val ratioChange = -dragAmountY / distanceFull

        val start = dragStartPosition ?: return
        if (start.x > viewCenterX) {
            // Swiping on the right, change volume
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (swipeGestureValueTracker == -1f) swipeGestureValueTracker = currentVolume.toFloat()

            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            swipeGestureValueTracker += ratioChange * maxVolume

            val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)

            indicator = VolumeBrightnessIndicator(
                iconRes = R.drawable.ic_volume_white_24dp,
                progress = if (maxVolume > 0) toSet.toFloat() / maxVolume else 0f,
                text = String.format(volumeTextFormat, if (maxVolume > 0) toSet * Constants.PERCENT_MAX / maxVolume else 0),
            )
        } else {
            // Swiping on the left, change brightness
            val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

            // Initialize on first swipe
            if (swipeGestureValueTracker == -1f) {
                val brightness = window.brightness
                swipeGestureValueTracker = when (brightness) {
                    in brightnessRange -> brightness
                    else -> {
                        // Fall back to the current system brightness when the window doesn't override it.
                        // Use the default-value overload to avoid SettingNotFoundException on devices without the setting.
                        val systemBrightness = Settings.System.getFloat(
                            window.context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            Constants.SCREEN_BRIGHTNESS_DEFAULT,
                        ) / Constants.SCREEN_BRIGHTNESS_MAX
                        systemBrightness.coerceIn(brightnessRange)
                    }
                }
            }

            swipeGestureValueTracker = (swipeGestureValueTracker + ratioChange).coerceIn(brightnessRange)
            window.brightness = swipeGestureValueTracker
            if (appPreferences.exoPlayerRememberBrightness) {
                appPreferences.exoPlayerBrightness = swipeGestureValueTracker
            }

            indicator = VolumeBrightnessIndicator(
                iconRes = R.drawable.ic_brightness_white_24dp,
                progress = swipeGestureValueTracker.coerceIn(0f, 1f),
                text = String.format(brightnessTextFormat, (swipeGestureValueTracker * Constants.PERCENT_MAX).toInt()),
            )
        }
    }

    private fun handleHorizontalSeek(dragAmountX: Float) {
        // Hide vertical overlay
        indicator = null

        val player = getPlayer()

        // Initialize seek start position on first swipe
        if (!isHorizontalSeeking) {
            seekStartPosition = player?.currentPosition ?: 0L
            mediaDuration = (player?.duration ?: 0L).coerceAtLeast(0L)
        }

        isHorizontalSeeking = true

        // Calculate seek time with non-linear acceleration
        // The further you swipe, the faster the seek time increases
        val baseSeekDeltaMs = (dragAmountX * 1000 / Constants.HORIZONTAL_SWIPE_DISTANCE_PER_SECOND).toLong()

        // Apply acceleration based on accumulated distance
        // Use portrait acceleration (2x) for portrait mode, default for landscape
        val accelerationFactor = if (isLandscape()) {
            Constants.SEEK_ACCELERATION_FACTOR
        } else {
            Constants.SEEK_ACCELERATION_FACTOR_PORTRAIT
        }
        val currentSeekSeconds = abs(seekTimeAccumulator / 1000f)
        val accelerationMultiplier = 1f + (currentSeekSeconds / 30f) * (accelerationFactor - 1f)
        val acceleratedSeekDelta = (baseSeekDeltaMs * accelerationMultiplier).toLong()

        seekTimeAccumulator += acceleratedSeekDelta

        // Clamp the accumulated seek time to valid range.
        // Keep a safety margin from the end: landing exactly on EOF ends the
        // episode immediately (watched sync + auto-advance), so the overlay
        // target must match the clamped seek actually performed on release.
        val safeDurationMs = (mediaDuration - Constants.SEEK_TO_END_SAFETY_MARGIN_MS).coerceAtLeast(0)
        val minSeek = -seekStartPosition
        val maxSeek = if (mediaDuration > 0) safeDurationMs - seekStartPosition else Long.MAX_VALUE
        seekTimeAccumulator = seekTimeAccumulator.coerceIn(minSeek, maxSeek)

        // Update the seek overlay with mm:ss format
        val timeFormatted = formatSeekOverlayTime(seekTimeAccumulator)
        val seekText = if (seekTimeAccumulator >= 0) "+$timeFormatted" else "-$timeFormatted"

        // Update position text (current position / duration)
        val targetPosition = (seekStartPosition + seekTimeAccumulator).coerceIn(0, safeDurationMs)

        seekIndicator = SeekIndicator(
            forward = seekTimeAccumulator >= 0,
            seekText = seekText,
            positionText = "${formatTime(targetPosition)} / ${formatTime(mediaDuration)}",
            progress = if (mediaDuration > 0) targetPosition.toFloat() / mediaDuration.toFloat() else 0f,
        )
    }

    fun onDragEnd() {
        // Handle horizontal seek gesture completion
        if (currentGesture == GestureDirection.HORIZONTAL && isHorizontalSeeking && seekTimeAccumulator != 0L) {
            // Seek to the absolute position shown in the overlay. A relative seek from the
            // release-time position would overshoot by however far playback advanced during
            // the swipe, since playback keeps running while the gesture is active.
            onSeekTo(seekStartPosition + seekTimeAccumulator)
        }
        finishGesture()
    }

    fun onDragCancel() {
        finishGesture()
    }

    private fun finishGesture() {
        // Release press-to-speed-up if active
        releaseSpeedUp()

        // Hide gesture indicators after timeout, if shown
        if (indicator != null || seekIndicator != null) {
            scope.launch {
                delay(Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong())
                indicator = null
                seekIndicator = null
            }
        }

        dragStartPosition = null
        currentGesture = GestureDirection.NONE
        isHorizontalSeeking = false
        seekTimeAccumulator = 0L
        seekStartPosition = 0L
        mediaDuration = 0L
        swipeGestureValueTracker = -1f
    }

    private fun releaseSpeedUp() {
        if (isOnPressingSpeedUp) {
            isOnPressingSpeedUp = false
            onPressSpeedUp(false)
        }
    }

    // endregion

    // region zoom

    fun onZoom(zoom: Float) {
        if (!isLandscape()) return
        if (abs(zoom - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
            isZoomEnabled = zoom > 1
            applyZoomMode()
        }
    }

    /** 方向变化后刷新缩放模式（与旧 handleConfiguration 一致）。 */
    fun handleConfiguration() {
        applyZoomMode()
    }

    private fun applyZoomMode() {
        onZoomChanged(isZoomEnabled && isLandscape())
    }

    // endregion
}

/**
 * 创建并记住 [PlayerGestureHandler]。
 *
 * @param longPressTimeoutMs 长按阈值（通常 ViewConfiguration.getLongPressTimeout()）
 */
@Composable
fun rememberPlayerGestureHandler(
    appPreferences: AppPreferences,
    audioManager: AudioManager,
    window: Window,
    sizeProvider: () -> IntSize,
    volumeTextFormat: String,
    brightnessTextFormat: String,
    isLandscape: () -> Boolean,
    isLocked: () -> Boolean,
    getPlayer: () -> Player?,
    onToggleControls: () -> Unit,
    onPeekUnlockButton: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onFastForward: () -> Unit,
    onRewind: () -> Unit,
    onPressSpeedUp: (Boolean) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onShowControls: () -> Unit,
    longPressTimeoutMs: Long,
    exclusionSizeVerticalPx: Int,
    exclusionSizeHorizontalPx: Int,
): PlayerGestureHandler {
    val scope = rememberCoroutineScope()
    return remember(
        appPreferences,
        audioManager,
        window,
        scope,
        volumeTextFormat,
        brightnessTextFormat,
        longPressTimeoutMs,
        exclusionSizeVerticalPx,
        exclusionSizeHorizontalPx,
    ) {
        PlayerGestureHandler(
            appPreferences = appPreferences,
            audioManager = audioManager,
            window = window,
            scope = scope,
            sizeProvider = sizeProvider,
            exclusionSizeVerticalPx = exclusionSizeVerticalPx,
            exclusionSizeHorizontalPx = exclusionSizeHorizontalPx,
            volumeTextFormat = volumeTextFormat,
            brightnessTextFormat = brightnessTextFormat,
            isLandscape = isLandscape,
            isLocked = isLocked,
            getPlayer = getPlayer,
            onToggleControls = onToggleControls,
            onPeekUnlockButton = onPeekUnlockButton,
            onTogglePlayPause = onTogglePlayPause,
            onSeekTo = onSeekTo,
            onFastForward = onFastForward,
            onRewind = onRewind,
            onPressSpeedUp = onPressSpeedUp,
            onZoomChanged = onZoomChanged,
            onShowControls = onShowControls,
        )
    }
}
