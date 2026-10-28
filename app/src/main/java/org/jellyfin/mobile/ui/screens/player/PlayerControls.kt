package org.jellyfin.mobile.ui.screens.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

/** 全屏模态层（对比面板等）用的均匀压暗。 */
val ControlsScrim = Color(0x60000000)

/**
 * 控件层遮罩。
 *
 * 原先是一层平铺的 37.5% 黑：中间的通透度被白白吃掉，而顶部/底部的按钮区又不够暗 ——
 * 纯白图标压在一帧亮画面上时对比度可能低于 3:1。改成上下渐变压暗、中间保持通透：
 * 文字所在的区域更清楚，画面主体几乎不受影响。
 */
private val ControlsScrimBrush = Brush.verticalGradient(
    0.0f to Color(0xB3000000),
    0.20f to Color(0x59000000),
    0.42f to Color(0x00000000),
    0.62f to Color(0x33000000),
    1.0f to Color(0xB3000000),
)
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
    /** Whether the configuration comparison entry should be offered (mpv engine, not switched off). */
    val mpvCompareEnabled: Boolean = false,
    /**
     * 「全屏」按钮实际会旋转设备（横屏视频），而不是切换沉浸式。文案与角标据此区分，
     * 否则同一个图标在两种视频上做两件事，用户无法预判。
     */
    val rotatesOnFullscreen: Boolean = false,
    /**
     * 是否处于加载等待（起播前 / 缓冲中）。
     *
     * 为 true 时隐藏中央那排按钮：加载指示器就画在正中间，两者叠在一起时蓝色圆环会
     * 绕着播放键转，播放键还会在就绪的瞬间从「播放」变成「暂停」——看起来就像指示器
     * 突然跳了一下。让转圈独占画面中心，就绪后再把按钮淡回来。
     */
    val loading: Boolean = false,
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
    val onShowCompare: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onMenuExpand: (PlayerMenuType?) -> Unit,
    val onScrubStart: () -> Unit,
    val onScrubMove: (Long) -> Unit,
    val onScrubStop: (Long) -> Unit,
    /**
     * 控件层上发生的任意一次触摸。宿主用它重置自动隐藏计时：按下按钮后计时重新开始，
     * 不会出现「刚点完一个菜单，手指还停在屏幕上，控件已经淡出」。
     */
    val onUserInteraction: () -> Unit,
    /**
     * 手指是否仍按在控件层上。
     *
     * 只重置计时是不够的：调进度、拉音量、盯着菜单比较音轨都可能按得久一点，计时器到点
     * 照样把控件（连同正在操作的菜单）收走。按下期间挂起隐藏，抬手后再重新计时。
     */
    val onTouchActiveChanged: (Boolean) -> Unit,
    /**
     * 单击控件层的空白处（不是按钮、不是进度条）。
     *
     * 控件可见时单击画面任意空白位置立刻收起控件，这是视频播放器的通用手势：用户想让出
     * 画面时不该被迫等自动隐藏计时走完。之前只有手势层处理单击，而控件可见时手势层被
     * 控件层挡住，点击等于无效 —— 于是「想收起控件」只能干等。
     */
    val onTapBackground: () -> Unit,
    /**
     * 控件层空白处的双击，转发给手势处理器（暂停/播放或快进/快退，取决于用户设置）。
     *
     * 控件可见时手势层被控件层挡住，双击收不到——不转发的话用户得先点一下收起控件、
     * 再双击，体感就是「双击要按三下」。
     */
    val onDoubleTap: (Offset) -> Unit,
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
    // actions 每次重组都是新实例（位置每 200ms 变一次），用 rememberUpdatedState 取最新回调，
    // pointerInput 的 key 保持稳定，避免手势进行中被取消重启。
    val onUserInteraction by rememberUpdatedState(actions.onUserInteraction)
    val onTouchActiveChanged by rememberUpdatedState(actions.onTouchActiveChanged)
    val onTapBackground by rememberUpdatedState(actions.onTapBackground)
    val onDoubleTap by rememberUpdatedState(actions.onDoubleTap)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ControlsScrimBrush)
            // 非消费型观察者：任何一次按下都算「用户还在操作」，交给宿主重置自动隐藏计时，
            // 并在按住期间挂起隐藏。不用 clickable，避免吞掉下面按钮/进度条的点击。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onUserInteraction()
                    onTouchActiveChanged(true)
                    // 抬手或被系统取消都要解除挂起，否则控件会永久留在屏幕上。
                    waitForUpOrCancellation()
                    onTouchActiveChanged(false)
                    onUserInteraction()
                }
            },
    ) {
        // 空白处的单击 / 双击。
        //
        // 单独一层、放在所有控件**下面**（同级后绘制者优先命中）：点按钮时事件根本不会到
        // 这里，点空白才由这一层收下。反过来说，这一层绝不能挂在父节点上——detectTapGestures
        // 会 consume 掉 DOWN，父节点的这个 consume 会让子按钮 clickable 的后续事件判定成
        // 「已被消费」而取消，整个控件栏就点不动了（第一版就踩了这个坑）。
        //
        // detectTapGestures 自带单击/双击区分（单击要等一个双击窗口才回调），这也是为什么
        // 收起控件会慢约 300ms：不和双击抢手势，就只能等窗口过期。
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTapBackground() },
                        onDoubleTap = { offset -> onDoubleTap(offset) },
                        // 长按不算轻点：按住看画面再松手不该把控件收走。
                        onLongPress = {},
                    )
                },
        )

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
                    contentDescription = stringResource(R.string.back),
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
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 8.dp),
            )
        }

        // Center controls: previous / previous chapter / play-pause / next chapter / next
        // 加载等待期间整排隐藏：加载指示器就画在正中，叠在一起等于让蓝圈绕着播放键转。
        // 用淡入淡出而不是 if：就绪那一帧整排按钮会凭空出现，与"起播不跳变"的观感相反。
        // 退出比进入快，让按钮先让位、转圈后消失，两段动画在时间上有重叠而不是硬切。
        AnimatedVisibility(
            visible = !state.loading,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(60)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(
                    onClick = actions.onPrevious,
                    iconRes = R.drawable.ic_skip_previous_black_32dp,
                    contentDescription = stringResource(R.string.playback_previous),
                    backdrop = true,
                )
                if (state.hasChapters) {
                    PlayerIconButton(
                        onClick = actions.onPreviousChapter,
                        iconRes = R.drawable.ic_skip_previous_chapter_black_32dp,
                        contentDescription = stringResource(R.string.playback_previous_chapter),
                        backdrop = true,
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
                    backdrop = true,
                )
                if (state.hasChapters) {
                    PlayerIconButton(
                        onClick = actions.onNextChapter,
                        iconRes = R.drawable.ic_skip_next_chapter_black_32dp,
                        contentDescription = stringResource(R.string.playback_next_chapter),
                        backdrop = true,
                    )
                }
                PlayerIconButton(
                    onClick = actions.onNext,
                    iconRes = R.drawable.ic_skip_next_black_32dp,
                    contentDescription = stringResource(R.string.playback_next),
                    enabled = state.hasNext,
                    backdrop = true,
                )
            }
        }

        // Bottom: progress row + button bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            StatusSummary(menus = menus)
            ProgressRow(state = state, actions = actions)
            BottomBar(state = state, menus = menus, actions = actions)
        }
    }
}

