package org.jellyfin.mobile.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.jellyfin.mobile.R
import java.util.UUID

/**
 * 应用更新相关的纯逻辑入口：下载任务调度、安装权限判断、安装界面调起。
 * 所有 UI（更新提示、进度、权限引导）已迁移到 Compose，见 [UpdateDialogStateHolder]
 * 与 org.jellyfin.mobile.ui.components.UpdateDialogs。
 */
class UpdateManager {

    /**
     * 调度 APK 后台下载（应用被杀死后由 WorkManager 继续执行）。
     *
     * @return 本次下载任务的唯一 ID，用于精确观察进度
     */
    fun enqueueDownload(context: Context, info: UpdateInfo): UUID =
        UpdateDownloadWorker.enqueue(context, info)

    fun cancelDownload(context: Context) {
        UpdateDownloadWorker.cancel(context)
    }

    /**
     * 下载完成后尝试调起安装界面；无权限时由调用方引导用户开启。
     */
    fun launchInstall(context: Context, info: UpdateInfo) {
        if (!hasInstallPermission(context)) {
            openInstallPermissionSettings(context)
            return
        }

        val file = UpdateDownloadWorker.apkFile(context, info.fileName)
        if (!file.exists()) {
            toast(context, context.getString(R.string.update_file_missing))
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            toast(context, context.getString(R.string.update_install_failed))
        }
    }

    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun channelLabel(context: Context, channel: String): String =
        if (channel == UpdateChannel.GITHUB) {
            context.getString(R.string.update_channel_github)
        } else {
            context.getString(R.string.update_channel_cnb)
        }

    /**
     * 规范化 release 正文用于对话框展示：
     * - 去掉与对话框"更新内容："标题重复的 markdown 标题（如 "## 更新内容（提交记录）"）；
     * - 去掉自动构建样板标题（"## Jellyfin Android ..."）与 GitHub 自动生成的 "Full Changelog" 行；
     * - 把 markdown 转成纯文本：标题转普通行、反引号去除、链接保留文字；
     * - 去掉 GitHub 自动生成提交行里的裸链接与 "by @user in" 署名。
     */
    fun formatChangelog(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                val isRepeatedTitle = t.startsWith("#") &&
                    (t.contains("更新内容") || t.contains("Jellyfin Android", ignoreCase = true))
                val isFullChangelog = t.startsWith("**Full Changelog**", ignoreCase = true) ||
                    t.startsWith("Full Changelog", ignoreCase = true)
                !isRepeatedTitle && !isFullChangelog
            }
            .map { line ->
                line
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace("`", "")
                    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
                    .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
                    // GitHub 自动生成的提交行：「* 标题 by @user in https://github.com/...」
                    .replace(Regex("""\s+by\s+@[\w.-]+\s+in\s+https?://\S+"""), "")
                    .replace(Regex("""\s+in\s+https?://\S+"""), "")
                    .replace(Regex("""https?://\S+"""), "")
                    .trimEnd()
            }
        // 压掉连续空行
        val sb = StringBuilder()
        var blankRun = 0
        for (line in lines) {
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
            } else {
                blankRun = 0
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line.trimStart())
        }
        return sb.toString().trim()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
