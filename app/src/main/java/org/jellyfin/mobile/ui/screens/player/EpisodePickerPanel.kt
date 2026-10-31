package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/**
 * 选集面板。
 *
 * 面板只占据控件层（标题栏/控制条）之间的区域：外层 [Box][controlsBounds] 与
 * [PlayerControlsState] 同尺寸，绘制在控制层之后因而位于其上方，同时不会挡住
 * 标题栏与控制条（点击这些区域仍能操作播放器）。
 *
 * @param controlsBounds 控件层的内边距（系统栏/刘海），由 [PlayerScreen] 传入
 */
@Composable
fun EpisodePickerPanel(
    currentIndex: Int,
    queueSize: Int,
    items: List<EpisodePickerItem>?,
    onSelect: (position: Int) -> Unit,
    onDismiss: () -> Unit,
    controlsBounds: PaddingValues = PaddingValues(),
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
            .padding(controlsBounds),
    ) {
        // 点外部关闭：只铺画面区，让出上下控制条。面板组合在控件层之后（z 序更高），
        // 这层若铺满全屏，返回、播放/暂停、进度条的点按都会被它吞成「关闭面板」。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = PlayerTopBarClearance, bottom = PlayerBottomBarClearance)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            color = CardBackground,
            shape = RoundedCornerShape(CardCornerRadius),
            // 与玻璃浮层同一道白色细边：面板同样是压在视频上的半透明卡片，
            // 没有描边时边缘会随画面内容忽隐忽现。
            border = BorderStroke(1.dp, PlayerGlassBorderColor),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    start = 12.dp,
                    top = PlayerTopBarClearance,
                    end = 12.dp,
                    bottom = PlayerBottomBarClearance,
                )
                .widthIn(max = 460.dp)
                .fillMaxHeight()
                // 卡片自己吞掉点击，否则点在卡片空白处会穿透到下面的关闭层。
                // 用 pointerInput 而不是空 clickable：只想拦截事件，不想多出一个语义上的可点区域。
                .pointerInput(Unit) { detectTapGestures { } },
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
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
                    // 元数据未加载：此前只留一个空 Spacer，面板看起来像坏了。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.episode_picker_loading),
                            color = TextCount,
                            fontSize = 13.sp,
                        )
                    }
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
    // 已看集此前用 50% 透明度表达，和「不可用」的视觉语言冲突；改为对勾角标，
    // 同时用 stateDescription 让读屏用户也能得到这个信息。
    val watchedLabel = stringResource(R.string.episode_picker_watched)
    Box(
        modifier = Modifier
            // 固定 48dp 高度在 200% 字体下会把文字裁掉；改用最小高度让格子随字体长高，
            // 同时保证触控高度不低于 Material 的最小目标。
            .heightIn(min = 48.dp)
            .background(background, RoundedCornerShape(CellCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                selected = item.selected
                if (item.played) stateDescription = watchedLabel
            }
            .padding(vertical = 6.dp),
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
        if (item.played) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xB3FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp)
                    .size(12.dp),
            )
        }
    }
}
