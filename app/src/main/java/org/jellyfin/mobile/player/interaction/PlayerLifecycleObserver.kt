package org.jellyfin.mobile.player.interaction

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.jellyfin.mobile.player.PlayerViewModel

class PlayerLifecycleObserver(private val viewModel: PlayerViewModel) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        viewModel.setupPlayer()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Only drop playback the user did not ask to keep running; the web app may still
        // play (music) or stream to a remote device.
        viewModel.pauseInBackground()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Last chance to stop: the view model is only cleared when the activity really goes
        // away, which is also the point where the foreground service must not keep playing.
        viewModel.onPlayerClosed()
    }
}
