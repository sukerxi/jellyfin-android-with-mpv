package org.jellyfin.mobile.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.update.UpdateCheckResult
import org.jellyfin.mobile.update.UpdateChannel
import org.jellyfin.mobile.update.UpdateChecker
import org.jellyfin.mobile.update.UpdateDialogStateHolder
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.isPackageInstalled
import org.jellyfin.mobile.utils.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMpvConfigEditor: () -> Unit,
    appPreferences: AppPreferences = koinInject(),
    storageManager: StorageManager = koinInject(),
    updateChecker: UpdateChecker = koinInject(),
    updateDialogState: UpdateDialogStateHolder = koinInject(),
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val musicNotificationAlwaysDismissible by appPreferences.musicNotificationAlwaysDismissibleFlow
        .collectAsState(initial = appPreferences.musicNotificationAlwaysDismissible)
    val playerType by appPreferences.videoPlayerTypeFlow
        .collectAsState(initial = appPreferences.videoPlayerType)
    val startLandscapeVideoInLandscape by appPreferences.exoPlayerStartLandscapeVideoInLandscapeFlow
        .collectAsState(initial = appPreferences.exoPlayerStartLandscapeVideoInLandscape)
    val allowSwipeGestures by appPreferences.exoPlayerAllowSwipeGesturesFlow
        .collectAsState(initial = appPreferences.exoPlayerAllowSwipeGestures)
    val rememberBrightness by appPreferences.exoPlayerRememberBrightnessFlow
        .collectAsState(initial = appPreferences.exoPlayerRememberBrightness)
    val allowPressSpeedUp by appPreferences.exoPlayerAllowPressSpeedUpFlow
        .collectAsState(initial = appPreferences.exoPlayerAllowPressSpeedUp)
    val allowBackgroundAudio by appPreferences.exoPlayerAllowBackgroundAudioFlow
        .collectAsState(initial = appPreferences.exoPlayerAllowBackgroundAudio)
    val allowHorizontalGesture by appPreferences.exoPlayerAllowHorizontalGestureFlow
        .collectAsState(initial = appPreferences.exoPlayerAllowHorizontalGesture)
    val doubleTapPause by appPreferences.exoPlayerDoubleTapPauseFlow
        .collectAsState(initial = appPreferences.exoPlayerDoubleTapPause)
    val directPlayAss by appPreferences.exoPlayerDirectPlayAssFlow
        .collectAsState(initial = appPreferences.exoPlayerDirectPlayAss)
    val networkBuffer by appPreferences.exoPlayerNetworkBufferFlow
        .collectAsState(initial = appPreferences.exoPlayerNetworkBuffer)
    val mobileDataPlaybackPrompt by appPreferences.mobileDataPlaybackPromptFlow
        .collectAsState(initial = appPreferences.mobileDataPlaybackPrompt)
    val mobileDataMaxBitrate by appPreferences.mobileDataMaxBitrateFlow
        .collectAsState(initial = appPreferences.mobileDataMaxBitrate)
    val externalPlayerApp by appPreferences.externalPlayerAppFlow
        .collectAsState(initial = appPreferences.externalPlayerApp)
    val mpvCustomConfig by appPreferences.mpvCustomConfigFlow
        .collectAsState(initial = appPreferences.mpvCustomConfig)
    val mpvShowCompareEntry by appPreferences.mpvShowCompareEntryFlow
        .collectAsState(initial = appPreferences.mpvShowCompareEntry)
    val downloadMethod by appPreferences.downloadMethodFlow
        .collectAsState(initial = appPreferences.downloadMethod)
    val storageLocation by appPreferences.storageLocationFlow
        .collectAsState(initial = appPreferences.storageLocation)
    val autoCheckUpdates by appPreferences.autoCheckUpdatesFlow
        .collectAsState(initial = appPreferences.autoCheckUpdates)
    val silentUpdateDownload by appPreferences.silentUpdateDownloadFlow
        .collectAsState(initial = appPreferences.silentUpdateDownload)

    var choiceDialog by remember { mutableStateOf<ChoiceDialogState?>(null) }

    val storageLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storageManager.changeStorageLocation(uri)
        }
    }

    // Revert the external player preference if the selected app is no longer installed
    LaunchedEffect(externalPlayerApp) {
        if (externalPlayerApp != ExternalPlayerPackage.SYSTEM_DEFAULT &&
            !context.packageManager.isPackageInstalled(externalPlayerApp)
        ) {
            appPreferences.externalPlayerApp = ExternalPlayerPackage.SYSTEM_DEFAULT
        }
    }

    fun checkForUpdates() {
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        scope.launch {
            when (val result = updateChecker.check(ignoreDismissed = true)) {
                is UpdateCheckResult.UpToDate ->
                    Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                is UpdateCheckResult.UpdateAvailable ->
                    updateDialogState.requestUpdate(result.info, allowDismiss = false)
                is UpdateCheckResult.Error ->
                    Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.activity_name_settings)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            // Music player
            SettingsCategoryHeader(title = stringResource(R.string.pref_category_music_player))
            SwitchPreferenceRow(
                title = stringResource(R.string.pref_music_notification_always_dismissible_title),
                summary = stringResource(
                    if (musicNotificationAlwaysDismissible) {
                        R.string.pref_music_notification_always_dismissible_summary_on
                    } else {
                        R.string.pref_music_notification_always_dismissible_summary_off
                    },
                ),
                checked = musicNotificationAlwaysDismissible,
                onCheckedChange = { appPreferences.musicNotificationAlwaysDismissible = it },
            )

            // Video player
            SettingsCategoryHeader(title = stringResource(R.string.pref_category_video_player))

            val videoPlayerOptions = listOf(
                ChoiceItem(VideoPlayerType.WEB_PLAYER, R.string.video_player_web, R.string.video_player_web_description),
                ChoiceItem(VideoPlayerType.MPV_PLAYER, R.string.video_player_mpv, R.string.video_player_mpv_description),
                ChoiceItem(VideoPlayerType.EXO_PLAYER, R.string.video_player_integrated, R.string.video_player_native_description),
                ChoiceItem(
                    VideoPlayerType.EXTERNAL_PLAYER,
                    R.string.video_player_external,
                    R.string.video_player_external_description,
                ),
            )
            SingleSelectPreferenceRow(
                title = stringResource(R.string.pref_video_player_type_title),
                items = videoPlayerOptions,
                selectedKey = playerType,
                onClick = {
                    choiceDialog = ChoiceDialogState(
                        title = context.getString(R.string.pref_video_player_type_title),
                        items = videoPlayerOptions,
                        selectedKey = playerType,
                        onSelect = { appPreferences.videoPlayerType = it as String },
                    )
                },
            )

            val isIntegratedPlayer =
                playerType == VideoPlayerType.EXO_PLAYER || playerType == VideoPlayerType.MPV_PLAYER

            if (isIntegratedPlayer) {
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_start_landscape_video_in_landscape),
                    checked = startLandscapeVideoInLandscape,
                    onCheckedChange = { appPreferences.exoPlayerStartLandscapeVideoInLandscape = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_allow_brightness_volume_gesture),
                    checked = allowSwipeGestures,
                    onCheckedChange = { appPreferences.exoPlayerAllowSwipeGestures = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_remember_brightness),
                    checked = rememberBrightness,
                    enabled = allowSwipeGestures,
                    onCheckedChange = { checked ->
                        appPreferences.exoPlayerRememberBrightness = checked
                        if (!checked) {
                            appPreferences.exoPlayerBrightness = BRIGHTNESS_OVERRIDE_NONE
                        }
                    },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_allow_press_speed_up),
                    summary = stringResource(R.string.pref_exoplayer_allow_press_speed_up_summary),
                    checked = allowPressSpeedUp,
                    onCheckedChange = { appPreferences.exoPlayerAllowPressSpeedUp = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_allow_background_audio),
                    summary = stringResource(R.string.pref_exoplayer_allow_background_audio_summary),
                    checked = allowBackgroundAudio,
                    onCheckedChange = { appPreferences.exoPlayerAllowBackgroundAudio = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_allow_horizontal_gesture),
                    summary = stringResource(R.string.pref_exoplayer_allow_horizontal_gesture_summary),
                    checked = allowHorizontalGesture,
                    onCheckedChange = { appPreferences.exoPlayerAllowHorizontalGesture = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_double_tap_pause),
                    summary = stringResource(R.string.pref_exoplayer_double_tap_pause_summary),
                    checked = doubleTapPause,
                    onCheckedChange = { appPreferences.exoPlayerDoubleTapPause = it },
                )
                // 「播放缓冲区大小」：ExoPlayer 用它设置缓冲时长，mpv 用它设置 demuxer 预读
                // 时长与缓存上限；蜂窝网络下两个引擎都会自动收紧一档。
                val networkBufferOptions = listOf(
                    ChoiceItem(
                        Constants.NETWORK_BUFFER_AUTO,
                        R.string.network_buffer_auto,
                        R.string.network_buffer_auto_description,
                    ),
                    ChoiceItem(
                        Constants.NETWORK_BUFFER_LARGE,
                        R.string.network_buffer_large,
                        R.string.network_buffer_large_description,
                    ),
                    ChoiceItem(
                        Constants.NETWORK_BUFFER_EXTRA_LARGE,
                        R.string.network_buffer_extra_large,
                        R.string.network_buffer_extra_large_description,
                    ),
                )
                val networkBufferSummary = listOfNotNull(
                    networkBufferOptions.find { it.key == networkBuffer }?.let { stringResource(it.titleRes) },
                    stringResource(R.string.pref_playback_buffer_mobile_note),
                ).joinToString(" · ")
                SingleSelectPreferenceRow(
                    title = stringResource(R.string.pref_exoplayer_network_buffer),
                    summary = networkBufferSummary,
                    items = networkBufferOptions,
                    selectedKey = networkBuffer,
                    onClick = {
                        choiceDialog = ChoiceDialogState(
                            title = context.getString(R.string.pref_exoplayer_network_buffer),
                            items = networkBufferOptions,
                            selectedKey = networkBuffer,
                            onSelect = { appPreferences.exoPlayerNetworkBuffer = it as String },
                        )
                    },
                )
            }

            when (playerType) {
                VideoPlayerType.EXO_PLAYER -> {
                    // The preference is only ever changed from the settings screen - never
                    // while a video is playing - so there is nothing to gate here: a session
                    // that ends up transcoding simply does not use the renderer
                    // (see AppPreferences.resolveExoPlayerDirectPlayAss).
                    SwitchPreferenceRow(
                        title = stringResource(R.string.pref_exoplayer_direct_play_ass),
                        summary = stringResource(R.string.pref_exoplayer_direct_play_ass_description),
                        checked = directPlayAss,
                        onCheckedChange = { appPreferences.exoPlayerDirectPlayAss = it },
                    )
                }
                VideoPlayerType.MPV_PLAYER -> {
                    val optionCount = MpvConfigManager.countOptions(mpvCustomConfig)
                    val mpvSummary = if (optionCount == 0) {
                        stringResource(R.string.pref_mpv_custom_config_summary_empty)
                    } else {
                        stringResource(R.string.pref_mpv_custom_config_summary_count, optionCount)
                    }
                    ClickPreferenceRow(
                        title = stringResource(R.string.pref_mpv_custom_config),
                        summary = mpvSummary,
                        onClick = onOpenMpvConfigEditor,
                    )
                    SwitchPreferenceRow(
                        title = stringResource(R.string.pref_mpv_compare_entry),
                        summary = stringResource(R.string.pref_mpv_compare_entry_summary),
                        checked = mpvShowCompareEntry,
                        onCheckedChange = { appPreferences.mpvShowCompareEntry = it },
                    )
                }
                else -> Unit
            }

            // 移动网络（蜂窝）播放：是否先弹窗确认，以及确认降码率时使用的档位。
            if (isIntegratedPlayer) {
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_mobile_data_prompt_title),
                    summary = stringResource(R.string.pref_mobile_data_prompt_summary),
                    checked = mobileDataPlaybackPrompt,
                    onCheckedChange = { appPreferences.mobileDataPlaybackPrompt = it },
                )
                val mobileDataBitrateOptions = listOf(
                    ChoiceItem(Constants.MOBILE_DATA_BITRATE_480P, R.string.mobile_data_bitrate_480p),
                    ChoiceItem(Constants.MOBILE_DATA_BITRATE_720P, R.string.mobile_data_bitrate_720p),
                    ChoiceItem(Constants.MOBILE_DATA_BITRATE_1080P, R.string.mobile_data_bitrate_1080p),
                )
                SingleSelectPreferenceRow(
                    title = stringResource(R.string.pref_mobile_data_bitrate_title),
                    items = mobileDataBitrateOptions,
                    selectedKey = mobileDataMaxBitrate,
                    onClick = {
                        choiceDialog = ChoiceDialogState(
                            title = context.getString(R.string.pref_mobile_data_bitrate_title),
                            items = mobileDataBitrateOptions,
                            selectedKey = mobileDataMaxBitrate,
                            onSelect = { key -> (key as? Int)?.let { appPreferences.mobileDataMaxBitrate = it } },
                        )
                    },
                )
            }

            if (playerType == VideoPlayerType.EXTERNAL_PLAYER) {
                val externalPlayerOptions = listOf(
                    ChoiceItem(
                        ExternalPlayerPackage.SYSTEM_DEFAULT,
                        R.string.external_player_system_default,
                        R.string.external_player_system_default_description,
                    ),
                    ChoiceItem(
                        ExternalPlayerPackage.MPV_PLAYER,
                        R.string.external_player_mpv,
                        R.string.external_player_mpv_description,
                    ),
                    ChoiceItem(
                        ExternalPlayerPackage.MX_PLAYER_FREE,
                        R.string.external_player_mx_player_free,
                        R.string.external_player_mx_player_free_description,
                    ),
                    ChoiceItem(
                        ExternalPlayerPackage.MX_PLAYER_PRO,
                        R.string.external_player_mx_player_pro,
                        R.string.external_player_mx_player_pro_description,
                    ),
                    ChoiceItem(
                        ExternalPlayerPackage.VLC_PLAYER,
                        R.string.external_player_vlc_player,
                        R.string.external_player_vlc_player_description,
                    ),
                    ChoiceItem(
                        ExternalPlayerPackage.MPVKT_PLAYER,
                        R.string.external_player_mpvkt,
                        R.string.external_player_mpvkt_description,
                    ),
                ).filter { item ->
                    item.key == ExternalPlayerPackage.SYSTEM_DEFAULT ||
                        context.packageManager.isPackageInstalled(item.key)
                }
                SingleSelectPreferenceRow(
                    title = stringResource(R.string.external_player_app),
                    items = externalPlayerOptions,
                    selectedKey = externalPlayerApp,
                    onClick = {
                        choiceDialog = ChoiceDialogState(
                            title = context.getString(R.string.external_player_app),
                            items = externalPlayerOptions,
                            selectedKey = externalPlayerApp,
                            onSelect = { appPreferences.externalPlayerApp = it as String },
                        )
                    },
                )
            }

            val subtitleSettingsIntent = remember { Intent(Settings.ACTION_CAPTIONING_SETTINGS) }
            if (subtitleSettingsIntent.resolveActivity(context.packageManager) != null) {
                ClickPreferenceRow(
                    title = stringResource(R.string.pref_subtitle_style),
                    summary = stringResource(R.string.pref_subtitle_style_summary),
                    onClick = { context.startActivity(subtitleSettingsIntent) },
                )
            }

            // Downloads
            SettingsCategoryHeader(title = stringResource(R.string.pref_category_downloads))

            val downloadMethodOptions = listOf(
                ChoiceItem(
                    DownloadMethod.WIFI_ONLY.intValue,
                    R.string.wifi_only,
                    R.string.wifi_only_summary,
                ),
                ChoiceItem(
                    DownloadMethod.MOBILE_DATA.intValue,
                    R.string.mobile_data,
                    R.string.mobile_data_summary,
                ),
                ChoiceItem(
                    DownloadMethod.MOBILE_AND_ROAMING.intValue,
                    R.string.mobile_data_and_roaming,
                    R.string.mobile_data_and_roaming_summary,
                ),
            )
            SingleSelectPreferenceRow(
                title = stringResource(R.string.network_title),
                items = downloadMethodOptions,
                selectedKey = downloadMethod.intValue,
                onClick = {
                    choiceDialog = ChoiceDialogState(
                        title = context.getString(R.string.network_title),
                        items = downloadMethodOptions,
                        selectedKey = downloadMethod.intValue,
                        onSelect = { key ->
                            DownloadMethod.fromInt(key as Int)?.let { appPreferences.downloadMethod = it }
                        },
                    )
                },
            )

            val storageLocationName = remember(storageLocation) {
                storageLocation?.toUri()?.let { DocumentFile.fromTreeUri(context, it)?.name }
            }
            val currentStorageLocation = storageManager.getStorageLocation()
            ClickPreferenceRow(
                title = stringResource(R.string.pref_download_location),
                summary = storageLocationName ?: stringResource(R.string.menu_item_none),
                onClick = {
                    storageLocationPicker.launch(currentStorageLocation?.uri ?: storageManager.defaultStorageLocation)
                },
            )

            // Updates（debug 包无法被 release APK 覆盖安装，不展示更新设置）
            if (updateChecker.isSupported) {
                SettingsCategoryHeader(title = stringResource(R.string.pref_category_update))

                val channelName = if (updateChecker.currentChannel == UpdateChannel.GITHUB) {
                    stringResource(R.string.update_channel_github)
                } else {
                    stringResource(R.string.update_channel_cnb)
                }
                ClickPreferenceRow(
                    title = stringResource(R.string.pref_update_channel),
                    summary = stringResource(R.string.pref_update_channel_summary, channelName),
                    enabled = false,
                    onClick = {},
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_auto_check_updates),
                    summary = stringResource(R.string.pref_auto_check_updates_summary),
                    checked = autoCheckUpdates,
                    onCheckedChange = { appPreferences.autoCheckUpdates = it },
                )
                SwitchPreferenceRow(
                    title = stringResource(R.string.pref_silent_download),
                    summary = stringResource(R.string.pref_silent_download_summary),
                    checked = silentUpdateDownload,
                    onCheckedChange = { appPreferences.silentUpdateDownload = it },
                )
                ClickPreferenceRow(
                    title = stringResource(R.string.pref_check_update),
                    summary = stringResource(R.string.pref_check_update_summary),
                    onClick = { checkForUpdates() },
                )
            }
        }
    }

    choiceDialog?.let { dialog ->
        SingleSelectDialog(
            dialog = dialog,
            onDismiss = { choiceDialog = null },
        )
    }
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun PreferenceRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun SwitchPreferenceRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    PreferenceRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
private fun ClickPreferenceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    PreferenceRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        trailing = null,
    )
}

@Composable
private fun <T> SingleSelectPreferenceRow(
    title: String,
    items: List<ChoiceItem<T>>,
    selectedKey: T,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = items.find { it.key == selectedKey }?.let { stringResource(it.titleRes) },
) {
    PreferenceRow(
        title = title,
        summary = summary,
        enabled = true,
        onClick = onClick,
        trailing = null,
    )
}

@Composable
private fun SingleSelectDialog(
    dialog: ChoiceDialogState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = dialog.title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                dialog.items.forEach { item ->
                    val selected = item.key == dialog.selectedKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!selected) dialog.onSelect(item.key)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                if (!selected) dialog.onSelect(item.key)
                                onDismiss()
                            },
                        )
                        Column {
                            Text(
                                text = stringResource(item.titleRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            val itemSummary = item.summaryRes
                            if (itemSummary != null) {
                                Text(
                                    text = stringResource(itemSummary),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.download_cancel))
            }
        },
    )
}

private class ChoiceItem<T>(
    val key: T,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int? = null,
)

private class ChoiceDialogState(
    val title: String,
    val items: List<ChoiceItem<*>>,
    val selectedKey: Any?,
    val onSelect: (Any?) -> Unit,
)
