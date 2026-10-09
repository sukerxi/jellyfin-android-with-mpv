package org.jellyfin.mobile.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 在后台下载更新 APK，下载过程中通过通知展示可见进度；
 * 下载完成后点击通知即可调起系统安装界面。
 */
class UpdateDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "org.jellyfin.mobile.update.DOWNLOAD"
        const val DOWNLOAD_NOTIFICATION_ID = 68
        const val DONE_NOTIFICATION_ID = 69

        private const val TAG = "UpdateDownloadWorker"
        private const val UNIQUE_NAME = "update_download"
        private const val KEY_DOWNLOAD_URL = "downloadUrl"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_VERSION_NAME = "versionName"

        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_DOWNLOADED = "downloaded"

        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_PROGRESS_REFRESH_MS = 500L

        /**
         * 将 APK 下载到缓存目录（应用被杀死后由 WorkManager 继续执行）。
         */
        fun enqueue(context: Context, info: UpdateInfo) {
            val inputData = Data.Builder()
                .putString(KEY_DOWNLOAD_URL, info.downloadUrl)
                .putString(KEY_FILE_NAME, info.fileName)
                .putString(KEY_VERSION_NAME, info.versionName)
                .build()

            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(inputData)
                .addTag(TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            context.getSystemService<NotificationManager>()?.cancel(DOWNLOAD_NOTIFICATION_ID)
        }

        /**
         * 观察下载进度（用于应用内对话框）。返回 progress(0..100)，total<=0 时为 -1（不确定）。
         */
        fun observeProgress(context: Context): Flow<DownloadProgress> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_NAME)
                .map { infos ->
                    val info = infos.firstOrNull()
                    when {
                        info == null -> DownloadProgress.Idle
                        info.state == WorkInfo.State.SUCCEEDED -> DownloadProgress.Success
                        info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED ->
                            DownloadProgress.Failed
                        else -> {
                            val p = info.progress
                            val percent = p.getInt(KEY_PROGRESS, -1)
                            val total = p.getLong(KEY_TOTAL, -1L)
                            val downloaded = p.getLong(KEY_DOWNLOADED, 0L)
                            DownloadProgress.Running(percent, downloaded, total)
                        }
                    }
                }

        fun ensureChannel(context: Context) {
            if (!AndroidVersion.isAtLeastO) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.update_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                },
            )
        }

        fun apkFile(context: Context, fileName: String): File =
            File(context.cacheDir, "updates").apply { mkdirs() }.resolve(fileName)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val notificationManager = applicationContext.getSystemService<NotificationManager>()

    private val downloadUrl get() = inputData.getString(KEY_DOWNLOAD_URL) ?: ""
    private val fileName get() = inputData.getString(KEY_FILE_NAME) ?: "update.apk"
    private val versionName get() = inputData.getString(KEY_VERSION_NAME) ?: ""

    private val builder by lazy {
        NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID).apply {
            setContentTitle(applicationContext.getString(R.string.update_download_notification_title, versionName))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOnlyAlertOnce(true)
            setOngoing(true)
            setProgress(0, 0, true)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        return ForegroundInfo(
            DOWNLOAD_NOTIFICATION_ID,
            builder.build(),
            if (AndroidVersion.isAtLeastQ) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        val url = downloadUrl
        if (url.isBlank()) return Result.failure()

        ensureChannel(applicationContext)
        setForeground(getForegroundInfo())

        val target = apkFile(applicationContext, fileName)
        return try {
            download(url, target)
            showInstallNotification(target)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Update download failed")
            notificationManager?.cancel(DOWNLOAD_NOTIFICATION_ID)
            showFailedNotification()
            Result.failure()
        }
    }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected response ${response.code}")

            val total = response.body?.contentLength() ?: -1L
            val temp = File(target.parentFile, target.name + ".part")

            response.body?.byteStream()?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var bytesRead: Int
                    var lastRefresh = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastRefresh >= MAX_PROGRESS_REFRESH_MS) {
                            lastRefresh = now
                            updateProgress(downloaded, total)
                        }
                    }
                }
            } ?: throw IOException("Empty response body")

            temp.renameTo(target)
            updateProgress(target.length(), target.length())
        }
    }

    private suspend fun updateProgress(downloaded: Long, total: Long) {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
        builder.apply {
            if (percent >= 0) {
                setProgress(100, percent, false)
                setContentText(
                    android.text.format.Formatter.formatShortFileSize(applicationContext, downloaded) +
                        " / " + android.text.format.Formatter.formatShortFileSize(applicationContext, total),
                )
            } else {
                setProgress(0, 0, true)
            }
        }
        notificationManager?.notify(DOWNLOAD_NOTIFICATION_ID, builder.build())

        // 同步进度到 WorkManager，供应用内对话框观察
        setProgress(
            Data.Builder()
                .putInt(KEY_PROGRESS, percent)
                .putLong(KEY_TOTAL, total)
                .putLong(KEY_DOWNLOADED, downloaded)
                .build(),
        )
    }

    private fun showInstallNotification(apkFile: File) {
        notificationManager?.cancel(DOWNLOAD_NOTIFICATION_ID)

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    apkFile,
                ),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val contentIntent = PendingIntentCompat.getActivity(applicationContext, 0, installIntent, 0, false)

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.update_install_notification_title, versionName))
            .setContentText(applicationContext.getString(R.string.update_install_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        notificationManager?.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun showFailedNotification() {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.update_download_failed_notification_title))
            .setContentText(applicationContext.getString(R.string.update_download_failed_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(DONE_NOTIFICATION_ID, notification)
    }
}

/**
 * 应用内可观察的下载状态。
 */
sealed interface DownloadProgress {
    data object Idle : DownloadProgress
    data object Success : DownloadProgress
    data object Failed : DownloadProgress
    data class Running(val percent: Int, val downloaded: Long, val total: Long) : DownloadProgress
}
