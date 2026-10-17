package org.jellyfin.mobile.ui.screens.mpvcompare

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.mpv.bench.ComparedFrame
import org.jellyfin.mobile.player.mpv.bench.FrameComparison
import org.jellyfin.mobile.player.mpv.bench.RawFrame
import kotlin.math.roundToInt

/**
 * Comparison views shared by every mpv configuration comparison entry point.
 *
 * They all take a rendered frame and an optional difference overlay, so the screens can differ in
 * how the user reaches a comparison without differing in what a comparison looks like.
 */

/** Scale steps offered in the comparison screens. */
val COMPARE_ZOOM_STEPS = listOf(1f, 2f, 4f)

/** Converts a rendered frame into something Compose can draw. */
fun RawFrame.toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()

/**
 * Swipe comparison: the baseline frame lies underneath, the candidate is drawn over it up to the
 * handle, so dragging the seam reveals one on top of the other.
 *
 * When [difference] is provided the comparison switches to the difference overlay instead of the
 * candidate: a spatial swipe only helps when the change is spatially local, while the overlay also
 * shows a difference that covers the whole frame. The overlay already spans the full frame, so the
 * seam is hidden there - dragging it would not reveal anything.
 */
@Composable
fun SwipeCompare(
    baseline: ImageBitmap,
    candidate: ImageBitmap,
    difference: ImageBitmap?,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    var dividerFraction by remember { mutableFloatStateOf(0.5f) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        FrameImage(
            bitmap = baseline,
            description = stringResource(R.string.mpv_compare_baseline_frame),
            zoom = zoom,
            modifier = Modifier.fillMaxSize(),
        )
        if (difference != null) {
            FrameImage(
                bitmap = difference,
                description = stringResource(R.string.mpv_compare_difference_frame),
                zoom = zoom,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            FrameImage(
                bitmap = candidate,
                description = stringResource(R.string.mpv_compare_candidate_frame),
                zoom = zoom,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToFraction(dividerFraction),
            )
            CompareDivider(
                fraction = dividerFraction,
                onDrag = { delta -> dividerFraction = (dividerFraction + delta / widthPx).coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * dividerFraction - 12.dp),
            )
        }
    }
}

/** A frame drawn with [ContentScale.Fit] and an optional static zoom. */
@Composable
private fun FrameImage(
    bitmap: ImageBitmap,
    description: String,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    val zoomModifier = if (zoom == 1f) {
        Modifier
    } else {
        Modifier.drawWithContent {
            scale(zoom, pivot = Offset(size.width / 2, size.height / 2)) { this@drawWithContent.drawContent() }
        }
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier.then(zoomModifier),
    )
}

/** Clips the drawn content to the left [fraction] of the layout. */
private fun Modifier.clipToFraction(fraction: Float): Modifier = drawWithContent {
    clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
}

/** The draggable seam of [SwipeCompare]. */
@Composable
private fun CompareDivider(
    fraction: Float,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount -> onDrag(dragAmount) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

/**
 * Shows both frames on top of each other, alternating on tap.
 *
 * For differences a swipe cannot show because they are not spatially separated - a different
 * scaling kernel or debanding changes the same pixels everywhere - flicking between the two is the
 * clearest signal.
 */
@Composable
fun BlinkCompare(
    baseline: ImageBitmap,
    candidate: ImageBitmap,
    showBaseline: Boolean,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    FrameImage(
        bitmap = if (showBaseline) baseline else candidate,
        description = if (showBaseline) {
            stringResource(R.string.mpv_compare_baseline_frame)
        } else {
            stringResource(R.string.mpv_compare_candidate_frame)
        },
        zoom = zoom,
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    )
}

/** Verdict and metrics of one compared change. */
@Composable
fun ComparisonSummary(
    compared: ComparedFrame,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val comparison = compared.comparison
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mpv_compare_change_position, index + 1, total),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            if (comparison != null) {
                VerdictChip(comparison.verdict, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            text = compared.candidate.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        val failure = compared.frame.failure
        val detail = when {
            failure != null -> failure
            comparison == null -> stringResource(R.string.mpv_compare_no_result)
            comparison.isIdentical -> stringResource(R.string.mpv_compare_delta_identical)
            else -> stringResource(
                R.string.mpv_compare_delta_value,
                comparison.meanAbsoluteDelta.roundToInt(),
            )
        }
        Text(
            text = detail,
            color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Colour-coded verdict label. */
@Composable
fun VerdictChip(verdict: FrameComparison.Verdict, modifier: Modifier = Modifier) {
    val (labelRes, color) = when (verdict) {
        FrameComparison.Verdict.IDENTICAL ->
            R.string.mpv_compare_verdict_identical to Color(0xFF4CAF50)
        FrameComparison.Verdict.NEGLIGIBLE ->
            R.string.mpv_compare_verdict_negligible to Color(0xFF8BC34A)
        FrameComparison.Verdict.SUBTLE ->
            R.string.mpv_compare_verdict_subtle to Color(0xFFFFB300)
        FrameComparison.Verdict.VISIBLE ->
            R.string.mpv_compare_verdict_visible to Color(0xFFE53935)
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            text = stringResource(labelRes),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
