package org.jellyfin.mobile.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.DecoderType

/**
 * 阶段 5：替代原 5 个 android.widget.PopupMenu 的 Compose 下拉菜单。
 */

/** 当前展开的菜单类型。 */
enum class PlayerMenuType {
    AUDIO,
    SUBTITLES,
    SPEED,
    QUALITY,
    DECODER,

    /** 底栏「更多」：收拢低频入口，子菜单也从同一点展开。 */
    MORE,
}

/** 音轨菜单项；[index] 为 MediaStream.index。 */
data class AudioTrackItem(
    val index: Int,
    val title: String,
    val selected: Boolean,
)

/** 字幕菜单项；[index] 为 MediaStream.index，-1 表示"关闭"。 */
data class SubtitleTrackItem(
    val index: Int,
    val title: String,
    val selected: Boolean,
)

/** 倍速菜单项。 */
data class SpeedItem(
    val speed: Float,
    val selected: Boolean,
)

/** 清晰度菜单项；[bitrate] 为 0 表示自动。 */
data class QualityItem(
    val bitrate: Int,
    val title: String,
    val selected: Boolean,
)

/** 解码器菜单项。 */
data class DecoderItem(
    val type: DecoderType,
    val title: String,
    val selected: Boolean,
)

/**
 * 承载一个按钮与其下拉菜单的容器：菜单以按钮（Box）为锚点展开。
 */
@Composable
fun PlayerMenuAnchor(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
    }
}

@Composable
fun AudioTracksMenu(
    expanded: Boolean,
    items: List<AudioTrackItem>,
    onSelect: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.title) },
                onClick = {
                    onSelect(item.index)
                    onDismiss()
                },
                trailingIcon = {
                    RadioButton(selected = item.selected, onClick = null)
                },
            )
        }
    }
}

@Composable
fun SubtitleTracksMenu(
    expanded: Boolean,
    items: List<SubtitleTrackItem>,
    onSelect: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.title) },
                onClick = {
                    onSelect(item.index)
                    onDismiss()
                },
                trailingIcon = {
                    RadioButton(selected = item.selected, onClick = null)
                },
            )
        }
    }
}

@Composable
fun SpeedMenu(
    expanded: Boolean,
    items: List<SpeedItem>,
    onSelect: (speed: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 120.dp),
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(formatSpeedLabel(item.speed)) },
                onClick = {
                    onSelect(item.speed)
                    onDismiss()
                },
                trailingIcon = {
                    RadioButton(selected = item.selected, onClick = null)
                },
            )
        }
    }
}

@Composable
fun QualityMenu(
    expanded: Boolean,
    items: List<QualityItem>,
    onSelect: (bitrate: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 160.dp),
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.title) },
                onClick = {
                    onSelect(item.bitrate.takeUnless { bitrate -> bitrate == 0 })
                    onDismiss()
                },
                trailingIcon = {
                    RadioButton(selected = item.selected, onClick = null)
                },
            )
        }
    }
}

@Composable
fun DecoderMenu(
    expanded: Boolean,
    items: List<DecoderItem>,
    onSelect: (type: DecoderType) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.title) },
                onClick = {
                    onSelect(item.type)
                    onDismiss()
                },
                trailingIcon = {
                    RadioButton(selected = item.selected, onClick = null)
                },
            )
        }
    }
}

/**
 * 底栏「更多」菜单。
 *
 * 底栏只保留高频入口，其余操作收在这里；分级项会把**当前值**写进标题
 * （如「清晰度 · 自动」），这样「现在是什么状态」不必再打开一次子菜单才能确认。
 * 清晰度/解码器点开的是同一个锚点下的子菜单，关闭「更多」与人眼看到的层级变化一致。
 *
 * @param qualityLabel 当前清晰度；null 表示本次播放不支持切换（本地文件等），该项隐藏
 * @param decoderLabel 当前解码器
 * @param compareAvailable 是否展示同帧对比入口（仅 mpv + 用户未关闭）
 */
@Composable
fun MoreMenu(
    expanded: Boolean,
    qualityLabel: String?,
    decoderLabel: String?,
    compareAvailable: Boolean,
    onOpenQuality: () -> Unit,
    onOpenDecoder: () -> Unit,
    onShowInfo: () -> Unit,
    onShowCompare: () -> Unit,
    onLockScreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp),
    ) {
        if (qualityLabel != null) {
            MoreMenuItem(
                iconRes = R.drawable.ic_settings_white_24dp,
                label = stringResource(
                    R.string.playback_menu_item_with_value,
                    stringResource(R.string.playback_quality),
                    qualityLabel,
                ),
                // 直接切到子菜单：expanded 从 MORE 变成 QUALITY，本菜单随之关闭
                onClick = onOpenQuality,
            )
        }
        if (decoderLabel != null) {
            MoreMenuItem(
                iconRes = R.drawable.ic_video_settings_white_24dp,
                label = stringResource(
                    R.string.playback_menu_item_with_value,
                    stringResource(R.string.playback_decoder),
                    decoderLabel,
                ),
                onClick = onOpenDecoder,
            )
        }
        MoreMenuItem(
            iconRes = R.drawable.ic_info_white_24dp,
            label = stringResource(R.string.playback_info_title),
            onClick = {
                onDismiss()
                onShowInfo()
            },
        )
        if (compareAvailable) {
            MoreMenuItem(
                iconRes = R.drawable.ic_compare_white_24dp,
                label = stringResource(R.string.mpv_compare_title),
                onClick = {
                    onDismiss()
                    onShowCompare()
                },
            )
        }
        HorizontalDivider()
        MoreMenuItem(
            iconRes = R.drawable.ic_screen_lock_white_24dp,
            label = stringResource(R.string.playback_lock_screen),
            onClick = {
                onDismiss()
                onLockScreen()
            },
        )
    }
}

@Composable
private fun MoreMenuItem(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}
