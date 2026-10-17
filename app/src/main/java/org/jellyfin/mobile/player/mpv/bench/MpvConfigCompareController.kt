package org.jellyfin.mobile.player.mpv.bench

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import timber.log.Timber

/**
 * Drives one comparison: an edited `mpv.conf` against the saved one, or mpv against ExoPlayer.
 *
 * Holds no player and no Compose state: it builds the candidates to render, hands them to an
 * [MpvSceneRenderer] and publishes an immutable [MpvConfigCompareState] for whatever UI is showing
 * it. Comparing an edited configuration against the saved one is therefore decided in exactly one
 * place, and so is comparing two playback engines ([CompareMode.ENGINE]).
 *
 * Nothing here writes `mpv.conf` or touches the player: the only lasting effect a session can have
 * is [commit], which the user has to ask for explicitly.
 *
 * ## Why every run has an owner and a deadline
 *
 * A run ends in native code (a fresh mpv instance per candidate, GPU read-back), and native calls
 * cannot be interrupted. A renderer that never returns therefore used to leave [MpvConfigCompareState.isRunning]
 * `true` forever: the overlay kept its spinner, the editor stayed disabled and both "compare"
 * buttons stayed disabled, so tapping them did nothing at all. Three things prevent that now:
 *
 * - the renderer bounds every candidate with a watchdog of its own (see `MpvSceneRendererImpl`), so
 *   a run always comes back with either frames or a reason;
 * - [cancel] clears the state directly instead of waiting for the render to notice, which gives the
 *   user an exit even in the worst case;
 * - every state write checks [generation], so a run that is abandoned can never overwrite the
 *   result of the run the user started afterwards.
 */
