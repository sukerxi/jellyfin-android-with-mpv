package org.jellyfin.mobile.utils

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class BluetoothPermissionHelper(
    private val activity: MainActivity,
    private val appPreferences: AppPreferences,
) {
    /**
     * This is used to prevent the dialog from showing multiple times in a single session (activity creation).
     * Otherwise, the package manager and permission would need to be queried on every media event.
     */
    private var wasDialogShowThisSession = false

    /**
     * Compose 宿主观察：true 时渲染蓝牙权限说明对话框。
     */
    var requestVisible by mutableStateOf(false)
        private set

    private var pendingContinuation: Continuation<Boolean>? = null

    @Suppress("ComplexCondition")
    suspend fun requestBluetoothPermissionIfNecessary() {
        // Check conditions by increasing complexity
        if (
            !AndroidVersion.isAtLeastS ||
            wasDialogShowThisSession ||
            activity.checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED ||
            appPreferences.ignoreBluetoothPermission ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        ) {
            return
        }

        wasDialogShowThisSession = true

        val shouldRequestPermission = suspendCancellableCoroutine { continuation ->
            pendingContinuation = continuation
            continuation.invokeOnCancellation {
                pendingContinuation = null
                requestVisible = false
            }
            requestVisible = true
        }

        if (!shouldRequestPermission) {
            appPreferences.ignoreBluetoothPermission = true
            return
        }

        activity.requestPermission(BLUETOOTH_CONNECT) { requestPermissionsResult ->
            if (requestPermissionsResult[BLUETOOTH_CONNECT] == PERMISSION_GRANTED) {
                activity.toast(R.string.bluetooth_permission_granted)
            }
        }
    }

    /**
     * Compose 对话框按钮回调。[shouldContinue] 为 true 时继续请求运行时权限，否则记住"忽略"。
     */
    fun onUserDecision(shouldContinue: Boolean) {
        requestVisible = false
        pendingContinuation?.resume(shouldContinue)
        pendingContinuation = null
    }
}
