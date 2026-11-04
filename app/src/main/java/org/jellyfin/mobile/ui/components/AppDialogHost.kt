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
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.dialog.DialogCoordinator
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
 * 因此不再需要可见性切换。但「无对话框」必须由 [DialogCoordinator] 保证：
 * 每个窗口都带 FLAG_DIM_BEHIND，两个窗口同时存在时 dim 叠加，后挂的窗口还会
 * 盖住页面里的选择框并接管触摸（表现为屏幕变暗、弹窗看不见、点哪儿都没反应）。
 *
 * 另一条同样的死路是「挂上了却关不掉」：窗口按模态规则吞掉窗口外全部触摸，
 * dim 也只在窗口 removeView 时才撤销。若 `onDismissRequest` 是空回调，点外部 /
 * 按返回只是触发一个不做事的 dismiss，窗口连同 dim 与触摸拦截会一直留在屏幕上，
 * 于是整屏变暗且背景里什么也点不了。提示类对话框必须给真实关闭回调；确实要
 * 不可取消的，用 DialogProperties 显式关掉 back / outside，而不是靠空回调兜底。
 */
@Composable
fun AppDialogHost(
    bluetoothHelper: BluetoothPermissionHelper,
    isWebViewUnsupported: () -> Boolean,
    onCloseApp: () -> Unit,
    updateDialogState: UpdateDialogStateHolder = koinInject(),
    webViewDialogState: WebViewDialogState = koinInject(),
    dialogCoordinator: DialogCoordinator = koinInject(),
) {
    val context = LocalContext.current

    // 页面级对话框（如设置页「视频播放器类型」单选框）显示期间，这里不挂载任何窗口。
    // 状态仍保留在各自的 holder 里，页面对话框关闭后本块重新组合，提示照常出现，不丢。
    if (!dialogCoordinator.pageDialogActive.value) {
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
        // 设备不支持 WebView 时应用不可用，必须走按钮显式退出（保持原有不可取消语义）。
        // 由窗口属性保证「不可取消」，而不是让 back / outside 触发一个空回调：
        // 后者会让这个带 dim 的模态窗口永远关不掉，整屏变暗且背景全不可点。
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
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
        // 点外部 / 返回等同「忽略」：回调会重新初始化 WebView 并清掉请求，窗口一定能关。
        // 原来是空回调，点外部只触发一个不做事的 dismiss，独立窗口会一直留着
        // dim 和模态拦截。此处不能只 dismiss —— WebView 在版本检查失败后没有初始化。
        onDismissRequest = onDismissed,
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
