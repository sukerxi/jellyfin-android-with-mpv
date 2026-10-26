package org.jellyfin.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.update.DownloadProgress
import org.jellyfin.mobile.update.UpdateDialogStateHolder
import org.jellyfin.mobile.update.UpdateDownloadWorker
import org.jellyfin.mobile.update.UpdateInfo
import org.jellyfin.mobile.update.UpdateManager
import org.jellyfin.mobile.utils.koinInject
import java.util.UUID

/**
 * 挂在根 Compose 宿主上的更新对话框集合：
 * 更新提示 / 下载进度 / 安装权限引导。状态来自 [UpdateDialogStateHolder]。
 */
@Composable
fun UpdateDialogHost(
    holder: UpdateDialogStateHolder = koinInject(),
    updateManager: UpdateManager = koinInject(),
    snackbarController: SnackbarController = koinInject(),
) {
    val context = LocalContext.current

    // 从系统设置授权返回后，如果正好有"等在门口"的更新，直接接着安装，
    // 不让用户再点一次「立即下载」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pending = holder.pendingInstall ?: return@LifecycleEventObserver
                if (updateManager.hasInstallPermission(context)) {
                    holder.clearPendingInstall()
                    holder.hideInstallPermission()
                    updateManager.launchInstall(context, pending)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    holder.prompt?.let { prompt ->
        UpdateAvailableDialog(
            prompt = prompt,
            updateManager = updateManager,
            onDownloadNow = {
                val workId = updateManager.enqueueDownload(context, prompt.info)
                holder.showProgress(prompt.info, workId)
                // 缺权限时不再"边弹权限引导边下载"两个对话框叠着走：
                // 先记下待安装版本，权限对话框优先展示，下载在后台照常进行。
                if (!updateManager.hasInstallPermission(context)) {
                    holder.requestInstallAfterPermission(prompt.info)
                }
            },
            onDownloadInBackground = {
                updateManager.enqueueDownload(context, prompt.info)
                snackbarController.show(context.getString(R.string.update_download_started))
                holder.dismissPrompt()
            },
            onIgnore = { holder.ignoreVersion(prompt.info.versionName) },
            onDismiss = holder::dismissPrompt,
        )
    }

    // 权限引导优先展示；关闭后再显示进度，避免两个对话框叠在一起
    holder.progressState?.takeIf { !holder.installPermissionVisible }?.let { state ->
        UpdateProgressDialog(
            info = state.info,
            workId = state.workId,
            onDismiss = holder::hideProgress,
            onCancel = {
                updateManager.cancelDownload(context)
                holder.hideProgress()
                holder.clearPendingInstall()
            },
            onSuccess = {
                holder.hideProgress()
                if (updateManager.hasInstallPermission(context)) {
                    updateManager.launchInstall(context, state.info)
                } else {
                    // 下载好了但装不了：记下来，授权返回后自动继续
                    holder.requestInstallAfterPermission(state.info)
                }
            },
            onFailed = {
                holder.hideProgress()
                holder.clearPendingInstall()
                snackbarController.show(context.getString(R.string.update_download_failed))
            },
        )
    }

    if (holder.installPermissionVisible) {
        InstallPermissionDialog(
            onOpenSettings = {
                updateManager.openInstallPermissionSettings(context)
                holder.hideInstallPermission()
            },
            onDismiss = holder::hideInstallPermission,
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    prompt: UpdateDialogStateHolder.UpdatePrompt,
    updateManager: UpdateManager,
    onDownloadNow: () -> Unit,
    onDownloadInBackground: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = prompt.info
    val context = LocalContext.current
    val changelog = remember(info.changelog) { updateManager.formatChangelog(info.changelog) }

    AlertDialog(
        // 返回键与点击外部始终可以关闭；"忽略此版本"只是额外选项
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME))
                    Text(stringResource(R.string.update_new_version, info.versionName))
                    Text(
                        stringResource(
                            R.string.update_channel_label,
                            updateManager.channelLabel(context, info.channel),
                        ),
                    )
                    if (changelog.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_changelog_label),
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(changelog)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownloadNow) {
                Text(stringResource(R.string.update_download_now))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (prompt.allowIgnore) {
                    TextButton(onClick = onIgnore) {
                        Text(stringResource(R.string.update_ignore))
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
                TextButton(onClick = onDownloadInBackground) {
                    Text(stringResource(R.string.update_download_background))
                }
            }
        },
    )
}

@Composable
private fun UpdateProgressDialog(
    info: UpdateInfo,
    workId: UUID,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onSuccess: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<DownloadProgress>(DownloadProgress.Idle) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(workId) {
        UpdateDownloadWorker.observeProgress(context, workId).collect { progress ->
            when (progress) {
                is DownloadProgress.Running -> state = progress
                DownloadProgress.Success -> if (!finished) {
                    finished = true
                    onSuccess()
                }
                DownloadProgress.Failed -> if (!finished) {
                    finished = true
                    onFailed()
                }
                DownloadProgress.Idle -> Unit
            }
        }
    }

    fun formatSize(bytes: Long): String =
        if (bytes <= 0) "?" else android.text.format.Formatter.formatShortFileSize(context, bytes)

    val running = state as? DownloadProgress.Running

    AlertDialog(
        // 允许关闭：下载跑在 WorkManager 里，关掉对话框并中断下载；
        // 旧实现 onDismissRequest = {} 让用户只能"取消"，等同于强迫中断。
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_downloading_title, info.versionName)) },
        text = {
            Column {
                if (running != null && running.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { running.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (val s = state) {
                        is DownloadProgress.Running -> {
                            if (s.percent >= 0) {
                                stringResource(
                                    R.string.update_download_progress,
                                    s.percent,
                                    formatSize(s.downloaded),
                                    formatSize(s.total),
                                )
                            } else {
                                stringResource(
                                    R.string.update_download_progress_indeterminate,
                                    formatSize(s.downloaded),
                                )
                            }
                        }
                        else -> ""
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.download_cancel))
            }
        },
    )
}

@Composable
private fun InstallPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_install_permission_title)) },
        text = { Text(stringResource(R.string.update_install_permission_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.dialog_button_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
