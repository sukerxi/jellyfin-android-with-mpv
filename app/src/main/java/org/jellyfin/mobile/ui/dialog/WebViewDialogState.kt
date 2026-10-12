package org.jellyfin.mobile.ui.dialog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * WebView 相关对话框（当前为"WebView 版本过旧"）的状态桥：
 * WebViewFragment 发起请求，根 Compose 宿主渲染。
 */
class WebViewDialogState {

    class OutdatedRequest(
        val webViewPackageName: String?,
        val onIgnore: () -> Unit,
    )

    var outdatedRequest by mutableStateOf<OutdatedRequest?>(null)
        private set

    fun showOutdatedWebViewDialog(webViewPackageName: String?, onIgnore: () -> Unit) {
        outdatedRequest = OutdatedRequest(webViewPackageName, onIgnore)
    }

    fun dismiss() {
        outdatedRequest = null
    }
}
