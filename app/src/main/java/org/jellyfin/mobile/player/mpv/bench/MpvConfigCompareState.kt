package org.jellyfin.mobile.player.mpv.bench

/**
 * A rendered candidate together with how far it is from the baseline frame.
 *
 * The UI only ever reads this: it never sees the raw pixel arrays, which keeps the screens free of
 * image processing and the processing free of Compose.
 */
data class ComparedFrame(
    val candidate: ConfigCandidate,
    val frame: SceneFrame,
    /** Delta against the baseline, or null when either frame failed to render. */
    val comparison: FrameComparison?,
)

/**
 * Everything a comparison screen needs.
 *
 * @param spec frozen scene, null before a scene has been captured
 * @param baseline the frame rendered with the configuration the user had before the run
 * @param compared every chunk of the edited configuration, in file order
 */
data class MpvConfigCompareState(
    val spec: SceneSpec? = null,
    val isRunning: Boolean = false,
    val error: String? = null,
    val capture: SceneCapture? = null,
) {
    /** The baseline frame of the last run, if it rendered. */
    val baseline: SceneFrame?
        get() = capture?.frames?.firstOrNull { it.candidate.id == ConfigCandidate.ID_BASELINE && it.isSuccess }

    /**
     * The last run's candidates expressed for display, baseline excluded. A failed candidate is
     * kept in the list so the UI can report which change broke rendering.
     */
    val compared: List<ComparedFrame>
        get() {
            val currentCapture = capture ?: return emptyList()
            val baselineFrame = currentCapture.frames
                .firstOrNull { it.candidate.id == ConfigCandidate.ID_BASELINE }
                ?.image
            return currentCapture.frames
                .filter { it.candidate.id != ConfigCandidate.ID_BASELINE }
                .map { frame ->
                    ComparedFrame(
                        candidate = frame.candidate,
                        frame = frame,
                        comparison = if (baselineFrame != null && frame.image != null) {
                            FrameComparison.compare(baselineFrame, frame.image)
                        } else {
                            null
                        },
                    )
                }
        }

    /** Whether at least one change produced a visually different frame. */
    val hasVisibleDifference: Boolean
        get() = compared.any { it.comparison?.verdict == FrameComparison.Verdict.VISIBLE }

    val totalCount: Int get() = compared.size
}
