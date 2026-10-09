package org.jellyfin.mobile.update

/**
 * 单个发布渠道的最新版本信息。
 *
 * @param versionName 与 BuildConfig.VERSION_NAME 同格式的语义版本（如 3.0.0-a661b02b）
 * @param changelog   提交记录等更新内容（纯文本，可能为空）
 * @param channel     来源渠道，见 [UpdateChannel]
 */
data class UpdateInfo(
    val versionName: String,
    val tag: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val fileName: String,
    val changelog: String,
    val channel: String,
)

object UpdateChannel {
    const val GITHUB = "github"
    const val CNB = "cnb"
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String?) : UpdateCheckResult
}
