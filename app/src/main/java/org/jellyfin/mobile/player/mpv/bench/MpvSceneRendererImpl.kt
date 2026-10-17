package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import android.graphics.Bitmap
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders benchmark frames on throwaway mpv instances.
 *
 * One instance per candidate (see [MpvBenchInstance]) is what makes the result trustworthy: mpv
 * only reads `mpv.conf` at startup, so a fresh instance is the only way to render "this exact
 * configuration" without inheriting options from the candidate before it. The cost is one decoder
 * start-up per candidate, which the UI hides behind a progress count.
 *
 * The player instance is never touched, so a comparison cannot leave playback without a surface,
 * with a changed `mpv.conf`, or with an option it did not ask for.
 */
class MpvSceneRendererImpl(private val appContext: Context) : MpvSceneRenderer {

    override fun render(
        spec: SceneSpec,
        candidates: List<BenchCandidate>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): SceneCapture {
        val root = File(appContext.cacheDir, BENCH_DIR).apply { mkdirs() }
        val total = candidates.size
        // Frames are read back as raw pixels and every one of them stays in memory for the diff, so
        // the render size is capped once for the whole run. Every candidate gets the same geometry,
        // which is what makes the comparison readable - and the aspect ratio is preserved, so a
        // capped frame still shows the real shape of the picture.
        val (renderWidth, renderHeight) = renderSizeOf(spec)

        val frames = candidates.mapIndexed { index, candidate ->
            val result = renderWithDeadline(spec, candidate, renderWidth, renderHeight, File(root, "run-$index"), index)
            onProgress(index + 1, total)
            result.fold(
                onSuccess = { image -> SceneFrame(candidate, image) },
                onFailure = { error ->
                    Timber.w(error, "mpv bench: rendering %s failed", candidate.id)
                    SceneFrame(candidate, image = null, failure = error.message ?: "渲染失败")
                },
            )
        }
        return SceneCapture(spec, frames)
    }

    /**
     * Renders one candidate on a thread this method is allowed to walk away from.
     *
     * Everything [renderOne] does ends in a native call (mpv instance start-up, hardware decoding,
     * `glReadPixels`) and none of them can be interrupted from Kotlin: `join(timeout)` is the only
     * way to bound them. Abandoning a thread leaks the instance it is stuck in, which is the lesser
     * evil - the alternative is a comparison that never reports back, and a UI that therefore stays
     * disabled until the app is restarted.
     */
    private fun renderWithDeadline(
        spec: SceneSpec,
        candidate: BenchCandidate,
        renderWidth: Int,
        renderHeight: Int,
        workspace: File,
        index: Int,
    ): Result<RawFrame> {
        var outcome: Result<RawFrame>? = null
        val worker = Thread(
            { outcome = runCatching { renderOne(spec, candidate, renderWidth, renderHeight, workspace) } },
            "mpv-bench-$index",
        ).apply { isDaemon = true }
        worker.start()
        worker.join(CANDIDATE_DEADLINE_MILLIS)
        if (worker.isAlive) {
            Timber.w("mpv bench: %s did not finish within %d ms", candidate.id, CANDIDATE_DEADLINE_MILLIS)
            return Result.failure(IllegalStateException("渲染超时（${CANDIDATE_DEADLINE_MILLIS / 1000} 秒内没有取到画面）"))
        }
        // `join` returning means the thread finished, so its writes are visible here.
        return outcome ?: Result.failure(IllegalStateException("渲染失败"))
    }

    private fun renderOne(
        spec: SceneSpec,
        candidate: BenchCandidate,
        renderWidth: Int,
        renderHeight: Int,
        workspace: File,
    ): RawFrame {
        // A previous run (or a crashed one) may have left files behind; the config file in there
        // would be read by the new instance instead of this candidate's.
        workspace.deleteRecursively()

        // Timed per stage on purpose: a stuck run leaves the overlay disabled, and these lines are
        // what tells "decoder never started" apart from "the read-back never returned" in logcat.
        val startedAt = System.currentTimeMillis()
        fun sinceStart(): Long = System.currentTimeMillis() - startedAt

        val bitmap: Bitmap = MpvBenchInstance(appContext, workspace).use { instance ->
            instance.open(candidate.configText, renderWidth, renderHeight)
            Timber.d("mpv bench: %s opened (%dx%d) after %d ms", candidate.id, renderWidth, renderHeight, sinceStart())
            instance.load(spec.sourceUrl, spec.positionMs)
            instance.awaitFirstFrame(FIRST_FRAME_TIMEOUT_MILLIS)
            Timber.d("mpv bench: %s first frame after %d ms", candidate.id, sinceStart())
            instance.captureFrame(CAPTURE_TIMEOUT_MILLIS).also {
                Timber.d("mpv bench: %s frame captured after %d ms", candidate.id, sinceStart())
            }
        }
        return bitmap.toRawFrame()
    }

    /**
     * The size every frame of this run is rendered at: the player's viewport, scaled down so the
     * longest side stays within [RENDER_MAX_LONG_SIDE].
     *
     * A phone viewport is ~4.4 MPixels; keeping that per candidate would cost tens of megabytes of
     * raw pixels each and make the diff crawl, without showing anything a smaller frame does not.
     */
    private fun renderSizeOf(spec: SceneSpec): Pair<Int, Int> {
        val longestSide = maxOf(spec.viewportWidth, spec.viewportHeight)
        if (longestSide <= RENDER_MAX_LONG_SIDE) return spec.viewportWidth to spec.viewportHeight
        val scale = RENDER_MAX_LONG_SIDE.toFloat() / longestSide
        return (spec.viewportWidth * scale).roundToInt().coerceAtLeast(MIN_RENDER_SIDE) to
            (spec.viewportHeight * scale).roundToInt().coerceAtLeast(MIN_RENDER_SIDE)
    }

    /**
     * Copies the bitmap into the flat pixel array the comparison works on.
     *
     * The bitmap is recycled right away: a full-screen frame is ~10 MB, and a run holds one per
     * candidate only as pixels.
     */
    private fun Bitmap.toRawFrame(): RawFrame {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val frame = RawFrame(width, height, pixels)
        recycle()
        return frame
    }

    private companion object {
        const val BENCH_DIR = "mpv-bench"

        /**
         * Hard limit for one candidate, watchdog included.
         *
         * Sits above the sum of the waits [MpvBenchInstance] performs itself (opening the source,
         * the first frame, the read-back) so a slow but healthy render still reports its own, more
         * specific error; it only fires when a native call never returns at all.
         */
        const val CANDIDATE_DEADLINE_MILLIS = 45_000L

        /** Decoding the scene at the captured position; network sources can take a while. */
        const val FIRST_FRAME_TIMEOUT_MILLIS = 15_000L

        /**
         * Reading one frame back from the render target. Generous on purpose: a hardware decoder
         * can still be finishing the seek, and the output may need a moment to present its first
         * frame after it is (re)created.
         */
        const val CAPTURE_TIMEOUT_MILLIS = 8_000L

        /** Longest side of a rendered frame; see [renderSizeOf]. */
        const val RENDER_MAX_LONG_SIDE = 1080

        const val MIN_RENDER_SIDE = 2
    }
}
