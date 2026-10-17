package org.jellyfin.mobile.player.mpv.bench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * Keeps one [MpvConfigCompareController] alive across the screens showing a comparison.
 *
 * Owning the controller from a ViewModel is what makes a comparison survive a configuration change:
 * a rotation while inspecting a frame must not re-render the whole scene.
 */
class FrameCompareViewModel(
    repository: ConfigRepository,
    private val renderer: MpvSceneRenderer,
) : ViewModel() {

    private val repository: ConfigRepository = repository

    val controller: MpvConfigCompareController = MpvConfigCompareController(
        repository = repository,
        scope = viewModelScope,
    )

    fun rendererInstance(): MpvSceneRenderer = renderer

    /** Creates a swapper bound to the current app context. */
    fun swapper(): MpvConfigSwapper = MpvConfigSwapper(repository.appContext())

    /** Applies the compared configuration to `mpv.conf` for good. */
    fun commit(config: String) = controller.commit(swapper(), config)

    override fun onCleared() {
        // A comparison that outlives its screen would leave the generated profiles in mpv.conf.
        controller.release(swapper())
    }
}
