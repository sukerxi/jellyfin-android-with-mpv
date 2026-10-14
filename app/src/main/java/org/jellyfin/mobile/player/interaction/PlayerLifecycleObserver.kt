package org.jellyfin.mobile.player.interaction

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.jellyfin.mobile.player.PlayerViewModel

/**
 * Watches the *process* foreground/background transitions on behalf of a single playback
 * session.
 *
 * Registered in [PlayerViewModel.setupPlayer] and unregistered in
 * [PlayerViewModel.releasePlayer] (not in the ViewModel's `init`): the ViewModel lives in the
 * activity's `ViewModelStore` and therefore outlives the player sessions it hosts, so a
 * permanently registered observer would pause - and on [onCreate], rebuild - the player of a
 * session it does not belong to.
 */
class PlayerLifecycleObserver(private val viewModel: PlayerViewModel) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        // Only drop playback the user did not ask to keep running; the web app may still
        // play (music) or stream to a remote device.
        viewModel.pauseInBackground()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // The process is going away: this is the last chance to stop, since the activity (and
        // with it the ViewModel) may never come back.
        viewModel.onPlayerClosed()
    }
}
