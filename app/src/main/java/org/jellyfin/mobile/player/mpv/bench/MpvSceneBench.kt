package org.jellyfin.mobile.player.mpv.bench

/**
 * A frozen scene: everything that must be identical for a frame rendered with one configuration
 * to be comparable with the same frame rendered under another.
 *
 * The values are captured while playback is still running ([SceneSpec.capture]) and are never
 * re-read afterwards, so a seek, a new episode or a rotation cannot invalidate a comparison that
 * is already in flight.
 */
data class SceneSpec(
    /** Media URL as handed to mpv, so the benchmark decodes the same bytes. */
    val sourceUrl: String,
    /** Position in the media, in milliseconds. */
    val positionMs: Long,
    /** Viewport the frame is rendered into; the output resolution part of the render config. */
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    val aspectRatio: Float
        get() = if (viewportHeight <= 0) 1f else viewportWidth.toFloat() / viewportHeight
}

/**
 * Result of rendering [spec] under one configuration.
 *
 * [failure] is part of the model on purpose: a candidate configuration can legitimately fail to
 * produce a frame (mpv rejected the options, the decoder gave up), and the comparison UI has to
 * say so instead of silently showing nothing.
 */
data class SceneFrame(
    val candidate: ConfigCandidate,
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
}

/**
 * Renders one frozen frame of a media scene under a given mpv configuration.
 *
 * This is the seam the whole feature is built on: benchmarking logic never touches a player, a
 * surface or the native library, so it can be exercised without Android UI and a different
 * renderer (e.g. a software one) can be dropped in without the callers noticing.
 */
interface MpvSceneRenderer {
    /**
     * Renders [spec] once for every candidate, in order, and returns the resulting frames.
     *
     * Implementations must be safe to call from a background thread and must not keep state
     * between calls.
     */
    fun render(spec: SceneSpec, candidates: List<ConfigCandidate>): SceneCapture
}
