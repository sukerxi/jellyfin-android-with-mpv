package org.jellyfin.mobile.webapp

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.initLocale
import org.jellyfin.mobile.utils.inject
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.Reader
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 网页宿主加载主框架失败的原因。
 *
 * 区分「可重试」与「不可恢复」是本类的核心职责：原实现把断网、5xx、证书错误
 * 一律当成致命错误，直接 `resetServer()` 销毁 WebView 退回选服页——地铁里掉一次网
 * 就等于丢掉整个浏览现场，而提示只有一句「无法建立连接」。
 */
data class WebLoadFailure(
    val kind: Kind,
    val httpStatus: Int? = null,
    val description: String? = null,
) {
    enum class Kind {
        /** 断网、DNS 失败、连接被拒等：原地重试即可恢复。 */
        NETWORK,

        /**
         * 主框架已连上，但网页界面（bundle / 脚本分片）没有加载完成。
         * 网络不稳定时最常见，重试即可恢复。
         */
        WEBAPP,

        /** 服务器返回 4xx/5xx：重启或升级中的服务器重试往往就好了。 */
        HTTP,

        /** 证书/SSL 错误：用户改不了，只能换服务器或修服务端配置。 */
        SSL,

        /** 首屏超时。 */
        TIMEOUT,
    }

    /**
     * 值得原地重试的失败。证书错误重试也不会变好，直接给「换个服务器」；
     * 4xx 里 401/403 属于配置问题（认证代理），同样不重试。
     */
    val retryable: Boolean
        get() = when (kind) {
            Kind.NETWORK, Kind.TIMEOUT, Kind.WEBAPP -> true
            Kind.HTTP -> httpStatus == null || httpStatus in 500..599
            Kind.SSL -> false
        }
}

abstract class JellyfinWebViewClient(
    private val coroutineScope: CoroutineScope,
    private val server: ServerEntity,
    private val assetsPathHandler: AssetsPathHandler,
    private val mainViewModel: MainViewModel,
) : WebViewClientCompat() {

    abstract fun onConnectedToWebapp()

    /** 首屏已渲染出实质内容（注入脚本探测）。 */
    abstract fun onWebappRendered()

    /**
     * 首屏是否已渲染。渲染前的脚本加载失败都会留下黑屏（登录页出不来），
     * 按致命错误整页替换；渲染后的失败属于 webapp 运行时问题，保留当前会话。
     */
    abstract fun isWebappRendered(): Boolean

    abstract fun onLoadFailed(failure: WebLoadFailure)

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path?.lowercase(Locale.ROOT) ?: return null
        return when {
            path.matches(Constants.MAIN_BUNDLE_PATH_REGEX) && "deferred" !in url.query.orEmpty() -> {
                onConnectedToWebapp()
                assetsPathHandler.inject("native/injectionScript.js")
            }
            // Load injected scripts from application assets
            path.contains("/native/") -> assetsPathHandler.inject("native/${url.lastPathSegment}")
            // Load the chrome.cast.js library instead
            path.endsWith(Constants.CAST_SDK_PATH) -> assetsPathHandler.inject("native/chrome.cast.js")
            path.endsWith(Constants.SESSION_CAPABILITIES_PATH) -> {
                coroutineScope.launch {
                    val credentials = suspendCoroutine { continuation ->
                        webView.evaluateJavascript(
                            "JSON.parse(window.localStorage.getItem('jellyfin_credentials'))",
                        ) { result ->
                            try {
                                continuation.resume(Json.parseToJsonElement(result).jsonObject)
                            } catch (e: Exception) {
                                val message = "Failed to extract credentials"
                                Timber.e(e, message)
                                continuation.resumeWithException(Exception(message, e))
                            }
                        }
                    }

                    val storedServer = credentials["Servers"]?.jsonArray?.get(0)?.jsonObject
                    val user = storedServer?.get("UserId")?.jsonPrimitive?.contentOrNull?.toUUIDOrNull()
                    val token = storedServer?.get("AccessToken")?.jsonPrimitive?.contentOrNull

                    if (user == null || token == null) {
                        Timber.e("Unable to find user in webview credentials")
                        onLoadFailed(WebLoadFailure(WebLoadFailure.Kind.NETWORK, description = "missing credentials"))
                        return@launch
                    }

                    mainViewModel.setupUser(server.id, user, token)
                    webView.initLocale(user)
                }
                null
            }
            else -> null
        }
    }

    /**
     * 是否属于 webapp 自身的脚本资源（主 bundle 或代码分片）。
     * `/native/` 下的资源由本地 assets 提供、不走网络，排除在外。
     */
    private fun WebResourceRequest.isWebappScript(): Boolean {
        val path = url.path?.lowercase(Locale.ROOT) ?: return false
        if (path.contains("/native/")) return false
        return Constants.MAIN_BUNDLE_PATH_REGEX.matches(path) || path.endsWith(".js")
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        val errorMessage = errorResponse.data?.run { bufferedReader().use(Reader::readText) }
        Timber.e("Received WebView HTTP %d error: %s", errorResponse.statusCode, errorMessage)

        if (request.isForMainFrame) {
            onLoadFailed(
                WebLoadFailure(
                    kind = WebLoadFailure.Kind.HTTP,
                    httpStatus = errorResponse.statusCode,
                ),
            )
            return
        }

        // 首屏渲染不出来之前，任何脚本（bundle / 代码分片）的 HTTP 错误都会让 webapp
        // 停在黑屏上，必须让用户看到原因与重试入口。
        if (!isWebappRendered() && request.isWebappScript()) {
            onLoadFailed(WebLoadFailure(kind = WebLoadFailure.Kind.WEBAPP, httpStatus = errorResponse.statusCode))
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        val description = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) -> error.description
            else -> null
        }
        val errorCode = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE) -> error.errorCode
            else -> ERROR_UNKNOWN
        }
        Timber.e("Received WebView error %d at %s: %s", errorCode, request.url.toString(), description)

        // 主框架加载失败：只有 4xx/5xx 由 onReceivedHttpError 报告，这里一律按网络类处理。
        if (request.isForMainFrame) {
            onLoadFailed(
                WebLoadFailure(
                    kind = WebLoadFailure.Kind.NETWORK,
                    description = description?.toString(),
                ),
            )
            return
        }

        // 首屏渲染之前的脚本失败同样致命：bundle 被拦截后还要走网络下载，网络不稳时
        // 失败或卡住就是「能连上服务器但一直黑屏」，不能只记日志。
        if (!isWebappRendered() && request.isWebappScript()) {
            onLoadFailed(WebLoadFailure(kind = WebLoadFailure.Kind.WEBAPP, description = description?.toString()))
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.e("Received SSL error: %s", error.toString())
        // 不再静默 cancel：证书问题用户看不出发生了什么，必须显式告知。
        handler.cancel()
        onLoadFailed(
            WebLoadFailure(
                kind = WebLoadFailure.Kind.SSL,
                description = error.primaryError.toString(),
            ),
        )
    }
}
