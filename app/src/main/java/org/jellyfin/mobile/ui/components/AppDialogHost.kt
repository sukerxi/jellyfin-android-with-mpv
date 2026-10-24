package org.jellyfin.mobile.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.dialog.WebViewDialogState
import org.jellyfin.mobile.update.UpdateDialogStateHolder
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BluetoothPermissionHelper
import org.jellyfin.mobile.utils.koinInject

/**
 * MainActivity setContent 根树中的全局对话框宿主：
 * 更新流程、蓝牙权限、WebView 不可用/过旧。
 *
 * Compose Dialog 本身是独立窗口，无对话框时不会拦截下层触摸，
 * 因此不再需要可见性切换。
 */
@Composable
fun AppDialogHost(
    bluetoothHelper: BluetoothPermissionHelper,
    isWebViewUnsupported: () -> Boolean,
    onCloseApp: () -> Unit,
    updateDialogState: UpdateDialogStateHolder = koinInject(),
    webViewDialogState: WebViewDialogState = koinInject(),
) {
    val context = LocalContext.current

    // 更新可用 / 下载进度 / 安装权限
    UpdateDialogHost(updateDialogState)

    // 蓝牙权限说明
    if (bluetoothHelper.requestVisible) {
        BluetoothPermissionDialog(
            onContinue = { bluetoothHelper.onUserDecision(true) },
            onDismiss = { bluetoothHelper.onUserDecision(false) },
        )
    }

    // WebView 不受支持（启动时检查）
    if (isWebViewUnsupported()) {
        WebViewUnsupportedDialog(
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                onCloseApp()
            },
            onCloseApp = onCloseApp,
        )
    }

    // WebView 版本过旧（WebViewHost 检查）
    webViewDialogState.outdatedRequest?.let { request ->
        WebViewOutdatedDialog(
            request = request,
            onDismissed = {
                request.onIgnore()
                webViewDialogState.dismiss()
            },
        )
    }
}

@Composable
private fun BluetoothPermissionDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bluetooth_permission_title)) },
        text = { Text(stringResource(R.string.bluetooth_permission_message)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.bluetooth_permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun WebViewUnsupportedDialog(
    onOpenSettings: () -> Unit,
    onCloseApp: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.dialog_web_view_not_supported)) },
        text = { Text(stringResource(R.string.dialog_web_view_not_supported_message)) },
        confirmButton = {
            if (AndroidVersion.isAtLeastN) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.dialog_button_open_settings))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCloseApp) {
                Text(stringResource(R.string.dialog_button_close_app))
            }
        },
    )
}

@Composable
private fun WebViewOutdatedDialog(
    request: WebViewDialogState.OutdatedRequest,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val marketIntent = remember(request) {
        request.webViewPackageName?.let { packageName ->
            val marketUri = Uri.Builder().apply {
                scheme("market")
                authority("details")
                appendQueryParameter("id", packageName)
            }.build()
            val referrerUri = Uri.Builder().apply {
                scheme("android-app")
                authority(context.packageName)
            }.build()
            Intent(Intent.ACTION_VIEW).apply {
                data = marketUri
                putExtra(Intent.EXTRA_REFERRER, referrerUri)
            }.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.dialog_web_view_outdated)) },
        text = { Text(stringResource(R.string.dialog_web_view_outdated_message)) },
        confirmButton = {
            if (AndroidVersion.isAtLeastN) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                    Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                    activity?.finishAfterTransition()
                }) {
                    Text(stringResource(R.string.dialog_button_open_settings))
                }
            }
        },
        dismissButton = {
            Row {
                if (marketIntent != null) {
                    TextButton(onClick = {
                        context.startActivity(marketIntent)
                        activity?.finishAfterTransition()
                    }) {
                        Text(stringResource(R.string.dialog_button_check_for_updates))
                    }
                }
                TextButton(onClick = onDismissed) {
                    Text(stringResource(R.string.dialog_button_ignore))
                }
            }
        },
    )
}
