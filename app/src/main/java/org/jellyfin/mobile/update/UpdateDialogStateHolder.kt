package org.jellyfin.mobile.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jellyfin.mobile.app.AppPreferences

/**
 * 更新相关对话框的跨页面状态层，供 MainActivity 自动检查与设置页手动检查共用，
 * 由挂在根 Compose 宿主上的 UpdateDialogHost 观察并渲染。
 */
class UpdateDialogStateHolder(
    private val appPreferences: AppPreferences,
) {
    /**
     * 待展示的"发现新版本"提示；null 表示不展示。
     */
    class UpdatePrompt(val info: UpdateInfo, val allowDismiss: Boolean)

    var prompt by mutableStateOf<UpdatePrompt?>(null)
        private set

    /**
     * 前台下载进度对话框对应的版本信息；null 表示不展示。
     */
    var progressInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    var installPermissionVisible by mutableStateOf(false)
        private set

    fun requestUpdate(info: UpdateInfo, allowDismiss: Boolean) {
        prompt = UpdatePrompt(info, allowDismiss)
    }

    fun dismissPrompt() {
        prompt = null
    }

    fun ignoreVersion(versionName: String) {
        appPreferences.dismissedUpdateVersion = versionName
        prompt = null
    }

    fun showProgress(info: UpdateInfo) {
        prompt = null
        progressInfo = info
    }

    fun hideProgress() {
        progressInfo = null
    }

    fun showInstallPermission() {
        installPermissionVisible = true
    }

    fun hideInstallPermission() {
        installPermissionVisible = false
    }
}
