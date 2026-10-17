package org.jellyfin.mobile.player.mpv.bench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps one [MpvConfigCompareController] alive across the screens showing a comparison.
 *
 * Owning the controller from a ViewModel is what makes a comparison survive a configuration change:
 * a rotation while inspecting a frame must not re-render the whole scene.
 *
 * The dependencies are injected instead of passed to the constructor: the Compose call sites
 * create this with the default `viewModel()` factory, and that factory only knows how to build
 * `(Application)` constructors - the previous two-argument constructor made it throw on every
 * entry to the player screen, which killed the process on "play".
 */
class FrameCompareViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val repository: ConfigRepository by inject()
    private val renderer: MpvSceneRenderer by inject()

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
