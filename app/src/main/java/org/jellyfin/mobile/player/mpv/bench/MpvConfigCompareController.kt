package org.jellyfin.mobile.player.mpv.bench

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one configuration comparison.
 *
 * Holds no player, no surface and no Compose state: it sequences the scene renderer and the config
 * swapper and publishes an immutable [MpvConfigCompareState] for whatever UI is showing it. Both
 * comparison entry points share this controller, so the benchmark lifecycle is decided in exactly
 * one place.
 */
class MpvConfigCompareController(
    private val repository: ConfigRepository,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(MpvConfigCompareState())
    val state: StateFlow<MpvConfigCompareState> = _state.asStateFlow()

    /** Configuration text the user is editing for the next run. */
    private val _editedConfig = MutableStateFlow("")
    val editedConfig: StateFlow<String> = _editedConfig.asStateFlow()

    /**
     * Scene the next run is based on. Set once while playback is still running, then reused: a
     * comparison that re-decided its frame mid-flight would not be comparable with itself.
     */
    private var spec: SceneSpec? = null

    init {
        _editedConfig.value = repository.currentConfig()
    }

    /** Freezes [spec] as the scene for this session. */
    fun setScene(spec: SceneSpec) {
        this.spec = spec
        _state.value = _state.value.copy(spec = spec)
    }

    fun setEditedConfig(value: String) {
        _editedConfig.value = value
    }

    /**
     * Renders the frozen frame under the configuration the user has now and under every single
     * change of [config], then publishes the frames and their deltas against the first one.
     */
    fun run(
        renderer: MpvSceneRenderer,
        swapper: MpvConfigSwapper,
        config: String = _editedConfig.value,
    ) {
        val currentSpec = spec ?: return
        if (_state.value.isRunning) return

        _state.value = _state.value.copy(isRunning = true, error = null, capture = null)
        scope.launch {
            runCatching {
                withContext(workDispatcher) {
                    // The profiles have to exist before anything is applied, and the baseline frame
                    // must be rendered while no chunk is active.
                    repository.install(config, swapper)
                    val chunks = MpvConfigProfiles.resolveChunks(config)
                    renderer.render(currentSpec, candidatesFor(chunks))
                }
            }.onSuccess { capture ->
                _state.value = _state.value.copy(isRunning = false, capture = capture)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isRunning = false,
                    error = error.message ?: "对比失败",
                )
            }
        }
    }

    /** Restores the user's own configuration; safe to call more than once. */
    fun release(swapper: MpvConfigSwapper) = repository.restore(swapper)

    /** Keeps the compared configuration as the user's own. */
    fun commit(swapper: MpvConfigSwapper, config: String = _editedConfig.value) =
        repository.commit(config, swapper)

    private fun candidatesFor(chunks: List<ConfigChunk>): List<ConfigCandidate> = buildList {
        add(MpvConfigProfiles.baselineConfigCandidate())
        chunks.forEach { chunk ->
            add(
                ConfigCandidate(
                    id = chunk.profileName,
                    label = chunk.label,
                    profileName = chunk.profileName,
                ),
            )
        }
    }
}
