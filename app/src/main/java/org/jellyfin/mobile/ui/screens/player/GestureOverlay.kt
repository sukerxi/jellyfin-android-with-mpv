package org.jellyfin.mobile.ui.screens.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.theme.JellyfinPrimary
import org.jellyfin.mobile.utils.Constants

/**
 * 阶段 5：手势层。挂载指针输入（单击/双击/长按/拖拽/缩放）并渲染
 * 亮度/音量、横向 seek、双击反馈等浮层（全部由 [PlayerGestureHandler] 的状态驱动）。
 */

/**
 * 手势 HUD 的统一视觉：深色玻璃卡片 + 1dp 细描边 + 圆角，亮度/音量、横向 seek、倍速角标共用。
 * 颜色与描边定义见 [PlayerGlassBackground]。
 */
private val HudAccentGradient = Brush.horizontalGradient(
    listOf(JellyfinPrimary.copy(alpha = 0.88f), Color(0xFF6FD7F7)),
)
private val HudWidth = 176.dp
private val HudCornerRadius = 20.dp
private val HudIconSize = 30.dp
private val HudProgressHeight = 5.dp
private val DoubleTapToggleSize = 88.dp

/** HUD 卡片进出场时长（毫秒）：入场略快，退场稍慢，松手时卡片是"淡走"而不是"凭空消失"。 */
private const val HUD_ENTER_MS = 120
private const val HUD_EXIT_MS = 170

/** 双击跳转量文字（`+10s` / `-10s`）。 */
private val RippleLabelWidth = 160.dp
private val RippleLabelLift = 56.dp

@Composable
fun GestureOverlay(
    handler: PlayerGestureHandler,
    onSurfaceSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只负责接收指针输入、上报手势区域大小；指示器由 [GestureIndicatorsOverlay] 画在控件层之上。
    Box(
        modifier = modifier
            .onSizeChanged(onSurfaceSizeChanged)
            .playerGestureInput(handler),
    )
}

/**
 * 手势指示浮层：亮度/音量、横向 seek、双击跳转量、长按倍速角标。
 *
 * 画在控件层之上：控件可见时也能滑动 seek / 调亮度音量，指示器若还在控件层下面，会被
 * 上下渐变压暗，并和中央那排按钮叠在一起。这一层没有任何指针输入，不会挡住下面的控件。
 */
@Composable
fun GestureIndicatorsOverlay(
    handler: PlayerGestureHandler,
    modifier: Modifier = Modifier,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier.onSizeChanged { surfaceSize = it },
    ) {
        GestureIndicators(handler = handler, surfaceSize = surfaceSize)
    }
}

/**
 * 播放器手势输入：单击/双击/长按、双指缩放、单指拖拽（横向 seek、纵向亮度音量）。
 *
 * 手势层与控件层的空白处共用这一份实现：控件可见时命中测试只落到控件层，同级的手势层
 * 根本收不到事件 —— 控件层空白处不自己挂一遍的话，控件显示期间滑动 seek / 亮度音量、
 * 捏合缩放、长按倍速就会全部失效。
 */
