package org.jellyfin.mobile.ui.screens.player

import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.theme.JellyfinPrimary
import org.jellyfin.mobile.utils.Constants
import kotlin.math.roundToInt

/**
 * 阶段 5：手势层。挂载指针输入（单击/双击/长按/拖拽/缩放）并渲染
 * 亮度/音量、横向 seek、双击反馈等浮层（全部由 [PlayerGestureHandler] 的状态驱动）。
 */

private val IndicatorBackground = Color(0xCC000000)
private val IndicatorRippleColor = Color.White.copy(alpha = 0.12f)
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

    // Double tap seek ripple: a soft circle expanding from the tap point and fading out
    // (mirrors the old RippleDrawable hotspot behavior; the previous flat full-half circle
    // that only faded in was barely visible)
    handler.ripple?.let { ripple ->
        if (surfaceSize.width > 0) {
            // Rebuild the animation state for every double tap (new token) so the ripple
            // always expands from scratch instead of resuming the previous one
            key(ripple.token) {
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }
                val progress by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0f,
                    animationSpec = tween(durationMillis = Constants.DOUBLE_TAP_RIPPLE_DURATION_MS.toInt()),
                )
                val maxRadiusPx = surfaceSize.width / 2f
                val center = Offset(ripple.x, ripple.y)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxSize()
                        .drawBehind {
                            val radius = maxRadiusPx * progress
                            val alpha = IndicatorRippleColor.alpha * (1f - progress)
                            if (radius > 0f && alpha > 0f) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        0f to Color.Transparent,
                                        0.55f to IndicatorRippleColor.copy(alpha = alpha),
                                        1f to Color.Transparent,
                                        center = center,
                                        radius = radius,
                                    ),
                                    radius = radius,
                                    center = center,
                                )
                            }
                        },
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
