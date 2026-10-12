package org.jellyfin.mobile.utils

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Settings.System.ACCELEROMETER_ROTATION
import androidx.activity.ComponentActivity
import androidx.core.content.getSystemService
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.ui.utils.shouldShowDownloadSettingsDialog
import org.jellyfin.mobile.ui.utils.showDownloadSettingsDialog
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.UUID
import kotlin.coroutines.resume
import android.Manifest

suspend fun requestDownload(
    activity: ComponentActivity,
    mainViewModel: MainViewModel,
    itemIds: Collection<UUID>,
) {
    if (itemIds.isEmpty()) return

    val koin = object : KoinComponent {}
    val appPreferences: AppPreferences = koin.get()
    val downloadManager: DownloadManager = koin.get()

    // Show dialog to choose download location for first download
    if (activity.shouldShowDownloadSettingsDialog()) {
        activity.showDownloadSettingsDialog()
    }

    // If no storage location is set the request for choosing a download folder
    // so we'll cancel the download too
    if (appPreferences.storageLocation == null) return

    // Request permissions to send notifications about download progress
    suspendCancellableCoroutine { continuation ->
        activity.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { permissionsMap ->
            if (permissionsMap[Manifest.permission.POST_NOTIFICATIONS] == PackageManager.PERMISSION_GRANTED) {
                continuation.resume(true)
            } else {
                continuation.cancel(null)
            }
        }
    }

    // Add actual download
    val server = mainViewModel.serverState.value.server ?: return
    val user = mainViewModel.userState.value.user ?: return

    downloadManager.enqueueItems(server, user, itemIds)
}

fun Activity.isAutoRotateOn() = Settings.System.getInt(contentResolver, ACCELEROMETER_ROTATION, 0) == 1

fun PackageManager.isPackageInstalled(@ExternalPlayerPackage packageName: String) = try {
    packageName.isNotEmpty() && getApplicationInfo(packageName, 0).enabled
} catch (e: PackageManager.NameNotFoundException) {
    false
}

fun Context.createMediaNotificationChannel(notificationManager: NotificationManager) {
    if (AndroidVersion.isAtLeastO) {
        val notificationChannel = NotificationChannel(
            Constants.MEDIA_NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Media notifications"
        }
        notificationManager.createNotificationChannel(notificationChannel)
    }
}

val Context.isLowRamDevice: Boolean
    get() = getSystemService<ActivityManager>()!!.isLowRamDevice

fun Uri.extractId(): String {
    val uri = toString()
    val idRegex = Regex("""/([a-f0-9]{32}|[a-f0-9-]{36})/""")
    val idResult = idRegex.find(uri)
    val id = idResult?.groups?.get(1)?.value.toString()
    var item = id.toUUID().toString()

    val subtitleRegex = Regex("""Subtitles/(\d+)/\d+/Stream.subrip|/(\d+).subrip""")
    val subtitleResult = subtitleRegex.find(uri)
    if (subtitleResult != null) {
        item += ":${subtitleResult.groups[1]?.value ?: subtitleResult.groups[2]?.value}"
    }

    return item
}
