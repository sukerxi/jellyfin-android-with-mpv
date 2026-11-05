package org.jellyfin.mobile.ui.screens.player

import android.os.SystemClock
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 控件显示 / 隐藏状态机。
 *
 * 状态本身仍由 PlayerScreen 通过 remember 持有（本类只拿到 MutableState / MutableIntState 引用），
 * 抽出来的是 show / hide / toggle、单击去抖、双击窗口内延后显示，以及交互信号与按压状态。
 *
 * 自动隐藏的两条 LaunchedEffect 仍在屏幕里：它们要读 isPlaying、controlsTimeoutMs、isScrubbing、
 * showLoadingIndicator 等屏幕状态与 snapshotFlow，搬走只会变成一长串构造参数。本类只保证
 * 「改可见性」这一侧永远经过同一组规则。
 */
@Stable
class PlayerControlsVisibility(
    private val visible: MutableState<Boolean>,
    private val interactionTick: MutableIntState,
    private val touchActive: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val doubleTapTimeoutMs: Long,
    private val toggleDebounceMs: Long,
) {
    /** 「单击呼出控件」的待生效任务。只在事件回调里写、在协程里读，不参与组合期读取。 */
    private var pendingShowJob: Job? = null
    private var lastToggleUptimeMs = 0L

    val controlsVisible: Boolean get() = visible.value
    val currentInteractionTick: Int get() = interactionTick.intValue
    val isTouchActive: Boolean get() = touchActive.value

    fun show() {
        cancelPendingShow()
        visible.value = true
        interactionTick.intValue++
    }

    /** 双击反馈专用：取消待生效的显示并立即收起。 */
    fun hide() {
        cancelPendingShow()
        visible.value = false
    }

    /** 锁屏 / PiP / 自动隐藏超时：只改可见性，不动待生效任务（保持原有语义）。 */
    fun setVisible(value: Boolean) {
        visible.value = value
    }

    fun cancelPendingShow() {
        pendingShowJob?.cancel()
        pendingShowJob = null
    }

    fun onUserInteraction() {
        interactionTick.intValue++
    }

    fun setTouchActive(active: Boolean) {
        touchActive.value = active
    }

    /**
     * 单击 = 显示 / 收起控件。
     *
     * 控件隐藏时，双击的第一击也走单击分支：若这一击立刻把控件亮出来，第二击随即把它收起，
     * 用户看到的就是双击「闪一下控件」。所以显示延后一个双击窗口，收起仍然即时。
     */
    fun toggle() {
        val now = SystemClock.uptimeMillis()
        if (now - lastToggleUptimeMs < toggleDebounceMs) return
        lastToggleUptimeMs = now
        if (visible.value) {
            visible.value = false
        } else {
            pendingShowJob = scope.launch {
                delay(doubleTapTimeoutMs)
                // 先清引用再显示：show() 自己会取消待生效任务，不能把当前协程取消掉。
                pendingShowJob = null
                show()
            }
        }
    }

    fun dispose() {
        cancelPendingShow()
    }
}
