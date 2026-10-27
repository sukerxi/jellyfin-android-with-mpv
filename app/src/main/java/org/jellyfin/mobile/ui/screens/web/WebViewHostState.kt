package org.jellyfin.mobile.ui.screens.web

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 常驻网页宿主对外暴露的状态。
 *
 * 宿主不随路由切换销毁（见 [WebViewHost]），连接状态也就不再是"某个屏幕的局部状态"：
 * 返回键仲裁点要用它判断返回是否该交给 webapp，而它只在宿主内部更新。
 *
 * 实例与 WebView 同寿命 —— 切换服务器时由调用方随 `server.id` 一起重建，
 * 因此不需要任何 reset 接口。
 */
@Stable
class WebViewHostState {
    /**
     * 主框架是否已连上：bundle 请求已被拦截、开始下载。
     *
     * 不代表首屏已渲染 —— 宿主另用渲染信号决定何时淡入 WebView，
     * 返回键仲裁只需要"页面上是否已有可交互内容"的近似判断。
     */
    var connected: Boolean by mutableStateOf(false)
        internal set
}
