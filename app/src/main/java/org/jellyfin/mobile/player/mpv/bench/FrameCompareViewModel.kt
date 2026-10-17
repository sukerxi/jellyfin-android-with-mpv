package org.jellyfin.mobile.player.mpv.bench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jellyfin.mobile.app.AppPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps one [MpvConfigCompareController] alive across the screens showing a comparison.
 *
 * Owning the controller from a ViewModel is what makes a comparison survive a configuration change:
 * a rotation while inspecting a frame must not re-render the whole scene.
 *
 * The dependencies are injected instead of passed to the constructor: the Compose call sites create
 * this with the default `viewModel()` factory, and that factory only knows how to build
 * `(Application)` constructors - a two-argument constructor made it throw on every entry to the
 * player screen, which killed the process on "play".
 *
 * There is no state to release on [onCleared]: a run renders on throwaway mpv instances that close
 * themselves, and the user's configuration is only written by [commit].
 */
class FrameCompareViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val appPreferences: AppPreferences by inject()
    private val renderer: MpvSceneRenderer by inject()

    val controller: MpvConfigCompareController = MpvConfigCompareController(
        appPreferences = appPreferences,
        renderer = renderer,
        scope = viewModelScope,
    )

    /** Applies the compared configuration to the user's own preferences. */
    fun commit(config: String) = controller.commit(config)
}