fun Modifier.playerGestureInput(handler: PlayerGestureHandler): Modifier = this
    // Non-consuming pointer-count observer: while a pinch (2+ pointers) is in
    // progress, drags are ignored (mirrors the old per-event pointerCount dispatch).
    .pointerInput(handler) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                handler.onPointerCountChanged(event.changes.count { change -> change.pressed })
            }
        }
    }
    // Tap / double-tap / long-press.
    //
    // 这里没有用 detectTapGestures：只要注册了 onDoubleTap，它就必须等双击超时
    // （约 300ms）才派发 onTap，单击切换控件会一直「慢半拍」。这里改成抬手即响应，
    // 若在双击窗口内收到第二击，再按双击语义覆盖。代价只在「控件隐藏时双击 seek」
    // 这一种情况：第一击会先亮一下控件，第二击随即把它收起——玩家已经抬手在 seek，
    // 这比每次单击都延迟 300ms 更可接受。
    .pointerInput(handler) {
        val doubleTapSlop = 48.dp.toPx()
        var lastTapUpTime = -1L
        var lastTapPosition = Offset.Zero
        var hasLastTap = false

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 时间与位移都在窗口内，才把这次按下当作上一击的第二击。
            val isSecondTap = hasLastTap &&
                (down.uptimeMillis - lastTapUpTime) in 0..viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPosition).getDistance() <= doubleTapSlop

            var canceled = false
            var longPressTriggered = false
            var up: PointerInputChange? = null

            // 第一阶段：长按超时前等抬手 / 拖拽 / 多指。
            // withTimeoutOrNull 是 AwaitPointerEventScope 的成员；这里不能用普通协程的
            // launch + delay —— 指针输入是受限挂起作用域，编译器不允许调用外部 suspend 函数。
            val endedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    // 多指（捏合缩放）不是轻点，交给外层 transform 检测器。
                    if (event.changes.count { it.pressed } > 1) {
                        canceled = true
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        canceled = true
                        break
                    }
                    if (!change.pressed) {
                        up = change
                        break
                    }
                    // 越过 touch slop 即视为拖拽（横向 seek / 亮度音量），不再等抬手。
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        canceled = true
                        break
                    }
                }
                true
            }

            if (endedBeforeTimeout == null) {
                // 长按已生效：继续等抬手/拖拽，但不再产生轻点。
                longPressTriggered = true
                handler.onLongPressStarted()
                while (up == null && !canceled) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) {
                        canceled = true
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        canceled = true
                        break
                    }
                    if (!change.pressed) {
                        up = change
                    } else if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        canceled = true
                        break
                    }
                }
            }

            // 与旧实现一致：任何按压结束都释放长按倍速。
            handler.onPressReleased()

            val upChange = up
            if (upChange != null && !canceled && !longPressTriggered) {
                if (isSecondTap) {
                    hasLastTap = false
                    handler.onDoubleTap(upChange.position)
                } else {
                    // 抬手即响应，不再等双击超时。
                    hasLastTap = true
                    lastTapUpTime = upChange.uptimeMillis
                    lastTapPosition = upChange.position
                    handler.onTap()
                }
            } else {
                // 拖拽/长按/多指结束：清空双击候选，避免下一次轻点被误判成第二击。
                hasLastTap = false
            }
        }
    }
    // Pinch zoom. Must stay OUTSIDE the single-pointer drag detector below:
    // detectTransformGestures consumes every position change once the touch
    // slop is reached, so the drag detector (innermost) claims single-pointer
    // drags first and makes this one cancel itself for them. For a genuine
    // two-pointer pinch the drag detector bails out without consuming, and
    // this detector handles the zoom.
    .pointerInput(handler) {
        detectTransformGestures { _, _, zoom, _ ->
            handler.onZoom(zoom)
            false
        }
    }
    // Single-pointer drag (horizontal seek / vertical brightness & volume).
    // Custom implementation: the stock detectDragGestures cannot be chained
    // with detectTransformGestures on the same node, because the transform
    // detector (innermost in the old ordering) consumed the drag movements
    // before the drag detector could see them, cancelling its touch-slop
    // detection and disabling all swipe gestures. Here the drag detector is
    // the innermost pointerInput and therefore receives events first:
    // - it claims single-pointer drags (consuming the movements so the tap
    //   and transform detectors cancel themselves),
    // - it releases multi-pointer gestures unconsumed so the transform
    //   detector above can handle pinch zoom,
    // - taps/double-taps (no movement past the slop) are never consumed and
    //   keep working through the tap detector above.
    .pointerInput(handler) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            var lastPosition = down.position
            var dragging = false
            // Phase 1: wait for the touch slop to be exceeded, a second
            // pointer (pinch), or the release (plain tap).
            while (!dragging) {
                val event = awaitPointerEvent()
                if (event.changes.count { change -> change.pressed } > 1) {
                    // Multi-touch gesture: leave everything unconsumed so
                    // detectTransformGestures can take over the zoom.
                    return@awaitEachGesture
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val distance = (change.position - down.position).getDistance()
                if (distance > touchSlop) {
                    dragging = true
                    change.consume()
                    handler.onDragStart(down.position)
                    // Report only the portion beyond the slop, mirroring the
                    // old GestureDetector.onScroll / stock detector behavior.
                    val slopPosition = down.position +
                        (change.position - down.position) * (touchSlop / distance)
                    handler.onDrag(change.position, change.position - slopPosition)
                    lastPosition = change.position
                }
            }
            // Phase 2: drag until the pointer is released or canceled.
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    handler.onDragCancel()
                    return@awaitEachGesture
                }
                val dragAmount = change.position - lastPosition
                if (!change.pressed) {
                    if (dragAmount != Offset.Zero) {
                        handler.onDrag(change.position, dragAmount)
                        lastPosition = change.position
                    }
                    handler.onDragEnd()
                    return@awaitEachGesture
                }
                if (dragAmount != Offset.Zero) {
                    handler.onDrag(change.position, dragAmount)
                    lastPosition = change.position
                    change.consume()
                }
            }
        }
    }

