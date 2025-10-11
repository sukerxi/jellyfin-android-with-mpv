package org.jellyfin.mobile

import android.app.Application
import android.os.StrictMode
import android.util.Log
import android.webkit.WebView
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import timber.log.Timber
import java.io.File
import java.io.IOException

@Suppress("unused")
class JellyfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val policy: StrictMode.ThreadPolicy  =  StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        // Setup logging
        Timber.plant(JellyTree())

        if (BuildConfig.DEBUG) {
            // Enable WebView debugging
            if (isWebViewSupported()) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
        copyMpvAssetFolder("mpv", filesDir)
        startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }
    }

    @Throws(IOException::class)
    private fun copyMpvAssetFolder(assetMpvDir: String, outputDir: File) {
        outputDir.mkdirs()
        val mpvFiles = assets.list(assetMpvDir) ?: return

        for (asset in mpvFiles) {
            val assetPath = "$assetMpvDir/$asset"
            val outFile = File(outputDir, asset)
            if (outFile.isDirectory) {
                copyMpvAssetFolder(assetPath, outFile)
            }else{
                if (outFile.exists()) {
                    continue
                }
                try {
                    // 尝试作为文件打开
                    assets.open(assetPath).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: IOException) {
                    Timber.tag("error").e(e)
                }
            }

        }
    }
}
