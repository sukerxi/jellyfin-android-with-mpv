package org.jellyfin.mobile.player.mpv.bench

import android.graphics.Bitmap
import androidx.media3.common.MediaItem

/**
 * A frozen scene: everything that must be identical for a frame rendered with one configuration
 * to be comparable with the same frame rendered under another.
 *
 * The values are captured while playback is still running ([PlaybackSceneCapture]) and are never
 * re-read afterwards, so a seek, a new episode or a rotation cannot invalidate a comparison that
 * is already in flight.
 */
data class SceneSpec(
    /** Media URL as handed to mpv, so the benchmark decodes the same bytes. */
    val sourceUrl: String,
    /** Position in the media, in milliseconds. */
    val positionMs: Long,
    /**
     * Viewport the frame is rendered into, in **pixels**: the output resolution part of the render
     * configuration. dp would be wrong - mpv scales the video into the size it is told, so a dp
     * viewport renders a smaller frame with a different aspect.
     */
    val viewportWidth: Int,
    val viewportHeight: Int,
    /**
     * Decoded size of the video as the server declared it, 0 when unknown.
     *
     * ExoPlayer needs the size *before* it can be given a surface (it does not report a size until
     * a video output exists, and it may not even leave buffering without one), so the declared size
     * is what the ExoPlayer render target is created from. The size the player reports after
     * rendering still wins when it differs - see [ExoFrameRenderer].
     */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /**
     * The same source described for ExoPlayer, so an engine comparison decodes the identical bytes
     * under the identical authorization. Null when the scene could not be described that way, which
     * an engine comparison reports instead of comparing against nothing.
     */
    val mediaItem: MediaItem? = null,
) {
    init {
        require(viewportWidth > 0 && viewportHeight > 0) {
            "Scene viewport must be positive, was ${viewportWidth}x$viewportHeight"
        }
    }

    val aspectRatio: Float
        get() = viewportWidth.toFloat() / viewportHeight
}

/**
 * Result of rendering [spec] under one configuration.
 *
 * [failure] is part of the model on purpose: a candidate configuration can legitimately fail to
 * produce a frame (mpv rejected the options, the decoder gave up), and the comparison UI has to
 * say so instead of silently showing nothing.
 */
data class SceneFrame(
    val candidate: BenchCandidate,
    val image: RawFrame?,
    val failure: String? = null,
    /**
     * What the video output actually ran with, read back from mpv: the scaler options, `hwdec`,
     * the source size, the render size, and every other option the candidate asked for. A
     * comparison that comes out identical is otherwise indistinguishable from an option that was
     * silently overridden.
     */
    val effective: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean get() = image != null
}

/** All frames rendered for one benchmark run. */
data class SceneCapture(
    val spec: SceneSpec,
    val frames: List<SceneFrame>,
) {
    /** Frames that produced an image, in candidate order. */
    val rendered: List<SceneFrame> get() = frames.filter { it.image != null }

    /** First failure of the run, so the UI can explain a run that produced nothing. */
    val firstFailure: String? get() = frames.firstOrNull { it.failure != null }?.failure
}

/**
 * Which renderer produces a candidate's frame.
 *
 * Both engines decode the same bytes of the same source at the same position; they differ in
 * everything after that (demuxer, decoder, scaling, colour conversion), which is exactly what an
 * engine comparison is supposed to make visible.
 */
enum class RenderEngine {
    /** A throwaway mpv instance using the candidate's `mpv.conf` text. */
    MPV,

    /** The app's other player stack, media3 ExoPlayer. */
    EXOPLAYER,
}

/** A rendered frame plus what the renderer actually ran with, as reported by the engine. */
internal data class RenderedFrame(
    val frame: RawFrame,
    val effective: Map<String, String>,
)

/**
 * Copies the bitmap into the flat pixel array the comparison works on.
 *
 * The bitmap is recycled right away: a full-screen frame is ~10 MB, and a run holds one per
 * candidate only as pixels.
 */
internal fun Bitmap.toRawFrame(): RawFrame {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val frame = RawFrame(width, height, pixels)
    recycle()
    return frame
}

/**
 * Renders one frozen frame of a media scene under a given mpv configuration.
 *
 * This is the seam the whole feature is built on: benchmarking logic never touches the *player*,
 * its surface or its `mpv.conf`, so a comparison cannot disturb playback - and a different renderer
 * (ExoPlayer, see [RenderEngine]) can be dropped in without the callers noticing.
 */
interface MpvSceneRenderer {
    /**
     * Renders [spec] once for every candidate, in order, and returns the resulting frames.
     *
     * Implementations must be safe to call from a background thread and must not keep state
     * between calls. [onProgress] is called after each candidate with the number of finished
     * frames and the total, so the UI can show how far a long run has got.
     */
    fun render(
        spec: SceneSpec,
        candidates: List<BenchCandidate>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): SceneCapture
}
