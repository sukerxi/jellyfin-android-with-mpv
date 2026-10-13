package org.jellyfin.mobile.ui.screens.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.ChapterMarkingState
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.TrickplayState

/**
 * 阶段 5：播放器控件栏（替代 exo_player_control_view.xml 290 行布局与 media3 内置控制器）。
 *
 * 结构：顶部返回+标题、中部播放/上下集/上下章、底部进度区（DefaultTimeBar + 章节标记 +
 * trickplay）与功能按钮行（锁屏/音轨/字幕/倍速/清晰度/解码器/信息/选集/全屏）。
 */

private val ControlsScrim = Color(0x60000000)
private val ControlsText = Color.White
private val ChapterMarkingWatched = Color(0xFF00A4DC)
private val ChapterMarkingUnwatched = Color(0xFF333333)

/** 控件栏渲染所需的全部状态。 */
data class PlayerControlsState(
    val title: String,
    val isFullscreen: Boolean,
    val hasChapters: Boolean,
    val hasNext: Boolean,
    val queueSize: Int,
    val currentQueueIndex: Int,
    val subtitleCount: Int,
    val subtitlesEnabled: Boolean,
    val qualityAvailable: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val isPlaying: Boolean,
    val chapterMarkings: List<ChapterMarkingState>,
    val trickplay: TrickplayState,
    /** 拖动进度条时的 scrub 位置（非 null 时覆盖 positionMs 显示）；松手后置空。 */
    val scrubPositionMs: Long? = null,
    /**
     * 松手后正在生效的 seek 目标位置。轮询位置在 seek 生效前仍是旧值，此期间进度条
     * 继续显示目标位置，避免先跳回 seek 起点、再跳到目标位置的观感回跳。
     */
    val pendingSeekPositionMs: Long? = null,
)

/** 下拉菜单渲染所需的全部状态。 */
data class PlayerMenusState(
    val expanded: PlayerMenuType?,
    val audioTracks: List<AudioTrackItem>,
    val subtitleTracks: List<SubtitleTrackItem>,
    val speeds: List<SpeedItem>,
    val qualities: List<QualityItem>,
    val decoders: List<DecoderItem>,
)

/** 控件栏交互回调。 */
class PlayerControlsActions(
    val onBack: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onLockScreen: () -> Unit,
    val onToggleSubtitles: () -> Unit,
    val onAudioTrackSelected: (Int) -> Unit,
    val onSubtitleTrackSelected: (Int) -> Unit,
    val onSpeedSelected: (Float) -> Unit,
    val onQualitySelected: (Int?) -> Unit,
    val onDecoderSelected: (DecoderType) -> Unit,
    val onShowInfo: () -> Unit,
    val onShowEpisodes: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onMenuExpand: (PlayerMenuType?) -> Unit,
    val onScrubStart: () -> Unit,
    val onScrubMove: (Long) -> Unit,
    val onScrubStop: (Long) -> Unit,
)

/** DefaultTimeBar 的 scrub 监听转发（字段由 SideEffect 保持最新）。 */
private class TimeBarCallbacks {
    var onScrubStart: () -> Unit = {}
    var onScrubMove: (Long) -> Unit = {}
    var onScrubStop: (Long) -> Unit = {}
    var isScrubbing = false

    val listener = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            isScrubbing = true
            onScrubStart()
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
            onScrubMove(position)
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, cancelled: Boolean) {
            isScrubbing = false
            onScrubStop(position)
        }
    }
}