class MpvConfigCompareController(
    private val appPreferences: AppPreferences,
    private val renderer: MpvSceneRenderer,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(MpvConfigCompareState())
    val state: StateFlow<MpvConfigCompareState> = _state.asStateFlow()

    /** Configuration text the user is editing for the next run. */
    private val _editedConfig = MutableStateFlow(appPreferences.mpvCustomConfig)
    val editedConfig: StateFlow<String> = _editedConfig.asStateFlow()

    /**
     * Scene the next run is based on. Set once while playback is still running, then reused: a
     * comparison that re-decided its frame mid-flight would not be comparable with itself.
     */
    private var scene: SceneSpec? = null

    /** The render currently in flight, if any; kept so [cancel] can abandon it. */
    private var runJob: Job? = null

    /**
     * Bumped whenever a run starts, is cancelled or is committed. A coroutine may only publish its
     * result while the value still matches the one it started with, which is what keeps an
     * abandoned render from overwriting a newer one.
     */
    private var generation: Int = 0

    /** The configuration normal playback uses, i.e. the baseline every run renders first. */
    val baselineConfig: String get() = appPreferences.mpvCustomConfig

    /** Freezes [spec] as the scene for this session. */
    fun setScene(spec: SceneSpec) {
        scene = spec
        _state.value = _state.value.copy(spec = spec)
    }

    fun setEditedConfig(value: String) {
        _editedConfig.value = value
    }

    /**
     * Configurations the current input would render: the baseline first, then one per change.
     *
     * Also the "is there anything to compare" question - a single entry means the editor does not
     * differ from what is playing. In [CompareMode.ENGINE] there is always something to compare:
     * the candidates are mpv and ExoPlayer, not two versions of the same file.
     */
    fun candidates(): List<BenchCandidate> = when (_state.value.mode) {
        CompareMode.CONFIG -> buildList {
            add(MpvBenchConfig.baseline(baselineConfig))
            addAll(MpvBenchConfig.changes(baselineConfig, _editedConfig.value))
        }
        CompareMode.ENGINE -> listOf(
            MpvBenchConfig.baseline(baselineConfig),
            MpvBenchConfig.exoPlayer(),
        )
    }

    /**
     * Switches what the next run compares.
     *
     * Nothing is carried over: the frames of one mode do not answer the question of the other, and
     * keeping them on screen under a different label is what would make a comparison lie.
     */
    fun setMode(mode: CompareMode) {
        if (_state.value.mode == mode) return
        runJob?.cancel()
        runJob = null
        generation++
        Timber.d("mpv compare: mode switched to %s", mode)
        _state.value = MpvConfigCompareState(mode = mode, spec = scene)
    }

    /**
     * Renders the frozen frame for every candidate of the current mode, then publishes the frames
     * and their deltas against the first one.
     *
     * In [CompareMode.CONFIG] that is the saved configuration plus every single change the user
     * made; in [CompareMode.ENGINE] it is the mpv frame and the ExoPlayer frame.
     */
    fun run() {
        val currentScene = scene ?: return
        if (_state.value.isRunning) return

        val targets = candidates()
        if (_state.value.mode == CompareMode.CONFIG && targets.size <= 1) {
            // Nothing changed: rendering identical frames would only waste the user's time.
            _state.value = _state.value.copy(
                isRunning = false,
                error = null,
                baseline = null,
                compared = emptyList(),
                failure = null,
                completed = 0,
                total = 0,
            )
            return
        }

        val runGeneration = ++generation
        _state.value = _state.value.copy(
            isRunning = true,
            error = null,
            baseline = null,
            compared = emptyList(),
            failure = null,
            completed = 0,
            total = targets.size,
        )
        Timber.d("mpv compare: run %d started, %d candidates", runGeneration, targets.size)

        runJob = scope.launch {
            try {
                val result = withContext(workDispatcher) {
                    renderer.render(currentScene, targets) { completed, total ->
                        publish(runGeneration) { it.copy(completed = completed, total = total) }
                    }.toRunResult()
                }
                publish(runGeneration) {
                    it.copy(
                        isRunning = false,
                        baseline = result.baseline,
                        compared = result.compared,
                        failure = result.failure,
                        error = null,
                    )
                }
                Timber.d("mpv compare: run %d finished", runGeneration)
            } catch (cancellation: CancellationException) {
                // A cancelled run publishes nothing: whoever cancelled it already decided what the
                // state should say. Rethrowing is what keeps the coroutine machinery happy.
                Timber.d("mpv compare: run %d cancelled", runGeneration)
                throw cancellation
            } catch (error: Throwable) {
                // Throwable, not Exception: an OutOfMemoryError while diffing two frames used to
                // escape here and leave the overlay running forever.
                Timber.w(error, "mpv compare: run %d failed", runGeneration)
                publish(runGeneration) {
                    it.copy(isRunning = false, error = error.message ?: "对比失败")
                }
            } finally {
                // A run that was replaced or cancelled must not clear the newer run's job handle.
                if (runGeneration == generation) runJob = null
                // Last line of defence: no path may leave the UI believing a run is still going.
                publish(runGeneration) { it.copy(isRunning = false) }
            }
        }
    }

    /**
     * Gives up on the render in flight.
     *
     * The state is cleared immediately instead of waiting for the render to notice: a native call
     * that is already stuck will not return, and the user asked for the spinner to go away.
     */
    fun cancel() {
        val job = runJob
        // Nothing running and nothing to say: keep the previous result on screen.
        if (job == null && !_state.value.isRunning) return
        runJob = null
        generation++
        job?.cancel()
        Timber.d("mpv compare: run cancelled by the user")
        _state.value = _state.value.copy(
            isRunning = false,
            error = null,
            baseline = null,
            compared = emptyList(),
            failure = CANCELLED_MESSAGE,
            completed = 0,
            total = 0,
        )
    }

    /**
     * Keeps [config] as the user's own. Only the preference is written: playlist code re-reads it
     * when mpv is (re)initialized, which is the only moment a config file can take effect.
     */
    fun commit(config: String) {
        val trimmed = config.trim()
        appPreferences.mpvCustomConfig = trimmed
        _editedConfig.value = trimmed
        // The rendered frames describe the previous baseline, so they no longer answer anything.
        generation++
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(
            isRunning = false,
            baseline = null,
            compared = emptyList(),
            failure = null,
            error = null,
        )
    }

    /** Publishes [transform] of the current state, unless a newer run took over meanwhile. */
    private fun publish(runGeneration: Int, transform: (MpvConfigCompareState) -> MpvConfigCompareState) {
        if (runGeneration != generation) return
        _state.value = transform(_state.value)
    }

    private fun SceneCapture.toRunResult(): RunResult {
        val baselineImage = frames.firstOrNull { it.candidate.isBaseline }?.image
        val compared = frames
            .filterNot { it.candidate.isBaseline }
            .map { frame ->
                ComparedFrame(
                    candidate = frame.candidate,
                    frame = frame,
                    comparison = if (baselineImage != null && frame.image != null) {
                        FrameComparison.compare(baselineImage, frame.image)
                    } else {
                        null
                    },
                )
            }
        return RunResult(baselineImage, compared, firstFailure)
    }

    private data class RunResult(
        val baseline: RawFrame?,
        val compared: List<ComparedFrame>,
        val failure: String?,
    )

    private companion object {
        const val CANCELLED_MESSAGE = "已取消对比"
    }
}
