package org.jellyfin.mobile.player.interaction

/**
 * 阶段 5：播放器控制层 Compose 化后，`onUserLeaveHint`（按 Home/概览键）不再由
 * Fragment 接收，改由此 Koin 单例桥接：
 *
 * - [PlayerScreen][org.jellyfin.mobile.ui.screens.player.PlayerScreen] 进入组合时注册
 *   [onUserLeaveHint]（内部判断播放中则进入 PiP）；
 * - MainActivity 的 `onUserLeaveHint` 调用 [notifyUserLeaveHint]。
 */
class PlaybackController {
    /**
     * 播放器界面注册的用户离开提示回调；无播放器界面时为 null。
     */
    var onUserLeaveHint: (() -> Unit)? = null

    /**
     * 由 MainActivity.onUserLeaveHint 调用，转发给当前播放器界面。
     */
    fun notifyUserLeaveHint() {
        onUserLeaveHint?.invoke()
    }
}
