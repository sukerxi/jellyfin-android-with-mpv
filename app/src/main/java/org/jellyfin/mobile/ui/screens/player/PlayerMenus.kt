package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
