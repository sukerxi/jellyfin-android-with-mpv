package org.jellyfin.mobile.ui.utils

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.ui.theme.JellyfinTheme
import org.koin.android.ext.android.get
import org.koin.compose.koinInject
import kotlin.coroutines.resume

@Composable
fun DownloadSettingsDialogContent(onClose: () -> Unit) {
    val storageManager: StorageManager = koinInject()

    var storageLocation by remember { mutableStateOf(storageManager.getStorageLocation()) }

    val storageLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storageManager.changeStorageLocation(uri)
            storageLocation = storageManager.getStorageLocation()
            onClose()
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.download_settings_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = stringResource(R.string.download_settings_dialog_message),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = {
                    storageLocationPicker.launch(storageLocation?.uri ?: storageManager.defaultStorageLocation)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.select_folder))
            }
        }
    }
}

fun ComponentActivity.shouldShowDownloadSettingsDialog(): Boolean {
    val storageManager = get<StorageManager>()
    return storageManager.getStorageLocation() == null
}

suspend fun ComponentActivity.showDownloadSettingsDialog() {
    suspendCancellableCoroutine { continuation ->
        ComponentDialog(this).apply {
            setContentView(
                ComposeView(this@showDownloadSettingsDialog).apply {
                    setContent {
                        JellyfinTheme {
                            DownloadSettingsDialogContent(
                                onClose = { dismiss() },
                            )
                        }
                    }
                },
            )

            setOnDismissListener {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
            show()
            continuation.invokeOnCancellation { dismiss() }
        }
    }
}
