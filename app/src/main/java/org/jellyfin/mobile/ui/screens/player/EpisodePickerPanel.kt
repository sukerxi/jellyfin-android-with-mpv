package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
 *
 * 布局参考常见视频客户端的右侧竖排列表：每集一行、行内带集数与名称，当前集用强调色高亮。
 * 相比原来的等宽数字宫格，列表能显示剧集标题，长标题也不会被 64dp 的格子裁掉。
 */

private val CardBackground = Color(0xCC000000)
private val AccentColor = Color(0xFF00A4DC)
private val RowBackground = Color(0x1FFFFFFF)
private val RowSelectedBackground = Color(0x3300A4DC)
private val TextSelected = Color.White
private val TextIdle = Color(0xE6FFFFFF)
private val TextCount = Color(0x99FFFFFF)
private val CardCornerRadius = 16.dp
private val RowCornerRadius = 8.dp

/** 竖排列表的宽度上限；竖屏时按可用宽度的 90% 收窄，避免贴边难触。 */
private val PanelMaxWidth = 360.dp

/** 选集单元格数据。 */
data class EpisodePickerItem(
    val position: Int,
    /** 集数标签：普通集为编号（"7"），特别篇为 "SP1"，无编号条目为名称。 */
    val label: String,
    /** 普通集的数字编号；仅用于本地化的「第 N 集」前缀，特别篇与无编号条目为 null。 */
    val episodeNumber: Int?,
    /** 剧集名称；与 [label] 重复或缺失时为 null。 */
    val title: String?,
    val played: Boolean,
    val selected: Boolean,
)

/**
 * 构建选集列表（逻辑沿用原 EpisodeAdapter.buildEpisodeLabel）。
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
    val display = describeEpisode(item, position)
    EpisodePickerItem(
        position = position,
        label = display.label,
        episodeNumber = display.episodeNumber,
        title = display.title,
        played = item?.userData?.played == true,
        selected = position == selectedPosition,
    )
}

private data class EpisodeDisplay(
    val label: String,
    val episodeNumber: Int?,
    val title: String?,
)

private fun describeEpisode(item: BaseItemDto?, position: Int): EpisodeDisplay {
    if (item == null) return EpisodeDisplay((position + 1).toString(), null, null)
    val name = item.name?.takeUnless(String::isBlank)
    if (item.type == BaseItemKind.EPISODE) {
        val index = item.indexNumber
        if (index != null) {
            val isSpecial = item.parentIndexNumber == 0
            return EpisodeDisplay(
                label = if (isSpecial) "SP$index" else index.toString(),
                // 特别篇不并入「第 N 集」格式：SP1 与「第 1 集」是两条不同的剧集。
                episodeNumber = index.takeUnless { isSpecial },
                title = name,
            )
        }
    }
    return EpisodeDisplay(
        label = name ?: (position + 1).toString(),
        episodeNumber = null,
        title = null,
    )
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
    val listState = rememberLazyListState()

    // Bring the currently playing episode into view whenever the panel (re)opens
    LaunchedEffect(currentIndex, items != null) {
        val count = items?.size ?: 0
        if (count > 0) {
            // 上方留两行：打开面板时既能看见「现在播到哪」，也能顺势回看前一集。
            listState.scrollToItem((currentIndex - 2).coerceIn(0, count - 1))
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
                // 先按上限收窄、再取可用宽度的九成：横屏时是固定宽度的右侧抽屉，
                // 竖屏时不至于只剩一条可点区（宽度计算见 [PanelMaxWidth]）。
                .widthIn(max = PanelMaxWidth)
                .fillMaxWidth(0.9f)
                .fillMaxHeight()
                // 卡片自己吞掉点击，否则点在卡片空白处会穿透到下面的关闭层。
                // 用 pointerInput 而不是空 clickable：只想拦截事件，不想多出一个语义上的可点区域。
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
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

                Spacer(modifier = Modifier.height(6.dp))

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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 6.dp),
                    ) {
                        items(items, key = { it.position }) { item ->
                            EpisodeRow(
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
private fun EpisodeRow(
    item: EpisodePickerItem,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(RowCornerRadius)
    val watchedLabel = stringResource(R.string.episode_picker_watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 固定 48dp 高度在 200% 字体下会把文字裁掉；改用最小高度让行随字体长高，
            // 同时保证触控高度不低于 Material 的最小目标。
            .heightIn(min = 48.dp)
            .background(
                if (item.selected) RowSelectedBackground else RowBackground,
                shape,
            )
            // 当前集用强调色描边 + 淡强调底：在深色卡片里比纯色填充更克制，
            // 也和列表里其他行的视觉层级一致。
            .then(
                if (item.selected) {
                    Modifier.border(1.dp, AccentColor, shape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                selected = item.selected
                if (item.played) stateDescription = watchedLabel
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = episodeRowTitle(item),
            color = if (item.selected) TextSelected else TextIdle,
            fontSize = 14.sp,
            fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 已看集此前用 50% 透明度表达，和「不可用」的视觉语言冲突；改为对勾角标，
        // 同时用 stateDescription 让读屏用户也能得到这个信息。
        if (item.played) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = TextCount,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp),
            )
        }
    }
}

/**
 * 行标题：普通集为「第 N 集 名称」，特别篇为「SP1 名称」，其余条目只显示名称。
 *
 * 名称缺失时退回集数标签，绝不出现「名称为空」的半截列表项。
 */
@Composable
private fun episodeRowTitle(item: EpisodePickerItem): String {
    val title = item.title
    val number = item.episodeNumber
    return when {
        number != null -> {
            val prefix = stringResource(R.string.episode_picker_episode_number, number)
            if (title != null) "$prefix $title" else prefix
        }
        title != null && title != item.label -> "${item.label}  $title"
        else -> item.label
    }
}
