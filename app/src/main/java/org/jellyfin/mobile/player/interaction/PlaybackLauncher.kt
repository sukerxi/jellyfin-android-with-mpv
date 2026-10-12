package org.jellyfin.mobile.player.interaction

/**
 * 阶段 4 单 Activity + Navigation Compose 改造后，JS 桥发起的原生播放器播放请求
 * 不再通过 Fragment arguments 传递，而是经此一次性中转：
 * [org.jellyfin.mobile.events.ActivityEventHandler] 存入，player 路由的临时 Fragment
 * 宿主在进入组合时消费（阶段 5 播放器 Compose 化后继续沿用）。
 */
class PlaybackLauncher {
    var pending: PlaybackRequest? = null
        private set

    fun launch(playOptions: PlayOptions, preferences: PlayerWebPreferences?) {
        pending = PlaybackRequest(playOptions, preferences)
    }

    fun consume(): PlaybackRequest? = pending.also { pending = null }
}

data class PlaybackRequest(
    val playOptions: PlayOptions,
    val preferences: PlayerWebPreferences?,
)
