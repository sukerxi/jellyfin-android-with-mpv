package org.jellyfin.mobile.player.mpv.bench

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
 * Renders one frozen frame of a media scene under a given mpv configuration.
 *
 * This is the seam the whole feature is built on: benchmarking logic never touches the *player*,
 * its surface or its `mpv.conf`, so a comparison cannot disturb playback - and a different renderer
 * (e.g. a software one) can be dropped in without the callers noticing.
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