@Composable
fun PlayerControls(
    state: PlayerControlsState,
    menus: PlayerMenusState,
    actions: PlayerControlsActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ControlsScrim),
    ) {
        // Top bar: back button + title
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ControlsText,
                )
            }
            Text(
                text = state.title,
                color = ControlsText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Center controls: previous / previous chapter / play-pause / next chapter / next
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                onClick = actions.onPrevious,
                iconRes = R.drawable.ic_skip_previous_black_32dp,
                contentDescription = stringResource(R.string.playback_previous),
            )
            if (state.hasChapters) {
                PlayerIconButton(
                    onClick = actions.onPreviousChapter,
                    iconRes = R.drawable.ic_skip_previous_chapter_black_32dp,
                    contentDescription = stringResource(R.string.playback_previous_chapter),
                )
            }
            PlayerIconButton(
                onClick = actions.onTogglePlayPause,
                iconRes = if (state.isPlaying) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.playback_pause else R.string.playback_play,
                ),
                buttonSize = 66.dp,
                iconSize = 42.dp,
            )
            if (state.hasChapters) {
                PlayerIconButton(
                    onClick = actions.onNextChapter,
                    iconRes = R.drawable.ic_skip_next_chapter_black_32dp,
                    contentDescription = stringResource(R.string.playback_next_chapter),
                )
            }
            PlayerIconButton(
                onClick = actions.onNext,
                iconRes = R.drawable.ic_skip_next_black_32dp,
                contentDescription = stringResource(R.string.playback_next),
                enabled = state.hasNext,
            )
        }

        // Bottom: progress row + button bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            ProgressRow(state = state, actions = actions)
            BottomBar(state = state, menus = menus, actions = actions)
        }
    }
}

