package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.mobile.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * 阶段 5：选集面板（替代 fragment_player.xml 的 episode_picker_container +
 * PlayerMenus.EpisodeAdapter/item_episode_picker.xml）。
 */

private val CardBackground = Color(0xCC000000)
private val AccentColor = Color(0xFF00A4DC)
private val IdleColor = Color(0x1FFFFFFF)
private val TextSelected = Color.White
private val TextIdle = Color(0xE6FFFFFF)
private val TextCount = Color(0x99FFFFFF)
private val CardCornerRadius = 16.dp
private val CellCornerRadius = 8.dp

/** 选集单元格数据。 */
data class EpisodePickerItem(
    val position: Int,
    val label: String,
    val played: Boolean,
    val selected: Boolean,
)

/**
 * 构建选集单元格列表（逻辑与原 EpisodeAdapter.buildEpisodeLabel 一致）。
 *
 * @param queueIds 队列中的条目 id（顺序即队列顺序）
 * @param items 队列条目的元数据（可能缺项）
 * @param selectedPosition 当前播放位置
 */
fun buildEpisodePickerItems(
    queueIds: List<UUID>,
    items: Map<UUID, BaseItemDto>,
    selectedPosition: Int,
): List<EpisodePickerItem> = queueIds.mapIndexed { position, id ->
    val item = items[id]
    EpisodePickerItem(
        position = position,
        label = buildEpisodeLabel(item, position),
        played = item?.userData?.played == true,
        selected = position == selectedPosition,
    )
}

private fun buildEpisodeLabel(item: BaseItemDto?, position: Int): String {
    if (item == null) return (position + 1).toString()
    return when (item.type) {
        BaseItemKind.EPISODE -> when {
            item.parentIndexNumber == 0 -> item.indexNumber?.let { "SP$it" }
            else -> item.indexNumber?.toString()
        } ?: item.name?.takeUnless(String::isBlank) ?: (position + 1).toString()
        else -> item.name?.takeUnless(String::isBlank) ?: (position + 1).toString()
    }
}

@Composable
fun EpisodePickerPanel(
    currentIndex: Int,
    queueSize: Int,
    items: List<EpisodePickerItem>?,
    onSelect: (position: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // Bring the currently playing episode into view whenever the panel (re)opens
    LaunchedEffect(currentIndex, items != null) {
        if (currentIndex >= 0) {
            gridState.scrollToItem(currentIndex.coerceAtMost((items?.size ?: 1) - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            color = CardBackground,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(start = 12.dp, top = 56.dp, end = 12.dp, bottom = 112.dp)
                .widthIn(max = 460.dp)
                .fillMaxHeight(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.episode_picker_title),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.episode_picker_count, currentIndex + 1, queueSize),
                        color = TextCount,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_white_24dp),
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier.size(18.dp),
                            tint = Color.White,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (items.isNullOrEmpty()) {
                    // Metadata still loading
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 64.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items) { item ->
                            EpisodeCell(
                                item = item,
                                onClick = {
                                    onSelect(item.position)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCell(
    item: EpisodePickerItem,
    onClick: () -> Unit,
) {
    val background = when {
        item.selected -> AccentColor
        else -> IdleColor
    }
    Box(
        modifier = Modifier
            .height(44.dp)
            .background(background, RoundedCornerShape(CellCornerRadius))
            // 已播放剧集 50% 透明度（与旧版 EpisodeAdapter 一致；选中项保持全不透明）
            .alpha(if (item.played && !item.selected) 0.5f else 1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.label,
            color = if (item.selected) TextSelected else TextIdle,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}
