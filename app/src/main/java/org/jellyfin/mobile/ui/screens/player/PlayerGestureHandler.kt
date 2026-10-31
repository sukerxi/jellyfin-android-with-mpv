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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 阶段 5：手势逻辑（亮度/音量/横向 seek/双击/长按倍速/缩放）。
 *
 * 由原 [org.jellyfin.mobile.player.ui.PlayerGestureHelper] 平移而来：
 * 所有 View 操作改为写入快照状态（[indicator]/[seekIndicator]/[doubleTapToggle]/[ripple]），
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

/**
 * 双击 seek 的涟漪反馈的一次性触发（[token] 用于识别新的双击）。
 *
 * [x]/[y] 为双击坐标，作为平台 RippleDrawable 的 hotspot。
 * [label] 是叠加在涟漪上的跳转量文案（`+10s` / `-10s`）：只画一个涟漪的话，用户无法
 * 判断这次双击到底跳了多远 —— 步长是服务端下发的偏好，可能 10s 也可能 30s。
 */
data class RippleIndicator(
    val x: Float,
    val y: Float,
    val token: Long,
    val label: String,
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
    private val onHideControls: () -> Unit,
    /** 双击跳转量的可读文案（`+10s` / `-10s`），步长来自服务端偏好。 */
    private val seekStepLabel: (forward: Boolean) -> String,
) {
    var indicator: VolumeBrightnessIndicator? by mutableStateOf(null)
        private set
    var seekIndicator: SeekIndicator? by mutableStateOf(null)
        private set
    var doubleTapToggle: DoubleTapToggleIndicator? by mutableStateOf(null)
        private set
    var ripple: RippleIndicator? by mutableStateOf(null)
        private set

    /** 长按临时倍速是否正在生效：宿主据此显示「N× 快进中」角标并给一次触感反馈。 */
    var speedBoostActive: Boolean by mutableStateOf(false)
        private set

    private var currentGesture = GestureDirection.NONE
    private var dragStartPosition: Offset? = null
    private var activePointerCount = 1

    /** Tracks a value during a swipe gesture; reset to -1f when a gesture starts. */
    private var swipeGestureValueTracker = -1f
    /**
     * 本次手势内缓存的最大音量。`getStreamMaxVolume` 是一次 Binder 调用，手指移动期间每个
     * 触摸事件都问一次等于每秒上百次 IPC；输出设备在手势中途切换的概率可以忽略。
     */
    private var cachedMaxVolume = -1
    /** 最近一次真正写入系统的音量档位：整数档位不变时重复 `setStreamVolume` 也是 IPC。 */
    private var lastAppliedVolume = -1
    /** 最近一次写入 HUD 的音量档位：档位不变时不必重组指示浮层。 */
    private var lastShownVolume = -1
    /** 最近一次真正写入窗口的亮度百分比（亮度按 1% 量化后再写，见 [handleVerticalSwipe]）。 */
    private var lastAppliedBrightnessPercent = -1
    /** 本次手势是否改过亮度：偏好落盘从「每个触摸事件」挪到手势结束（见 [finishGesture]）。 */
    private var brightnessChanged = false
    private var isHorizontalSeeking = false
    private var seekTimeAccumulator = 0L
    private var seekStartPosition = 0L
    private var mediaDuration = 0L
    private var isOnPressingSpeedUp = false
    private var isZoomEnabled = false
    private var toggleToken = 0L
    private var rippleToken = 0L

    // region taps

    /**
     * 上一次轻点的抬手时间与位置，供**所有**手势输入层共享。
     *
     * 控件隐藏时第一击由手势层收到，抬手立即呼出控件；第二击因此落到刚出现的控件层上。
     * 若每层各自保存「上一击」（pointerInput 的局部变量），第二击在控件层看来就是新的
     * 第一击——双击在控件隐藏时永远不成立，用户必须先把控件叫出来才能双击暂停/快进。
     */
    private var lastTapUpTime = -1L
    private var lastTapPosition = Offset.Zero
    private var hasPendingTap = false

    /**
     * 这次按下是否落在上一次轻点的双击窗口内。
     *
     * @param downTimeMs 本次按下的事件时间
     * @param downPosition 本次按下的位置（与该层上一次轻点所在层的坐标系可能不同，只在
     * 控件呼出/收起的交界处发生；容差足够吸收这点偏移）
     */
    fun isSecondTap(downTimeMs: Long, downPosition: Offset, timeoutMs: Long, slopPx: Float): Boolean =
        hasPendingTap &&
            downTimeMs - lastTapUpTime in 0..timeoutMs &&
            (downPosition - lastTapPosition).getDistance() <= slopPx

    /** 记录一次轻点（抬手时调用）。 */
    fun recordTap(upTimeMs: Long, upPosition: Offset) {
        hasPendingTap = true
        lastTapUpTime = upTimeMs
        lastTapPosition = upPosition
    }

    /** 清空双击候选（拖拽/长按/多指结束、或双击已被消费）。 */
    fun clearPendingTap() {
        hasPendingTap = false
    }

    fun onTap() {
        if (isLocked()) {
            onPeekUnlockButton()
        } else {
            onToggleControls()
        }
    }

    fun onDoubleTap(position: Offset) {
        // 双击一旦成立，上一击就不再是候选：否则紧接着的第三下会被误判成新的第二击。
        clearPendingTap()
        // 锁屏时画面手势整体失效，与旧版 View 实现一致：锁屏后只保留单击呼出解锁按钮，
        // 双击 seek / 双击暂停都不该在"已锁"的画面上生效。
        if (isLocked()) return
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
            // Show ripple effect and fast-forward/rewind. The overlay replays the ripple
            // for every new token and clears the state once it finished fading out.
            val forward = position.x > viewCenterX
            ripple = RippleIndicator(
                x = position.x,
                y = position.y,
                token = ++rippleToken,
                label = seekStepLabel(forward),
            )
            if (forward) onFastForward() else onRewind()
        }

        // 双击是「对画面做的事」，不该顺手把控件栏叫出来：那会让紧接着的双击变成
        // 「第一下收控件、第二下才开始计数」，用户体感就是「双击要按三下」。
        // 这里反过来——有控件就收起，让出画面给反馈图标。
        onHideControls()
    }

    /**
     * 长按开始（由手势探测器的长按回调触发，超过系统长按阈值且未发生拖拽时）。
     */
    fun onLongPressStarted() {
        if (isLocked() || !appPreferences.exoPlayerAllowPressSpeedUp) return
        isOnPressingSpeedUp = true
        speedBoostActive = true
        onPressSpeedUp(true)
    }

    /** 隐藏双击播放/暂停浮层（退场动画结束后由覆盖层调用）。 */
    fun hideDoubleTapToggle() {
        doubleTapToggle = null
    }

    /** 隐藏双击 seek 涟漪（退场动画结束后由覆盖层调用）。 */
    fun hideRipple() {
        ripple = null
    }

    /**
     * 按压结束（抬起或取消）：释放长按倍速。
     */
    fun onPressReleased() {
        releaseSpeedUp()
    }

    // endregion

    // region drags

    fun onDragStart(position: Offset) {
        // A drag supersedes the long-press speed-up started by the tap detector.
        releaseSpeedUp()

        // 上一次手势若因系统抢占而没走到 finishGesture，亮度改动在这里补一次落盘。
        persistBrightnessIfChanged()

        dragStartPosition = position
        currentGesture = GestureDirection.NONE
        swipeGestureValueTracker = -1f
        // 每次手势重新读取输出设备与窗口状态：音量被其他应用改动、蓝牙切换都发生在手势之间。
        cachedMaxVolume = -1
        lastAppliedVolume = -1
        lastShownVolume = -1
        lastAppliedBrightnessPercent = -1
        brightnessChanged = false
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
        if (isLocked()) return
        val size = sizeProvider()
        if (size.width == 0 || size.height == 0) {
            return
        }
        // Pinch zoom is handled by the transform detector; ignore drags while multiple
        // pointers are down (mirrors the old per-event pointerCount dispatch).
        if (activePointerCount > 1) {
            return
        }
        val start = dragStartPosition
        if (start == null) {
            return
        }

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
                else -> {
                    return
                }
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
            val maxVolume = cachedMaxVolume.takeIf { it > 0 } ?: run {
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).also { cachedMaxVolume = it }
            }
            // 只在本手势的第一个事件读一次当前音量；之后由累加器跟踪，避免每帧一次
            // getStreamVolume（Binder IPC）。
            if (swipeGestureValueTracker == -1f) {
                swipeGestureValueTracker = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }

            swipeGestureValueTracker += ratioChange * maxVolume

            val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
            // 触摸事件的细分变化通常不跨整数档位：档位没变时写系统音量和重建 HUD 都不会
            // 产生任何可见效果，只会各多一次 IPC / 重组。
            if (toSet != lastAppliedVolume) {
                lastAppliedVolume = toSet
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)
            }
            if (toSet == lastShownVolume) return
            lastShownVolume = toSet

            indicator = VolumeBrightnessIndicator(
                // 0% 还画着「有声波」的喇叭会自相矛盾，静音时换成叉号。
                iconRes = if (toSet == 0) {
                    R.drawable.ic_volume_off_white_24dp
                } else {
                    R.drawable.ic_volume_white_24dp
                },
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
            // 亮度按 1% 量化后再写窗口：`Window.brightness` 的 setter 会触发一次
            // updateViewLayout（Binder IPC + WindowManagerService 重排），而 120Hz 屏幕上
            // 触摸事件可达每秒上百个，亚百分比的变化既看不出来、成本又是每事件一次。
            // 量化后的整数百分比同时也是 HUD 文案与偏好里保存的值，三者始终一致。
            val percent = (swipeGestureValueTracker * Constants.PERCENT_MAX).roundToInt()
                .coerceIn(0, Constants.PERCENT_MAX)
            if (percent == lastAppliedBrightnessPercent) return
            lastAppliedBrightnessPercent = percent

            val appliedBrightness = percent / Constants.PERCENT_MAX.toFloat()
            window.brightness = appliedBrightness
            brightnessChanged = true

            indicator = VolumeBrightnessIndicator(
                iconRes = R.drawable.ic_brightness_white_24dp,
                progress = appliedBrightness,
                text = String.format(brightnessTextFormat, percent),
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

    /**
     * 「记住亮度」在手势结束时落盘一次。
     *
     * 原先每个触摸事件都会写一次 DataStore：每次 edit 都会重写整份偏好快照，并让所有
     * `dataStore.data` 派生 Flow 重新发射，而手指移动期间这些写入的收益为零。
     */
    private fun persistBrightnessIfChanged() {
        if (!brightnessChanged) return
        brightnessChanged = false
        if (appPreferences.exoPlayerRememberBrightness) {
            appPreferences.exoPlayerBrightness = lastAppliedBrightnessPercent / Constants.PERCENT_MAX.toFloat()
        }
    }

    private fun finishGesture() {
        persistBrightnessIfChanged()

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
            speedBoostActive = false
            onPressSpeedUp(false)
        }
    }

    // endregion

    // region zoom

    fun onZoom(zoom: Float) {
        if (isLocked() || !isLandscape()) return
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

/** 创建并记住 [PlayerGestureHandler]。 */
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
    onHideControls: () -> Unit,
    seekStepLabel: (forward: Boolean) -> String,
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
            onHideControls = onHideControls,
            seekStepLabel = seekStepLabel,
        )
    }
}
