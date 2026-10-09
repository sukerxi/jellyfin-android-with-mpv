package org.jellyfin.mobile.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences

/**
 * 负责更新对话框的展示与下载入口，供设置页与启动自动检查复用。
 */
class UpdateManager(
    private val appPreferences: AppPreferences,
) {
    /**
     * 发现新版本时弹出对话框：展示版本号、更新内容，提供
     * "立即下载 / 后台下载 / 忽略此版本"三种选择。
     */
    fun showUpdateDialog(context: Context, info: UpdateInfo, allowDismiss: Boolean) {
        val message = buildString {
            append(context.getString(R.string.update_current_version, BuildConfig.VERSION_NAME))
            append("\n")
            append(context.getString(R.string.update_new_version, info.versionName))
            append("\n")
            append(context.getString(R.string.update_channel_label, channelLabel(context, info.channel)))
            val changelog = formatChangelog(info.changelog)
            if (changelog.isNotBlank()) {
                append("\n\n")
                append(context.getString(R.string.update_changelog_label))
                append("\n")
                append(changelog)
            }
        }

        val scroll = ScrollView(context).apply {
            addView(TextView(context).apply {
                text = HtmlCompat.fromHtml(
                    escapeHtml(message).replace("\n", "<br>"),
                    HtmlCompat.FROM_HTML_MODE_COMPACT,
                )
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
                maxLines = 20
            })
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.update_available_title)
            .setView(scroll)
            .setPositiveButton(R.string.update_download_now) { _, _ ->
                startDownload(context, info, foregroundDialog = true)
            }
            .setNeutralButton(R.string.update_download_background) { _, _ ->
                startDownload(context, info, foregroundDialog = false)
            }
            .apply {
                if (allowDismiss) {
                    setNegativeButton(R.string.update_ignore) { _, _ ->
                        appPreferences.dismissedUpdateVersion = info.versionName
                    }
                }
            }
            .show()
    }

    /**
     * 开始下载。foregroundDialog=true 时展示应用内进度对话框（可取消）；
     * false 时仅通知栏静默下载。
     */
    fun startDownload(context: Context, info: UpdateInfo, foregroundDialog: Boolean) {
        if (!hasInstallPermission(context)) {
            showPermissionDialog(context)
        }

        UpdateDownloadWorker.enqueue(context, info)

        if (!foregroundDialog) {
            toast(context, context.getString(R.string.update_download_started))
            return
        }

        showProgressDialog(context, info)
    }

    private fun showProgressDialog(context: Context, info: UpdateInfo) {
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        val text = TextView(context).apply {
            setPadding(0, dp(context, 8), 0, 0)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), 0)
            addView(progress)
            addView(text)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_downloading_title, info.versionName))
            .setView(container)
            .setNegativeButton(R.string.download_cancel) { d, _ ->
                UpdateDownloadWorker.cancel(context)
                d.dismiss()
            }
            .show()

        val owner = context as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateDownloadWorker.observeProgress(context).collect { state ->
                    when (state) {
                        is DownloadProgress.Running -> {
                            if (state.percent >= 0) {
                                progress.isIndeterminate = false
                                progress.progress = state.percent
                                text.text = context.getString(
                                    R.string.update_download_progress,
                                    state.percent,
                                    formatSize(context, state.downloaded),
                                    formatSize(context, state.total),
                                )
                            } else {
                                progress.isIndeterminate = true
                                text.text = context.getString(
                                    R.string.update_download_progress_indeterminate,
                                    formatSize(context, state.downloaded),
                                )
                            }
                        }
                        DownloadProgress.Success -> {
                            dialog.dismiss()
                            launchInstall(context, info)
                        }
                        DownloadProgress.Failed -> {
                            dialog.dismiss()
                            toast(context, context.getString(R.string.update_download_failed))
                        }
                        DownloadProgress.Idle -> Unit
                    }
                }
            }
        }
    }

    /**
     * 下载完成后尝试调起安装界面；无权限时引导用户开启。
     */
    fun launchInstall(context: Context, info: UpdateInfo) {
        if (!hasInstallPermission(context)) {
            showPermissionDialog(context)
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

    private fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun showPermissionDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(R.string.dialog_button_open_settings) { _, _ ->
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatSize(context: Context, bytes: Long): String =
        if (bytes <= 0) "?" else android.text.format.Formatter.formatShortFileSize(context, bytes)

    private fun toast(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun channelLabel(context: Context, channel: String): String =
        if (channel == UpdateChannel.GITHUB) {
            context.getString(R.string.update_channel_github)
        } else {
            context.getString(R.string.update_channel_cnb)
        }

    /**
     * 规范化 release 正文用于对话框展示：
     * - 去掉与对话框"更新内容："标题重复的 markdown 标题（如 "## 更新内容（提交记录）"）；
     * - 去掉自动构建样板标题（"## Jellyfin Android ..."）；
     * - 把 markdown 转成纯文本：标题转普通行、反引号去除、链接保留文字。
     */
    private fun formatChangelog(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                !(t.startsWith("#") &&
                    (t.contains("更新内容") || t.contains("Jellyfin Android", ignoreCase = true)))
            }
            .map { line ->
                line
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace("`", "")
                    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
                    .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
