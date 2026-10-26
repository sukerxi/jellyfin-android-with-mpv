package org.jellyfin.mobile.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jellyfin.mobile.app.AppPreferences
import java.util.UUID

/**
 * 更新相关对话框的跨页面状态层，供 MainActivity 自动检查与设置页手动检查共用，
 * 由挂在根 Compose 宿主上的 UpdateDialogHost 观察并渲染。
 */
class UpdateDialogStateHolder(
    private val appPreferences: AppPreferences,
) {
    /**
     * 待展示的"发现新版本"提示；null 表示不展示。
     *
     * @param allowIgnore 是否提供"忽略此版本"选项；自动检查允许忽略并记住该版本，手动检查不提供
     */
    class UpdatePrompt(val info: UpdateInfo, val allowIgnore: Boolean)

    /**
     * 前台下载进度对话框对应的版本信息与 WorkManager 任务 ID。
     */
    class ProgressState(val info: UpdateInfo, val workId: UUID)

    var prompt by mutableStateOf<UpdatePrompt?>(null)
        private set

    /**
     * 前台下载进度对话框状态；null 表示不展示。
     */
    var progressState by mutableStateOf<ProgressState?>(null)
        private set

    var installPermissionVisible by mutableStateOf(false)
        private set

    /**
     * 下载完成但缺少「安装未知应用」权限时记下的待安装版本。
     *
     * 用户被引导去系统设置授权，回来后 app 是被 pause/resume 的，没人会再触发安装；
     * 于是下载好的包就躺在缓存里。这里把「等权限」这件事记下来，宿主在 ON_RESUME
     * 时检查权限并自动继续安装。
     */
    var pendingInstall by mutableStateOf<UpdateInfo?>(null)
        private set

    fun requestInstallAfterPermission(info: UpdateInfo) {
        pendingInstall = info
        installPermissionVisible = true
    }

    fun clearPendingInstall() {
        pendingInstall = null
    }

    fun requestUpdate(info: UpdateInfo, allowIgnore: Boolean) {
        prompt = UpdatePrompt(info, allowIgnore)
    }

    fun dismissPrompt() {
        prompt = null
    }

    fun ignoreVersion(versionName: String) {
        appPreferences.dismissedUpdateVersion = versionName
        prompt = null
    }

    fun showProgress(info: UpdateInfo, workId: UUID) {
        prompt = null
        progressState = ProgressState(info, workId)
    }

    fun hideProgress() {
        progressState = null
    }

    fun showInstallPermission() {
        installPermissionVisible = true
    }

    fun hideInstallPermission() {
        installPermissionVisible = false
    }
}
