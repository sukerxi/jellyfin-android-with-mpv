package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.screens.settings.MpvConfigEditorScreen
import org.jellyfin.mobile.ui.theme.JellyfinTheme

/**
 * Full-screen editor for the user-provided mpv configuration.
 *
 * The text follows the native [mpv.conf](https://mpv.io/manual/master/#configuration-files)
 * syntax (one `option=value` per line, `#` comments and `[profile]` sections). It is stored in
 * preferences and synchronized into mpv's config directory, where mpv parses it natively.
 */
class MpvConfigEditorFragment : Fragment(R.layout.fragment_compose) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val composeView: ComposeView = view.findViewById(R.id.compose_view)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

        composeView.setContent {
            JellyfinTheme {
                MpvConfigEditorScreen(
                    onBack = {
                        if (isAdded && !parentFragmentManager.isStateSaved) {
                            parentFragmentManager.popBackStackImmediate()
                        }
                    },
                )
            }
        }
    }
}
