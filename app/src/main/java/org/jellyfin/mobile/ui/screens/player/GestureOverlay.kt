package org.jellyfin.mobile.ui.screens.player

import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            .pointerInput(handler) {
                detectDragGestures(
                    onDragStart = { offset -> handler.onDragStart(offset) },
                    onDragEnd = { handler.onDragEnd() },
                    onDragCancel = { handler.onDragCancel() },
                ) { change, dragAmount ->
                    handler.onDrag(change.position, dragAmount)
                }
            }
            .pointerInput(handler) {
                detectTransformGestures { _, _, zoom, _ ->
                    handler.onZoom(zoom)
                    false
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

    // Double tap seek ripple (circle covering the tapped half of the screen)
    handler.ripple?.let { ripple ->
        if (surfaceSize.width > 0) {
            val density = LocalDensity.current
            val centerX = surfaceSize.width / 2f
            val centerY = surfaceSize.height / 2f
            var appeared by remember(ripple.token) { mutableStateOf(false) }
            LaunchedEffect(ripple.token) { appeared = true }
            val alpha by animateFloatAsState(
                targetValue = if (appeared) IndicatorRippleColor.alpha else 0f,
                animationSpec = tween(durationMillis = Constants.DOUBLE_TAP_RIPPLE_DURATION_MS.toInt()),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = if (ripple.rightSide) centerX.roundToInt() else 0,
                            y = (centerY - centerX / 2f).roundToInt(),
                        )
                    }
                    .size(with(density) { centerX.toDp() })
                    .background(IndicatorRippleColor.copy(alpha = alpha), CircleShape),
            )
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
