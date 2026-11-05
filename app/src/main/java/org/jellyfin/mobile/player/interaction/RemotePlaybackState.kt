package org.jellyfin.mobile.player.interaction

/**
 * 远端播放（Cast / 后台媒体会话）是否正在进行。
 *
 * 由 Activity 在绑定服务时写入查询闭包，播放器侧只读调用。闭包捕获的是 ServiceBinder
 * 而非 Activity，且 Koin 单例不持有任何 Activity 引用，避免 ViewModel 反查 Activity
 * 以及解绑后长期持有失效 binder。
 */
class RemotePlaybackState {
    var isPlaying: () -> Boolean = { false }
}
