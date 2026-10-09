package org.jellyfin.mobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.app.AppPreferences
import timber.log.Timber

/**
 * 检查发布渠道是否有新版本。
 *
 * 渠道由构建来源决定（[BuildConfig.UPDATE_CHANNEL]）：
 * GitHub Actions 构建的 APK 检查 GitHub Releases，CNB 流水线构建的检查 CNB Releases。
 */
class UpdateChecker(
    private val appPreferences: AppPreferences,
    private val gitHubSource: GitHubReleaseSource,
    private val cnbSource: CnbReleaseSource,
) {
    /** 当前构建对应的更新渠道。 */
    val currentChannel: String get() = BuildConfig.UPDATE_CHANNEL

    private fun sourceFor(channel: String): ReleaseSource =
        if (channel == UpdateChannel.GITHUB) gitHubSource else cnbSource

    /**
     * 在当前构建渠道上检查更新。
     *
     * @param ignoreDismissed 手动检查时传 true，忽略"已忽略版本"记录
     */
    suspend fun check(ignoreDismissed: Boolean = false): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val channel = currentChannel
            try {
                val info = sourceFor(channel).fetchLatest()
                    ?: return@withContext UpdateCheckResult.Error("Unable to parse release info")

                if (info.versionName == BuildConfig.VERSION_NAME) {
                    return@withContext UpdateCheckResult.UpToDate
                }

                // 用户选择过"忽略此版本"则视为已最新（仅自动检查时生效）
                if (!ignoreDismissed && appPreferences.dismissedUpdateVersion == info.versionName) {
                    return@withContext UpdateCheckResult.UpToDate
                }

                UpdateCheckResult.UpdateAvailable(info)
            } catch (e: Exception) {
                Timber.e(e, "Update check failed (channel=$channel)")
                UpdateCheckResult.Error(e.message)
            }
        }
}
