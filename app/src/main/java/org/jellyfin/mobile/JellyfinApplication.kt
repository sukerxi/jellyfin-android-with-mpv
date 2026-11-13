package org.jellyfin.mobile

import android.app.Application
import android.os.StrictMode
import android.webkit.WebView
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.mpv.MpvAssetInstaller
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application(), KoinComponent {
    /**
     * Singletons that are expensive to build and would otherwise be constructed
     * lazily on the main thread during the first playback setup: [AppPreferences]
     * reads its DataStore snapshot from disk, [DeviceProfileBuilder] scans the
     * device's media codecs. Both are resolved on the prewarm thread below, never
     * here.
     */
    private val appPreferences: AppPreferences by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()

    override fun onCreate() {
        super.onCreate()
        // WebView 初始化会在主线程摸磁盘、读配置，生产环境不能因此出问题，所以 release
        // 直接放行。但 debug 曾经也是 permitAll —— 等于把「主线程 I/O」这条最容易变成
        // 掉帧的线索一起静音了：以后谁写了主线程读盘，只会表现为偶发卡顿，且查不出来。
        // debug 改为「照常检测 + 只打日志」，不改变行为，但 Slog 里能看见。
        val policy: StrictMode.ThreadPolicy = StrictMode.ThreadPolicy.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    detectDiskReads()
                    detectDiskWrites()
                    detectCustomSlowCalls()
                    penaltyLog()
                } else {
                    permitAll()
                }
            }
            .build()
        StrictMode.setThreadPolicy(policy)

        // Setup logging
        Timber.plant(JellyTree())

        if (BuildConfig.DEBUG) {
            // Enable WebView debugging
            if (isWebViewSupported()) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
        // Copy mpv assets (subfont.ttf, ...) off the main thread; MpvCore awaits completion.
        MpvAssetInstaller.start(this)
        startKoin {
            androidContext(this@JellyfinApplication)

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }
        // Resolve the expensive singletons here, on a background thread, so their
        // construction cost (disk read, codec scan) is paid during app startup
        // instead of on the main thread while the user waits for playback to start.
        Thread {
            appPreferences
            deviceProfileBuilder
        }.apply {
            name = "app-prewarm"
            isDaemon = true
            start()
        }
    }
}
