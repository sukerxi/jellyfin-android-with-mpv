package org.jellyfin.mobile.ui.screens.player

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import org.jellyfin.mobile.player.interaction.PlaybackRequest

/**
 * 一次播放会话的状态集合，仍由 PlayerScreen 通过 remember 持有。
 *
 * 3c 只把「谁改这些状态、按什么顺序改」放进 [PlayerSessionCoordinator]，状态本身没有搬家：
 * 屏幕继续用 `val x by xState` 读取，读取点无需感知 coordinator 的存在，也不会因为
 * 抽取而改变重组范围。
 */
@Stable
internal class PlayerSessionState(
    val closingSoon: MutableState<Boolean>,
    val playbackEnded: MutableState<Boolean>,
    val playbackStarted: MutableState<Boolean>,
    val pipMode: MutableState<Boolean>,
    val isPlaying: MutableState<Boolean>,
    val isFullscreen: MutableState<Boolean>,
    val subtitlesEnabled: MutableState<Boolean>,
    val playbackFailure: MutableState<PlaybackFailure?>,
    val pendingMobileDataRequest: MutableState<PlaybackRequest?>,
)