@Composable
private fun ProgressRow(
    state: PlayerControlsState,
    actions: PlayerControlsActions,
    modifier: Modifier = Modifier,
) {
    val durationKnown = state.durationMs != C.TIME_UNSET && state.durationMs > 0
    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(state.effectivePositionMs()),
            color = ControlsText,
            fontSize = 14.sp,
        )
        var seekBarWidthPx by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onGloballyPositioned { coordinates -> seekBarWidthPx = coordinates.size.width },
        ) {
            PlayerTimeBar(
                state = state,
                durationKnown = durationKnown,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
            ChapterMarkingsOverlay(markings = state.chapterMarkings)
            TrickplayPreview(state = state.trickplay, seekBarWidthPx = seekBarWidthPx)
        }
        Text(
            text = if (durationKnown) formatTime(state.durationMs) else "--:--",
            color = ControlsText,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun PlayerTimeBar(
    state: PlayerControlsState,
    durationKnown: Boolean,
    actions: PlayerControlsActions,
    modifier: Modifier = Modifier,
) {
    val callbacks = remember { TimeBarCallbacks() }
    SideEffect {
        callbacks.onScrubStart = actions.onScrubStart
        callbacks.onScrubMove = actions.onScrubMove
        callbacks.onScrubStop = actions.onScrubStop
    }

    AndroidView(
        factory = { context ->
            val timeBar = LayoutInflater.from(context)
                .inflate(R.layout.view_player_timebar, null, false) as DefaultTimeBar
            timeBar.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            timeBar.addListener(callbacks.listener)
            timeBar
        },
        update = { timeBar ->
            // Don't fight the user's finger while scrubbing
            if (!callbacks.isScrubbing) timeBar.setPosition(state.effectivePositionMs())
            timeBar.setDuration(state.durationMs)
            timeBar.setBufferedPosition(state.bufferedMs)
            timeBar.isEnabled = durationKnown
        },
        onRelease = { timeBar ->
            timeBar.removeListener(callbacks.listener)
        },
        modifier = modifier,
    )
}

/** 章节标记覆盖层：与原 chapter_marking_container 一致（水平 11dp 缩进，垂直居中）。 */
@Composable
private fun BoxScope.ChapterMarkingsOverlay(markings: List<ChapterMarkingState>) {
    if (markings.isEmpty()) return
    BoxWithConstraints(
        modifier = Modifier
            .matchParentSize()
            .padding(horizontal = 11.dp),
    ) {
        val markingWidth = 3.dp
        markings.forEach { marking ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // ConstraintLayout biased positioning: left = bias * (parentWidth - viewWidth)
                    .offset(x = (maxWidth - markingWidth) * marking.bias)
                    .size(width = markingWidth, height = 15.dp)
                    .background(
                        if (marking.watched) ChapterMarkingWatched else ChapterMarkingUnwatched,
                    ),
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: PlayerControlsState,
    menus: PlayerMenusState,
    actions: PlayerControlsActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = actions.onLockScreen,
                iconRes = R.drawable.ic_screen_lock_white_24dp,
                contentDescription = stringResource(R.string.playback_lock_screen),
            )
        }
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.AUDIO) },
                iconRes = R.drawable.ic_music_note_white_24dp,
                contentDescription = stringResource(R.string.playback_audio_streams),
            )
            AudioTracksMenu(
                expanded = menus.expanded == PlayerMenuType.AUDIO,
                items = menus.audioTracks,
                onSelect = actions.onAudioTrackSelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
        }
        if (state.subtitleCount > 0) {
            PlayerMenuAnchor {
                PlayerIconButton(
                    onClick = {
                        // A single track toggles directly, multiple tracks open the menu
                        if (state.subtitleCount == 1) {
                            actions.onToggleSubtitles()
                        } else {
                            actions.onMenuExpand(PlayerMenuType.SUBTITLES)
                        }
                    },
                    iconRes = if (state.subtitlesEnabled) {
                        R.drawable.ic_subtitles_white_24dp
                    } else {
                        R.drawable.ic_subtitles_off_white_24dp
                    },
                    contentDescription = stringResource(R.string.playback_subtitles),
                )
                if (state.subtitleCount > 1) {
                    SubtitleTracksMenu(
                        expanded = menus.expanded == PlayerMenuType.SUBTITLES,
                        items = menus.subtitleTracks,
                        onSelect = actions.onSubtitleTrackSelected,
                        onDismiss = { actions.onMenuExpand(null) },
                    )
                }
            }
        }
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.SPEED) },
                iconRes = R.drawable.ic_slow_motion_video_white_24dp,
                contentDescription = stringResource(R.string.playback_speed),
            )
            SpeedMenu(
                expanded = menus.expanded == PlayerMenuType.SPEED,
                items = menus.speeds,
                onSelect = actions.onSpeedSelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
        }
        if (state.qualityAvailable) {
            PlayerMenuAnchor {
                PlayerIconButton(
                    onClick = { actions.onMenuExpand(PlayerMenuType.QUALITY) },
                    iconRes = R.drawable.ic_settings_white_24dp,
                    contentDescription = stringResource(R.string.playback_quality),
                )
                QualityMenu(
                    expanded = menus.expanded == PlayerMenuType.QUALITY,
                    items = menus.qualities,
                    onSelect = actions.onQualitySelected,
                    onDismiss = { actions.onMenuExpand(null) },
                )
            }
        }
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.DECODER) },
                iconRes = R.drawable.ic_video_settings_white_24dp,
                contentDescription = stringResource(R.string.playback_decoder),
            )
            DecoderMenu(
                expanded = menus.expanded == PlayerMenuType.DECODER,
                items = menus.decoders,
                onSelect = actions.onDecoderSelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
        }
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = actions.onShowInfo,
                iconRes = R.drawable.ic_info_white_24dp,
                contentDescription = stringResource(R.string.playback_info_title),
            )
        }
        if (state.queueSize > 1) {
            PlayerMenuAnchor {
                PlayerIconButton(
                    onClick = actions.onShowEpisodes,
                    iconRes = R.drawable.ic_playlist,
                    contentDescription = stringResource(R.string.episode_picker_title),
                )
            }
        }
        PlayerMenuAnchor {
            PlayerIconButton(
                onClick = actions.onToggleFullscreen,
                iconRes = if (state.isFullscreen) {
                    R.drawable.ic_fullscreen_exit_white_32dp
                } else {
                    R.drawable.ic_fullscreen_enter_white_32dp
                },
                contentDescription = stringResource(R.string.playback_fullscreen),
            )
        }
    }
}

/**
 * 进度条与时间文本的生效位置：拖动 scrub 预览 > seek 目标保持 > 实时轮询位置。
 * seek 生效前轮询位置仍是旧值，若此时不用目标位置，进度条会先跳回 seek 起点。
 */
private fun PlayerControlsState.effectivePositionMs(): Long =
    scrubPositionMs ?: pendingSeekPositionMs ?: positionMs

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonSize: Dp = 42.dp,
    iconSize: Dp = 26.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        enabled = enabled,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            // 禁用态置灰（Material 禁用内容透明度 0.38），与旧版 View isEnabled=false 时
            // textColorPrimary ColorStateList 的 disabled 态一致
            tint = if (enabled) ControlsText else ControlsText.copy(alpha = 0.38f),
        )
    }
}
