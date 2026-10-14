package org.jellyfin.mobile.ui.screens.player

import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.theme.JellyfinPrimary
import org.jellyfin.mobile.utils.Constants

/**
 * 阶段 5：手势层。挂载指针输入（单击/双击/长按/拖拽/缩放）并渲染
 * 亮度/音量、横向 seek、双击反馈等浮层（全部由 [PlayerGestureHandler] 的状态驱动）。
 */

private val IndicatorBackground = Color(0xCC000000)
private val IndicatorTrackColor = Color.White.copy(alpha = 0.24f)
private val SeekPositionTextColor = Color(0xFFAAAAAA)
private val IndicatorWidth = 160.dp
private val DoubleTapToggleSize = 96.dp

@Composable
fun GestureOverlay(
    handler: PlayerGestureHandler,
    onSurfaceSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                surfaceSize = size
                onSurfaceSizeChanged(size)
            }
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
            .pointerInput(handler) {
                detectTapGestures(
                    onPress = {
                        handler.startLongPressDetection(longPressTimeoutMs)
                        tryAwaitRelease()
                        handler.onPressReleased()
                    },
                    onTap = { handler.onTap() },
                    onDoubleTap = { offset -> handler.onDoubleTap(offset) },
                )
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
            //   keep working through detectTapGestures.
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
            },
    ) {
        GestureIndicators(handler = handler, surfaceSize = surfaceSize)
    }
}

@Composable
private fun BoxScope.GestureIndicators(
    handler: PlayerGestureHandler,
    surfaceSize: IntSize,
) {
    // Brightness / volume indicator
    handler.indicator?.let { indicator ->
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(IndicatorWidth)
                .background(IndicatorBackground, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(indicator.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { indicator.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = JellyfinPrimary,
                    trackColor = IndicatorTrackColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = indicator.text,
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
    }

    // Horizontal seek indicator
    handler.seekIndicator?.let { seek ->
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(IndicatorWidth)
                .background(IndicatorBackground, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(
                        if (seek.forward) R.drawable.ic_fast_forward_black_32dp else R.drawable.ic_rewind_black_32dp,
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .padding(top = 8.dp),
                    tint = Color.White,
                )
                Text(
                    text = seek.seekText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = seek.positionText,
                    color = SeekPositionTextColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                LinearProgressIndicator(
                    progress = { seek.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                        .height(4.dp),
                    color = JellyfinPrimary,
                    trackColor = IndicatorTrackColor,
                )
            }
        }
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
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (toggle.willPause) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 双击 seek 的涟漪反馈：被点击的那半屏上的一圈 Material 涟漪。
 *
 * 旧实现给 `seek_ripple_overlay` 的 RippleDrawable 设 bounds（被点击的半屏，半屏宽的正方形、
 * 垂直居中）和 hotspot（点击点），压住 [Constants.DOUBLE_TAP_RIPPLE_DURATION_MS] 后松开，
 * 退场动画由 drawable 自己完成。这里用 Material 的 [ripple] 表达同一件事：一块覆盖该半屏的
 * 不可见方块，由 indication 在按压点画涟漪。
 */
@Composable
fun SeekRippleOverlay(
    trigger: RippleIndicator?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trigger == null) return
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // 旧代码压住 drawable 一段时间再松开触发退场动画，这里保持同样的按压时长
    LaunchedEffect(trigger.token) {
        delay(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS)
        onFinished()
    }

    Box(modifier = modifier.onSizeChanged { surfaceSize = it }) {
        val halfWidthPx = surfaceSize.width / 2f
        if (halfWidthPx == 0f) return@Box
        val isFastForward = trigger.x > halfWidthPx

        // hotspot 是点击点在该半屏内的位置（旧代码传给 drawable 的是视图内绝对坐标，
        // 由 RippleDrawable 换算到 bounds 内，这里手工换算）
        val boundsLeftPx = if (isFastForward) halfWidthPx else 0f
        val boundsTopPx = surfaceSize.height / 2f - halfWidthPx / 2f
        val hotspot = Offset(trigger.x - boundsLeftPx, trigger.y - boundsTopPx)

        val interactionSource = remember { MutableInteractionSource() }
        LaunchedEffect(trigger.token, interactionSource) {
            val press = PressInteraction.Press(hotspot)
            interactionSource.emit(press)
            delay(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS)
            interactionSource.emit(PressInteraction.Release(press))
        }

        // 被点击的半屏：半屏宽的正方形，垂直居中
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = if (isFastForward) halfWidthPx.roundToInt() else 0,
                        y = (surfaceSize.height / 2f - halfWidthPx / 2f).roundToInt(),
                    )
                }
                .size(with(density) { halfWidthPx.toDp() })
                .clipToBounds()
                .indication(interactionSource = interactionSource, indication = ripple()),
        )
    }
}
