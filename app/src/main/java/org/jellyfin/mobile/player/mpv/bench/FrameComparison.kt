package org.jellyfin.mobile.player.mpv.bench

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * A decoded frame with its pixels, kept out of the Android widget layer.
 *
 * The pixels are stored as a plain ARGB array so that comparison is pure integer arithmetic and
 * can run off the main thread while the bitmaps handed to the UI are built separately.
 */
class RawFrame(
    val width: Int,
    val height: Int,
    /**
     * Pixel data as one packed [Int] per pixel, i.e. [width] * [height] entries.
     */
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height) { "Pixel count does not match ${width}x$height" }
    }

    /**
     * 64-bit FNV-1a hash over the pixels. Only used as a cheap "is this the same frame at all"
     * signal; the actual delta is computed by [FrameComparison.compare].
     */
    val signature: Long by lazy {
        var hash = FNV_OFFSET_BASIS
        for (pixel in pixels) {
            hash = (hash xor (pixel.toLong() and 0xFFFFFFFFL)) * FNV_PRIME
        }
        hash
    }

    fun toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    private companion object {
        const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
        const val FNV_PRIME = 1099511628211L
    }
}

/**
 * How much two frames differ.
 *
 * [meanAbsoluteDelta] is the average per-channel absolute difference in 0..255, which is
 * independent of the frame size and therefore comparable between scenes. [difference] is the same
 * delta expanded into an ARGB frame for display, with everything below [DIFFERENCE_THRESHOLD]
 * suppressed so that noise does not light up the whole picture.
 */
data class FrameComparison(
    val sameSignature: Boolean,
    val meanAbsoluteDelta: Double,
    val difference: RawFrame,
) {
    /** True when the configuration produced a frame indistinguishable from the baseline. */
    val isIdentical: Boolean get() = sameSignature || meanAbsoluteDelta < IDENTICAL_DELTA

    /** Human readable verdict for the comparison UI. */
    val verdict: Verdict
        get() = when {
            sameSignature -> Verdict.IDENTICAL
            meanAbsoluteDelta < IDENTICAL_DELTA -> Verdict.NEGLIGIBLE
            meanAbsoluteDelta < VISIBLE_DELTA -> Verdict.SUBTLE
            else -> Verdict.VISIBLE
        }

    enum class Verdict { IDENTICAL, NEGLIGIBLE, SUBTLE, VISIBLE }

    companion object {
        /** Below this, a difference is not distinguishable on a phone screen. */
        const val IDENTICAL_DELTA = 1.0

        /** Above this, the difference is plainly visible. */
        const val VISIBLE_DELTA = 6.0

        /** Deltas below this are not painted into the difference frame. */
        private const val DIFFERENCE_THRESHOLD = 8

        /**
         * Compares two frames. Frames of different sizes are compared over the overlapping
         * region, so a configuration that changes the output resolution still yields a delta
         * instead of an error.
         */
        fun compare(baseline: RawFrame, candidate: RawFrame): FrameComparison {
            val width = minOf(baseline.width, candidate.width)
            val height = minOf(baseline.height, candidate.height)
            val delta = IntArray(width * height)
            var sum = 0L
            for (y in 0 until height) {
                val baselineRow = y * baseline.width
                val candidateRow = y * candidate.width
                for (x in 0 until width) {
                    val a = baseline.pixels[baselineRow + x]
                    val b = candidate.pixels[candidateRow + x]
                    val d = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                        abs((a and 0xFF) - (b and 0xFF))
                    sum += d
                    delta[y * width + x] = d
                }
            }
            val mean = sum.toDouble() / (width * height * 3)
            return FrameComparison(
                sameSignature = baseline.signature == candidate.signature,
                meanAbsoluteDelta = mean,
                difference = buildDifferenceFrame(width, height, delta),
            )
        }

        /** Expands the per-pixel delta into an ARGB frame, painted as a magenta heat map. */
        private fun buildDifferenceFrame(width: Int, height: Int, delta: IntArray): RawFrame {
            val pixels = IntArray(width * height)
            for (i in delta.indices) {
                val value = (delta[i] * 255 / (DIFFERENCE_THRESHOLD * 3)).coerceAtMost(255)
                val alpha = if (delta[i] < DIFFERENCE_THRESHOLD) 0 else 0x40 + value * 3 / 4
                // Magenta on black reads as "this pixel changed" without hiding the frame beneath.
                pixels[i] = (alpha.coerceAtMost(255) shl 24) or (value shl 16) or value
            }
            return RawFrame(width, height, pixels)
        }
    }
}

/**
 * Persists rendered frames and the comparison results derived from them.
 *
 * Everything lives in the app's cache: a benchmark is a throwaway artifact, and the user's own
 * storage must not accumulate screenshots from it.
 */
class MpvFrameStore(private val root: File) {

    /** Writes [frame] as a PNG and returns the file, or null when writing failed. */
    fun save(name: String, frame: RawFrame): File? = try {
        root.mkdirs()
        val file = File(root, "$name.png")
        FileOutputStream(file).use { output ->
            frame.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        file
    } catch (e: Exception) {
        null
    }

    /** Reads back a frame written by [save], or null when the file is missing or unreadable. */
    fun load(file: File): RawFrame? = try {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val frame = RawFrame(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        frame
    } catch (e: Exception) {
        null
    }

    /** Deletes every stored frame (called when a benchmark is replaced or the UI is left). */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }
}
