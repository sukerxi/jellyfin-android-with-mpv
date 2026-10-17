package org.jellyfin.mobile.player.mpv.bench

/**
 * A rendered candidate together with how far it is from the baseline frame.
 *
 * The UI only ever reads this: it never sees the raw pixel arrays, which keeps the screens free of
 * image processing and the processing free of Compose.
 */
data class ComparedFrame(
    val candidate: BenchCandidate,
    val frame: SceneFrame,
    /** Delta against the baseline, or null when either frame failed to render. */
    val comparison: FrameComparison?,
)

/**
 * What a comparison is about.
 *
 * Both modes render the same frozen frame and use the same comparison and display code; they only
 * differ in what the candidates are.
 */
enum class CompareMode {
    /** One `mpv.conf` against the saved one, one change at a time. */
    CONFIG,

    /** mpv against ExoPlayer: same source, same position, two decoder stacks. */
    ENGINE,
}

/**
 * Everything a comparison screen needs.
 *
 * The deltas are computed once, when a run finishes (see [MpvConfigCompareController]), and not
 * while the UI reads this: comparing two full-screen frames is a per-pixel loop over millions of
 * pixels, and recomputing it on every recomposition stalled the screen.
 *
 * @param mode what the candidates of the next run are; switching it drops the previous result
 * @param spec frozen scene, null before a scene has been captured
 * @param baseline the frame rendered with the configuration the user had before the run
 * @param compared every change of the edited configuration, in config-file order
 * @param failure first reason a frame could not be rendered, for runs that produced nothing
 */
data class MpvConfigCompareState(
    val mode: CompareMode = CompareMode.CONFIG,
    val spec: SceneSpec? = null,
    val isRunning: Boolean = false,
    /** Frames finished so far and the number of frames this run will render. */
    val completed: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val baseline: RawFrame? = null,
    val compared: List<ComparedFrame> = emptyList(),
    val failure: String? = null,
) {
    /** Whether the baseline frame rendered, i.e. whether there is something to compare against. */
    val hasResult: Boolean get() = baseline != null

    /** Whether at least one change produced a visually different frame. */
    val hasVisibleDifference: Boolean
        get() = compared.any { it.comparison?.verdict == FrameComparison.Verdict.VISIBLE }
}
