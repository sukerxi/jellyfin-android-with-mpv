package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.screens.settings.SettingsScreen
import org.jellyfin.mobile.ui.theme.JellyfinTheme
import org.jellyfin.mobile.utils.extensions.addFragment

class SettingsFragment : Fragment(R.layout.fragment_compose) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val composeView: ComposeView = view.findViewById(R.id.compose_view)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

        composeView.setContent {
            JellyfinTheme {
                SettingsScreen(
                    onBack = {
                        if (isAdded && !parentFragmentManager.isStateSaved) {
                            parentFragmentManager.popBackStackImmediate()
                        }
                    },
                    onOpenMpvConfigEditor = {
                        if (isAdded && !parentFragmentManager.isStateSaved) {
                            parentFragmentManager.addFragment<MpvConfigEditorFragment>()
                        }
                    },
                )
            }
        }
    }
}