@Composable
private fun BoxScope.GestureIndicators(
    handler: PlayerGestureHandler,
    surfaceSize: IntSize,
) {
    // 长按临时倍速：音画已经变快了，但屏幕上原本没有任何反馈，用户不确定是否生效，
    // 于是一直按着等。给一次触感 + 顶部角标，松手前都能看到「正在 N× 快进」。
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(handler.speedBoostActive) {
        if (handler.speedBoostActive) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    AnimatedVisibility(
        visible = handler.speedBoostActive,
        enter = fadeIn(tween(HUD_ENTER_MS)) + scaleIn(tween(HUD_ENTER_MS), initialScale = 0.9f),
        exit = fadeOut(tween(HUD_EXIT_MS)) + scaleOut(tween(HUD_EXIT_MS), targetScale = 0.94f),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 72.dp),
    ) {
        val badgeShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .playerGlass(badgeShape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.playback_speed_boost_active,
                    formatSpeedLabel(Constants.HOLD_SPEEDUP_MULTIPLIER),
                ),
                color = PlayerGlassTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            )
        }
    }

    // Brightness / volume indicator
    HudSlot(value = handler.indicator) { indicator ->
        HudCard {
            Icon(
                painter = painterResource(indicator.iconRes),
                contentDescription = null,
                modifier = Modifier.size(HudIconSize),
                tint = PlayerGlassIconTint,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = indicator.text,
                color = PlayerGlassTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(14.dp))
            HudProgressBar(progress = indicator.progress)
        }
    }

    // Horizontal seek indicator
    HudSlot(value = handler.seekIndicator) { seek ->
        HudCard {
            Icon(
                painter = painterResource(
                    if (seek.forward) R.drawable.ic_gesture_seek_forward_24dp else R.drawable.ic_gesture_seek_rewind_24dp,
                ),
                contentDescription = null,
                modifier = Modifier.size(HudIconSize),
                tint = PlayerGlassIconTint,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = seek.seekText,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                // 等宽数字：拖动过程中「+12:01」不会因为位数变化而左右抖动。
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = seek.positionText,
                color = PlayerGlassTextSecondary,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(modifier = Modifier.height(12.dp))
            HudProgressBar(progress = seek.progress)
        }
    }

    // 双击 seek 的跳转量。涟漪本身只表达「点这里了」，不表达「跳了多远」——步长由服务端
    // 偏好决定（10s / 30s 都可能），所以在涟漪中心上方补一行文字。涟漪消失时一起退场。
    handler.ripple?.let { ripple ->
        val density = LocalDensity.current
        val labelWidthPx = with(density) { RippleLabelWidth.toPx() }
        val labelLiftPx = with(density) { RippleLabelLift.toPx() }
        val maxX = (surfaceSize.width - labelWidthPx).coerceAtLeast(0f)
        Text(
            text = ripple.label,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            // 涟漪上的文字直接压在画面高光上，纯白 28sp 在亮场景里会糊掉，加一层投影兜底。
            style = TextStyle(
                shadow = Shadow(color = Color(0xB3000000), blurRadius = 12f, offset = Offset(0f, 2f)),
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        x = (ripple.x - labelWidthPx / 2f).coerceIn(0f, maxX).roundToInt(),
                        // 抬到触点上方的同时夹在屏内，避免在屏幕顶边双击时文字被裁掉。
                        y = (ripple.y - labelLiftPx).coerceIn(0f, surfaceSize.height.toFloat()).roundToInt(),
                    )
                }
                .width(RippleLabelWidth),
        )
    }

    // Double tap play/pause toggle icon
    handler.doubleTapToggle?.let { toggle ->
        val density = LocalDensity.current
        val overlaySizePx = with(density) { DoubleTapToggleSize.toPx() }
        val maxX = (surfaceSize.width - overlaySizePx).coerceAtLeast(0f)
        val maxY = (surfaceSize.height - overlaySizePx).coerceAtLeast(0f)
        var visible by remember(toggle.token) { mutableStateOf(true) }
        LaunchedEffect(toggle.token) {
            delay(Constants.DOUBLE_TAP_TOGGLE_POP_DURATION_MS + Constants.DOUBLE_TAP_TOGGLE_VISIBLE_DURATION_MS)
            visible = false
            delay(Constants.DOUBLE_TAP_TOGGLE_FADE_DURATION_MS)
            handler.hideDoubleTapToggle()
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Constants.DOUBLE_TAP_TOGGLE_POP_DURATION_MS.toInt())) +
                scaleIn(tween(Constants.DOUBLE_TAP_TOGGLE_POP_DURATION_MS.toInt()), initialScale = 0.7f),
            exit = fadeOut(tween(Constants.DOUBLE_TAP_TOGGLE_FADE_DURATION_MS.toInt())),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        x = (toggle.x - overlaySizePx / 2f).coerceIn(0f, maxX).roundToInt(),
                        y = (toggle.y - overlaySizePx / 2f).coerceIn(0f, maxY).roundToInt(),
                    )
                }
                .size(DoubleTapToggleSize),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(listOf(Color(0xB3000000), Color(0x73000000))),
                        CircleShape,
                    )
                    .border(1.dp, PlayerGlassBorderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (toggle.willPause) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 居中 HUD 卡槽：值变为 null 时不立刻摘掉内容，而是保留最后一帧做淡出 + 微缩。
 *
 * 松手后指示器只停留很短的时间，若到点直接移除，卡片会在注视下瞬间消失；保留内容
 * 再退场，收尾和入场（淡入 + 轻微放大）对称。保留值放在普通数组里，写入不触发重组。
 */
@Composable
private fun <T : Any> BoxScope.HudSlot(
    value: T?,
    content: @Composable (T) -> Unit,
) {
    val retained = remember { arrayOfNulls<Any>(1) }
    if (value != null) retained[0] = value
    @Suppress("UNCHECKED_CAST")
    val shown = (value ?: retained[0]) as T?
    // 初始目标态为 false、下一帧才置 true：直接让 AnimatedVisibility 以 visible = true
    // 首次组合时不会播放入场动画，而第一版 HudAppear 正是靠"先 false 再 true"出场的。
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(value != null) { visibility.targetState = value != null }
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(HUD_ENTER_MS)) + scaleIn(tween(HUD_ENTER_MS), initialScale = 0.94f),
        exit = fadeOut(tween(HUD_EXIT_MS)) + scaleOut(tween(HUD_EXIT_MS), targetScale = 0.96f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        if (shown != null) content(shown)
    }
}

