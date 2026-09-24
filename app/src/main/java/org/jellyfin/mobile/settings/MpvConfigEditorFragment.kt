package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.FragmentMpvConfigEditorBinding
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.extensions.requireMainActivity
import org.jellyfin.mobile.utils.withThemedContext
import org.koin.android.ext.android.inject

/**
 * Full-screen editor for the user-provided mpv configuration.
 *
 * The text follows the native [mpv.conf](https://mpv.io/manual/master/#configuration-files)
 * syntax (one `option=value` per line, `#` comments and `[profile]` sections). It is stored in
 * preferences and synchronized into mpv's config directory, where mpv parses it natively.
 */
class MpvConfigEditorFragment : Fragment() {

    private val appPreferences: AppPreferences by inject()

    private var _binding: FragmentMpvConfigEditorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val localInflater = inflater.withThemedContext(requireContext(), R.style.AppTheme_Settings)
        _binding = FragmentMpvConfigEditorBinding.inflate(localInflater, container, false)
        binding.root.applyWindowInsetsAsMargins()
        binding.toolbar.setTitle(R.string.mpv_config_editor_title)
        requireMainActivity().apply {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        binding.configEditText.setText(appPreferences.mpvCustomConfig)
        binding.resetButton.setOnClickListener {
            binding.configEditText.setText("")
        }
        binding.saveButton.setOnClickListener {
            saveConfig()
        }
        return binding.root
    }

    private fun saveConfig() {
        val config = binding.configEditText.text?.toString().orEmpty().trim()
        val invalidLine = MpvConfigManager.findInvalidLine(config)
        if (invalidLine != null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.mpv_config_editor_invalid_line, invalidLine),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        appPreferences.mpvCustomConfig = config
        MpvConfigManager.syncConfigFile(requireContext().applicationContext, config)
        Toast.makeText(requireContext(), R.string.mpv_config_editor_saved, Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireMainActivity().setSupportActionBar(null)
        _binding = null
    }
}