/**
 * 底栏上方的状态摘要：只列「非默认值」，因此正常播放时它不存在。
 *
 * 倍速、清晰度、解码器切换之后图标本身不变化，菜单一关就无从确认当前状态 —— 用户想知道
 * 「我现在是不是 1.5 倍速」只能再把菜单打开一遍。这里把当前值一行摆出来，且只摆偏离默认
 * 的那些，避免常驻噪音。
 */
@Composable
private fun StatusSummary(menus: PlayerMenusState) {
    val speed = menus.speeds.firstOrNull { it.selected }?.speed
    val quality = menus.qualities.firstOrNull { it.selected && it.bitrate != 0 }?.title
    val softwareDecoder = menus.decoders.firstOrNull { it.selected }?.type == DecoderType.SOFTWARE

    val chips = buildList {
        if (speed != null && speed != 1f) add(formatSpeedLabel(speed))
        if (quality != null) add(quality)
        if (softwareDecoder) add(stringResource(R.string.playback_badge_software_decoder))
    }
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { label ->
            Text(
                text = label,
                color = ControlsText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
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
    val qualityLabel = menus.qualities.firstOrNull { it.selected }?.title
    val decoderLabel = menus.decoders.firstOrNull { it.selected }?.title

    // 入口数量随「有字幕 / 有队列」变化（4~6 个），所以用等分权重而不是固定宽度的顺序排列：
    // 固定宽度在 320~360dp 的竖屏上会把末尾按钮压成 0 宽——既看不见也点不到。等分之后
    // 每个入口至少拿到「可用宽度 / 6」，最窄的竖屏也能保持 ≥48dp 的触控宽度。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.AUDIO) },
                iconRes = R.drawable.ic_music_note_white_24dp,
                contentDescription = stringResource(R.string.playback_audio_streams),
                modifier = Modifier.fillMaxWidth(),
            )
            AudioTracksMenu(
                expanded = menus.expanded == PlayerMenuType.AUDIO,
                items = menus.audioTracks,
                onSelect = actions.onAudioTrackSelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
        }
        if (state.subtitleCount > 0) {
            PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
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
                    modifier = Modifier.fillMaxWidth(),
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
        PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.SPEED) },
                iconRes = R.drawable.ic_slow_motion_video_white_24dp,
                contentDescription = stringResource(R.string.playback_speed),
                modifier = Modifier.fillMaxWidth(),
            )
            SpeedMenu(
                expanded = menus.expanded == PlayerMenuType.SPEED,
                items = menus.speeds,
                onSelect = actions.onSpeedSelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
        }
        if (state.queueSize > 1) {
            PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
                PlayerIconButton(
                    onClick = actions.onShowEpisodes,
                    iconRes = R.drawable.ic_playlist,
                    contentDescription = stringResource(R.string.episode_picker_title),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
            PlayerIconButton(
                onClick = actions.onToggleFullscreen,
                // 横屏视频时这个按钮实际是「旋转设备」：竖屏→横屏、横屏→竖屏。
                // 两个方向用**同一组**转屏图标（同一形状、方向相反），不掺全屏图标——
                // 之前竖屏是转屏图标、横屏变成「退出全屏」，动作没变图标却换了家族，
                // 看起来像换了个功能。图标内部已把内容缩到与其他底栏图标同一光学尺寸，
                // 不会比旁边的按钮显大。
                iconRes = when {
                    state.rotatesOnFullscreen -> if (state.isFullscreen) {
                        R.drawable.ic_rotate_to_portrait_white_24dp
                    } else {
                        R.drawable.ic_rotate_to_landscape_white_24dp
                    }
                    state.isFullscreen -> R.drawable.ic_fullscreen_exit_white_32dp
                    else -> R.drawable.ic_fullscreen_enter_white_32dp
                },
                // 横屏视频时这个按钮其实是「转屏」，描述如实说明动作，避免用户以为
                // 点了会变成别的分辨率；竖屏视频才是真正的沉浸式切换。
                contentDescription = stringResource(
                    when {
                        state.rotatesOnFullscreen && state.isFullscreen ->
                            R.string.playback_rotate_portrait
                        state.rotatesOnFullscreen -> R.string.playback_rotate_fullscreen
                        state.isFullscreen -> R.string.playback_exit_fullscreen
                        else -> R.string.playback_fullscreen
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 「更多」：清晰度 / 解码器 / 播放信息 / 对比 / 锁屏。子菜单同样从这个锚点展开，
        // 层级变化与用户看到的菜单来源一致。清晰度只在服务端播放时出现，其当前值直接写在
        // 标题里，不必为了确认状态再打开一次子菜单。
        PlayerMenuAnchor(modifier = Modifier.weight(1f)) {
            PlayerIconButton(
                onClick = { actions.onMenuExpand(PlayerMenuType.MORE) },
                iconRes = R.drawable.ic_more_vert_white_24dp,
                contentDescription = stringResource(R.string.playback_more),
                modifier = Modifier.fillMaxWidth(),
            )
            MoreMenu(
                expanded = menus.expanded == PlayerMenuType.MORE,
                qualityLabel = qualityLabel.takeIf { state.qualityAvailable },
                decoderLabel = decoderLabel,
                compareAvailable = state.mpvCompareEnabled,
                onOpenQuality = { actions.onMenuExpand(PlayerMenuType.QUALITY) },
                onOpenDecoder = { actions.onMenuExpand(PlayerMenuType.DECODER) },
                onShowInfo = actions.onShowInfo,
                onShowCompare = actions.onShowCompare,
                onLockScreen = actions.onLockScreen,
                onDismiss = { actions.onMenuExpand(null) },
            )
            QualityMenu(
                expanded = menus.expanded == PlayerMenuType.QUALITY,
                items = menus.qualities,
                onSelect = actions.onQualitySelected,
                onDismiss = { actions.onMenuExpand(null) },
            )
            DecoderMenu(
                expanded = menus.expanded == PlayerMenuType.DECODER,
                items = menus.decoders,
                onSelect = actions.onDecoderSelected,
                onDismiss = { actions.onMenuExpand(null) },
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
    // 48dp 是 Material 的最小触控目标；旧值 42dp 在竖屏上点起来偏小
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    /**
     * 是否给按钮垫一层圆形暗底。
     *
     * 控件层的压暗是上下渐变的，画面中部刻意保持原样（不给整幅画面蒙灰）。中央那排按钮
     * 正好落在中间，白图标直接压在明亮画面上时几乎看不见——给它们一层 25% 的圆底，
     * 既保住画面，也保住对比度。
     */
    backdrop: Boolean = false,
) {
    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center,
    ) {
        if (backdrop) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x40000000), CircleShape),
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.matchParentSize(),
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
}
