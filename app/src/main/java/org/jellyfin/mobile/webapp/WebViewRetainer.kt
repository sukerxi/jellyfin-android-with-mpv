package org.jellyfin.mobile.webapp

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 在路由切换期间保留 [WebView] 实例。
 *
 * 导航到设置/下载等其它路由时，WEB 目的地的组合会被销毁，AndroidView 的 onRelease 随之
 * 销毁 WebView；返回时工厂方法重建 WebView 并 loadUrl(服务器首页)，用户回到的就会是
 * web 应用首页而不是离开时的页面（旧版 Fragment 叠加方案中 WebView 始终存活）。
 * 播放器为全屏浮层，播放期间 WEB 组合不销毁，WebView 始终保持存活。
 *
 * 保留期间 WebView 只是脱离窗口，页面状态（SPA 路由、滚动位置）原样保留；复用前由
 * WebViewScreen 重新绑定客户端与 JS 桥。切换服务器或退出应用时统一销毁。
 */
class WebViewRetainer {
    private var retainedWebView: WebView? = null
    private var retainedServerId: Long? = null

    /** [destroy] 之后置位：容器已随 Activity 销毁，不再接受新的保留。 */
    private var stopped = false

    /**
     * web 应用是否已连接。随组合保留：复用 WebView 时（如从播放器返回）恢复该状态，
     * 否则返回键会从"web 应用内后退"退化为直接弹出导航路由。
     */
    var connected by mutableStateOf(false)
        private set

    fun markConnected() {
        connected = true
    }

    fun markDisconnected() {
        connected = false
    }

    /** 保留 [webView]；同一时间只保留一个，非同一实例的旧实例立即销毁。 */
    fun retain(serverId: Long, webView: WebView) {
        // 已经 destroy（Activity 退出）后不再保留：此时 WebView 持有已销毁 Activity 的
        // Context，留着只会被下一次复用，必须直接销毁。
        if (stopped) {
            webView.destroy()
            return
        }
        if (retainedWebView !== webView) destroyRetained()
        retainedWebView = webView
        retainedServerId = serverId
    }

    /**
     * 取回属于 [serverId] 的保留实例（取出后引用即清空，由新的 onRelease 决定去留）。
     * 保留实例属于其它服务器（切换过服务器）时销毁并返回 null。
     */
    fun obtain(serverId: Long): WebView? {
        val webView = retainedWebView ?: return null
        if (retainedServerId != serverId) {
            destroyRetained()
            return null
        }
        retainedWebView = null
        retainedServerId = null
        return webView
    }

    /** 销毁保留的 WebView（退出应用时调用）。调用后不再接受保留。 */
    fun destroy() {
        stopped = true
        destroyRetained()
    }

    private fun destroyRetained() {
        retainedWebView?.destroy()
        retainedWebView = null
        retainedServerId = null
    }
}
