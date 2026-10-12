package org.jellyfin.mobile.utils

/**
 * Back press interception hook for the player.
 *
 * 阶段 4 单 Activity 化后仅 PlayerFragment 仍实现此接口（由其临时 Fragment 宿主
 * 在阶段 5 随播放器 Compose 化一并移除）。
 */
interface BackPressInterceptor {
    /**
     * @return `true` if the event was intercepted, `false` to perform the default close action
     */
    fun onInterceptBackPressed(): Boolean = false
}
