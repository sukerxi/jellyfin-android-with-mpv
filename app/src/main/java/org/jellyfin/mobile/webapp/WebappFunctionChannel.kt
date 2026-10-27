package org.jellyfin.mobile.webapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator

/**
 * Allows to call functions within the webapp
 */
class WebappFunctionChannel {
    private val internalChannel = Channel<String>()

    operator fun iterator(): ChannelIterator<String> = internalChannel.iterator()

    fun call(action: String) = internalChannel.trySend(action).isSuccess

    // Web component helpers
    fun callPlaybackManagerAction(action: String) = call("$PLAYBACK_MANAGER.$action();")
    fun setVolume(volume: Int) = call(
        "$PLAYBACK_MANAGER.sendCommand({" +
            "Name: 'SetVolume', Arguments: { Volume: $volume }" +
            "});",
    )

    fun seekTo(pos: Long) = call("$PLAYBACK_MANAGER.seekMs($pos);")
    fun goBack() = call("$NAVIGATION_HELPER.goBack();")

    /**
     * 关掉网页端的全屏加载圈。
     *
     * jellyfin-web 的 `playbackManager.play()` 只在成功路径上隐藏它：取条目 / PlaybackInfo
     * 等请求断网或超时时 promise 以失败告终，转圈会一直挂在网页正中 —— 而此时播放要么早已
     * 交给原生层，要么根本没有起来。退出播放器时兜底调用一次。
     *
     * `window.Loading` 由 jellyfin-web 10.8+ 暴露；旧版没有该全局，退化为直接摘掉 DOM 上
     * 的激活态（与 loading 组件内部的类名一致）。
     */
    fun hideWebLoading() = call(
        "(function(){" +
            "if(window.Loading&&window.Loading.hide){window.Loading.hide();}" +
            "var e=document.querySelectorAll('.docspinner.mdlSpinnerActive');" +
            "for(var i=0;i<e.length;i++){e[i].classList.remove('mdlSpinnerActive');}" +
            "})();",
    )

    companion object {
        private const val NAVIGATION_HELPER = "window.NavigationHelper"
        private const val PLAYBACK_MANAGER = "$NAVIGATION_HELPER.playbackManager"
    }
}
