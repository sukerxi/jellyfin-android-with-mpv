package org.jellyfin.mobile.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.utils.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpvConfigEditorScreen(
    onBack: () -> Unit,
    appPreferences: AppPreferences = koinInject(),
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(appPreferences.mpvCustomConfig) }

    fun saveConfig() {
        val trimmedConfig = config.trim()
        val invalidLine = MpvConfigManager.findInvalidLine(trimmedConfig)
        if (invalidLine != null) {
            Toast.makeText(
                context,
                context.getString(R.string.mpv_config_editor_invalid_line, invalidLine),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        appPreferences.mpvCustomConfig = trimmedConfig
        MpvConfigManager.syncConfigFile(context.applicationContext, trimmedConfig)
        Toast.makeText(context, R.string.mpv_config_editor_saved, Toast.LENGTH_SHORT).show()
        onBack()
    }

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.mpv_config_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.mpv_config_editor_help),
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = config,
                onValueChange = { config = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(text = stringResource(R.string.mpv_config_editor_hint)) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { config = "" }) {
                    Text(text = stringResource(R.string.mpv_config_editor_reset))
                }
                Button(onClick = { saveConfig() }) {
                    Text(text = stringResource(R.string.mpv_config_editor_save))
                }
            }
        }
    }
}
