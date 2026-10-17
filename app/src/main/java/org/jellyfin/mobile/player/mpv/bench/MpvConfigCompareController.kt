package org.jellyfin.mobile.player.mpv.bench

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences

/**
 * Drives one configuration comparison.
 *
 * Holds no player and no Compose state: it builds the candidates to render, hands them to an
 * [MpvSceneRenderer] and publishes an immutable [MpvConfigCompareState] for whatever UI is showing
 * it. Comparing an edited configuration against the saved one is therefore decided in exactly one
 * place.
 *
 * Nothing here writes `mpv.conf` or touches the player: the only lasting effect a session can have
 * is [commit], which the user has to ask for explicitly.
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
     * differ from what is playing.
     */
    fun candidates(): List<BenchCandidate> = buildList {
        add(MpvBenchConfig.baseline(baselineConfig))
        addAll(MpvBenchConfig.changes(baselineConfig, _editedConfig.value))
    }

    /**
     * Renders the frozen frame under the saved configuration and under every single change the
     * user made, then publishes the frames and their deltas against the first one.
     */
    fun run() {
        val currentScene = scene ?: return
        if (_state.value.isRunning) return

        val targets = candidates()
        if (targets.size <= 1) {
            // Nothing changed: rendering identical frames would only waste the user's time.
            _state.value = _state.value.copy(error = null, baseline = null, compared = emptyList(), failure = null)
            return
        }

        _state.value = _state.value.copy(
            isRunning = true,
            error = null,
            baseline = null,
            compared = emptyList(),
            failure = null,
            completed = 0,
            total = targets.size,
        )
        scope.launch {
            val result = try {
                withContext(workDispatcher) {
                    val capture = renderer.render(currentScene, targets) { completed, total ->
                        _state.value = _state.value.copy(completed = completed, total = total)
                    }
                    capture.toRunResult()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.value = _state.value.copy(isRunning = false, error = error.message ?: "对比失败")
                return@launch
            }
            _state.value = _state.value.copy(
                isRunning = false,
                baseline = result.baseline,
                compared = result.compared,
                failure = result.failure,
                error = null,
            )
        }
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
        _state.value = _state.value.copy(baseline = null, compared = emptyList(), failure = null, error = null)
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
}
