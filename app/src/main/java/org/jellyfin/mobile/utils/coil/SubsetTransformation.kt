package org.jellyfin.mobile.utils.coil

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * Crops the rectangle at (x, y, width, height) - given in the coordinate space of the
 * *undecoded* image - out of [input].
 *
 * The trickplay request asks for the tile at half resolution, so Coil has already
 * scaled the whole tile image down by the time this runs. The crop rectangle is
 * mapped through that same scale factor, derived from [sourceWidth]/[sourceHeight]
 * (the tile's full pixel size) and the actual input dimensions. All coordinates are
 * clamped to the input bounds, so an image smaller than the logical grid degrades to
 * a safe crop instead of crashing the transformation.
 */
class SubsetTransformation(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) : Transformation() {
    override val cacheKey: String = "$x,$y,$width,$height,$sourceWidth,$sourceHeight"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scaleX = input.width.toFloat() / sourceWidth
        val scaleY = input.height.toFloat() / sourceHeight
        // Equal for a full grid tile (the only layout the crop math supports); the
        // max keeps every coordinate inside the bitmap for a partially filled tile.
        val scale = maxOf(scaleX, scaleY)
        val left = (x * scale).roundToInt().coerceIn(0, input.width - 1)
        val top = (y * scale).roundToInt().coerceIn(0, input.height - 1)
        val right = ((x + width) * scale).roundToInt().coerceIn(left + 1, input.width)
        val bottom = ((y + height) * scale).roundToInt().coerceIn(top + 1, input.height)
        return Bitmap.createBitmap(input, left, top, right - left, bottom - top)
    }
}