/** 手势 HUD 的统一卡片容器：玻璃底 + 细描边 + 圆角，内容垂直居中。 */
@Composable
private fun HudCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(HudWidth)
            .playerGlass(RoundedCornerShape(HudCornerRadius), shadowElevation = 10.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * 手势 HUD 的进度条：圆头轨道 + 品牌色渐变填充 + 末端小圆点。
 *
 * Material 的 LinearProgressIndicator 是方头、带末端 stop 点，放在这块小卡片里显得粗糙；这里自己画，几行 Canvas 就能对齐卡片的圆角语言。
 */
@Composable
private fun HudProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(HudProgressHeight),
    ) {
        val barRadius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = PlayerGlassTrackColor, cornerRadius = barRadius)
        if (fraction <= 0f) return@Canvas

        val barWidth = (size.width * fraction).coerceIn(size.height, size.width)
        drawRoundRect(
            brush = HudAccentGradient,
            size = Size(barWidth, size.height),
            cornerRadius = barRadius,
        )
        // 末端圆点：进度很低时这一小截几乎不可见，点能让"当前值"始终有个落点。
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = size.height * 0.34f,
            center = Offset(barWidth - size.height / 2f, size.height / 2f),
        )
    }
}

/**
 * 双击 seek 的涟漪反馈。
 *
 * 观感必须和重构前一致：那里是 `seek_ripple_overlay` 上的一层平台 RippleDrawable
 * （`?attr/colorControlHighlight`），给它设 bounds（被点击的半屏，半屏宽的正方形、垂直居中）
 * 和 hotspot（点击点），压住 [Constants.DOUBLE_TAP_RIPPLE_DURATION_MS] 后松开，退场动画由
 * drawable 自己跑。
 *
 * 这里直接渲染**同一个** `?attr/colorControlHighlight`（Compose 的 `ripple()` 取
 * `LocalContentColor`，解析不到主题属性；平台属性则由当前主题解析），几何与时序照搬旧实现，
 * 这样"像不像"就不再是一个需要逐参数去猜的问题。
 */
