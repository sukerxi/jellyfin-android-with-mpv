package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.TrickplayState
import kotlin.math.roundToInt

/**
 * 阶段 5：trickplay 缩略图预览（替代原 exo_player_control_view.xml 中的 trickplay_container）。
 *
 * 位置由 [TrickplayState.fraction] 与调用方提供的进度条宽度计算：水平居中于当前 scrub
 * 位置（并夹在进度条范围内），垂直位于进度条上方 8dp。取图逻辑见
 * [org.jellyfin.mobile.player.ui.TrickplayHelper]。
 */

private val TrickplayBackground = Color(0xFF282828)
private val TrickplayTextShadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 6f)
private val TrickplayMarginBottom = 8.dp
private val TrickplayChapterTextStyle = TextStyle(fontSize = 14.sp, shadow = TrickplayTextShadow)
private val TrickplayTimeTextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, shadow = TrickplayTextShadow)

@Composable
fun TrickplayPreview(
    state: TrickplayState,
    seekBarWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    // The old implementation only revealed the container once an image arrived
    val bitmap = if (state.visible) state.thumbnail else null
    if (bitmap == null || state.thumbnailWidthPx <= 0 || seekBarWidthPx <= 0) return

    val density = LocalDensity.current
    val thumbnailHeightPx = with(density) { dimensionResource(R.dimen.trickplay_thumbnail_height).toPx() }
    val thumbnailWidthPx = state.thumbnailWidthPx.toFloat()
    val thumbnailWidthDp = with(density) { thumbnailWidthPx.toDp() }
    val thumbnailHeightDp = dimensionResource(R.dimen.trickplay_thumbnail_height)
    val marginBottomPx = with(density) { TrickplayMarginBottom.toPx() }

    Box(
        modifier = modifier
            .offset {
                val x = (state.fraction * seekBarWidthPx - thumbnailWidthPx / 2f)
                    .coerceIn(0f, (seekBarWidthPx - thumbnailWidthPx).coerceAtLeast(0f))
                val y = -(thumbnailHeightPx + marginBottomPx)
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .size(width = thumbnailWidthDp, height = thumbnailHeightDp),
    ) {
        Surface(
            color = TrickplayBackground,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    if (!state.chapterName.isNullOrEmpty()) {
                        Text(
                            text = state.chapterName,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TrickplayChapterTextStyle,
                        )
                    }
                    Text(
                        text = state.timeText,
                        color = Color.White,
                        style = TrickplayTimeTextStyle,
                    )
                }
            }
        }
    }
}
