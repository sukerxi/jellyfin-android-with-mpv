package org.jellyfin.mobile.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
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
import org.jellyfin.mobile.ui.components.SnackbarController
import org.jellyfin.mobile.utils.koinInject

/**
 * mpv 自定义配置编辑器。
 *
 * 交互上的三条硬约束（对应旧实现的问题）：
 * 1. 校验结果放在输入框自己的错误位里，指出具体行号，不再用 Toast 报一句就跑；
 * 2. 有未保存改动时，返回键与返回按钮都会拦下来确认，不会静默丢弃；
 * 3. 「重置」是清空全部内容，属于破坏性操作，需要二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpvConfigEditorScreen(
    onBack: () -> Unit,
    appPreferences: AppPreferences = koinInject(),
    snackbarController: SnackbarController = koinInject(),
) {
    val context = LocalContext.current
    val initialConfig = remember { appPreferences.mpvCustomConfig }
    var config by remember { mutableStateOf(initialConfig) }
    var invalidLine by remember { mutableStateOf<Int?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val hasUnsavedChanges = config.trim() != initialConfig.trim()

    fun attemptLeave() {
        if (hasUnsavedChanges) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }

    fun saveConfig() {
        val trimmedConfig = config.trim()
        val badLine = MpvConfigManager.findInvalidLine(trimmedConfig)
        if (badLine != null) {
            // 原地标红并指出行号；用户不必猜是哪一行写错了
            invalidLine = badLine
            return
        }

        invalidLine = null
        appPreferences.mpvCustomConfig = trimmedConfig
        MpvConfigManager.syncConfigFile(context.applicationContext, trimmedConfig)
        // 走全局 Snackbar：离开本页后仍然可见，且能承载"何时生效"这种后续信息
        snackbarController.show(context.getString(R.string.mpv_config_editor_saved))
        onBack()
    }

    // 返回键与返回按钮同语义，避免"按钮拦了、返回键没拦"的半吊子实现
    BackHandler { attemptLeave() }

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.mpv_config_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = { attemptLeave() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
                onValueChange = {
                    config = it
                    // 输入即清除旧的校验结果：错误提示跟着内容走，不留过期信息
                    invalidLine = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(text = stringResource(R.string.mpv_config_editor_hint)) },
                isError = invalidLine != null,
                supportingText = invalidLine?.let { line ->
                    {
                        Text(
                            text = stringResource(R.string.mpv_config_editor_invalid_line, line),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        if (config.isNotEmpty()) confirmReset = true
                    },
                    enabled = config.isNotEmpty(),
                ) {
                    Text(text = stringResource(R.string.mpv_config_editor_reset))
                }
                Button(onClick = { saveConfig() }) {
                    Text(text = stringResource(R.string.mpv_config_editor_save))
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(text = stringResource(R.string.mpv_config_editor_reset_title)) },
            text = { Text(text = stringResource(R.string.mpv_config_editor_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    config = ""
                    invalidLine = null
                }) {
                    Text(text = stringResource(R.string.mpv_config_editor_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(text = stringResource(R.string.mpv_config_editor_discard_title)) },
            text = { Text(text = stringResource(R.string.mpv_config_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) {
                    Text(text = stringResource(R.string.mpv_config_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(text = stringResource(R.string.mpv_config_editor_keep_editing))
                }
            },
        )
    }
}