@Composable
fun SeekRippleOverlay(
    trigger: RippleIndicator?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trigger == null) return
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    // Not a `mutableStateOf`: the view is created by the `factory` during composition, and
    // writing observed state from there would schedule another recomposition for nothing.
    // [LaunchedEffect] reads it after the first frame, when the factory has run.
    val rippleViewHolder = remember { arrayOfNulls<View>(1) }

    Box(modifier = modifier.onSizeChanged { surfaceSize = it }) {
        val width = surfaceSize.width
        val height = surfaceSize.height

        AndroidView(
            factory = { context ->
                // A plain view whose foreground is the platform ripple: rendered by the
                // framework (RenderThread) and free of any Compose gesture handling.
                View(context)
                    .apply { foreground = colorControlHighlightRipple(context) }
                    .also { rippleViewHolder[0] = it }
            },
            // Deliberately no ripple in `update`: it runs on *every* recomposition (the
            // player polls its position every 200ms, so that is constantly), and every call
            // would re-press the drawable, restarting the animation before it could play.
            // The ripple is a one-shot; it is driven by the [LaunchedEffect] below.
            modifier = Modifier.fillMaxSize(),
        )

        // Play the ripple exactly once per double tap, once the surface size is known.
        LaunchedEffect(trigger.token, width, height) {
            if (width == 0 || height == 0) return@LaunchedEffect
            val view = rippleViewHolder[0] ?: return@LaunchedEffect

            // Bounds of the tapped half: half of the view wide, full height, centred
            // vertically (the old code used a viewWidth/2 square centred on the view's
            // vertical centre).
            val viewCenterX = width / 2
            val viewCenterY = height / 2
            val isFastForward = trigger.x.toInt() > viewCenterX
            val left = if (isFastForward) viewCenterX else 0
            val right = if (isFastForward) width else viewCenterX
            val top = viewCenterY - viewCenterX / 2
            val bottom = viewCenterY + viewCenterX / 2

            // The platform re-applies `mForegroundInfo.mDrawable.setBounds(0, 0, w, h)` on
            // every layout pass, so the custom bounds have to be set after the view is laid
            // out - and re-set right before pressing, otherwise a layout pass in between
            // silently replaces them with the full-view bounds.
            view.post {
                view.playRipple(left, top, right, bottom, trigger.x, trigger.y)
            }
        }

        // The overlay is a one-shot visual; clear the trigger once it has faded out.
        LaunchedEffect(trigger.token) {
            delay(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS + RIPPLE_FADE_OUT_DURATION_MS)
            onFinished()
        }
    }
}

/** 涟漪退场动画时长（平台 RippleDrawable 的默认 `rippleExitDuration`）。 */
private const val RIPPLE_FADE_OUT_DURATION_MS = 200L

/**
 * 平台主题 `?attr/colorControlHighlight` 对应的涟漪 drawable，行为与重构前
 * `fragment_player.xml` 里 `seek_ripple_overlay` 的 `android:foreground` 一致。
 */
private fun colorControlHighlightRipple(context: Context): RippleDrawable {
    val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorControlHighlight))
    val color = attributes.getColor(0, Color.White.toArgb())
    attributes.recycle()
    return RippleDrawable(ColorStateList.valueOf(color), null, null)
}

/**
 * 按旧实现的几何与时序播放一次涟漪：设 bounds、hotspot，压住
 * [Constants.DOUBLE_TAP_RIPPLE_DURATION_MS] 后松开，退场动画由 drawable 自己完成。
 */
private fun View.playRipple(left: Int, top: Int, right: Int, bottom: Int, x: Float, y: Float) {
    val ripple = foreground as? RippleDrawable ?: return
    ripple.setBounds(left, top, right, bottom)
    ripple.setHotspot(x, y)
    ripple.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
    postDelayed({ ripple.state = IntArray(0) }, Constants.DOUBLE_TAP_RIPPLE_DURATION_MS)
}
