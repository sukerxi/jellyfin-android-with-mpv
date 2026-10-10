package org.jellyfin.mobile.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 发布来源抽象：不同渠道（GitHub / CNB）以各自的方式获取最新 release 信息。
 */
interface ReleaseSource {
    /**
     * 获取该渠道的最新发布；若无法解析出有效版本信息则返回 null。
     * 网络等异常直接向上传递，由调用方处理。
     */
    suspend fun fetchLatest(): UpdateInfo?
}

private val json = Json { ignoreUnknownKeys = true }

private const val REPO = "sukerxi/jellyfin-android-with-mpv"

private val VERSION_IN_NAME = Regex("""\(([^)]+)\)""")

/**
 * GitHub 与 CNB 的 release JSON 结构一致，统一解析：
 * - `name` 形如 `3.0.0-49 (3.0.0-c7a636a4)`，括号内为 versionName，解析失败回退 `tag_name`；
 * - APK 取 assets 中第一个 `.apk` 文件；
 * - `body` 为更新说明（markdown 文本）。
 *
 * @param normalizeBody 渠道特有的正文预处理（如 CNB 的 `<br>` 换行）
 */
private fun parseReleaseJson(
    payload: String,
    channel: String,
    fallbackReleaseUrl: String,
    normalizeBody: (String) -> String = { it },
): UpdateInfo? {
    val release = json.parseToJsonElement(payload).jsonObject

    val name = release["name"]?.jsonPrimitive?.content.orEmpty()
    val tag = release["tag_name"]?.jsonPrimitive?.content.orEmpty()
    val versionName = VERSION_IN_NAME.find(name)?.groupValues?.get(1) ?: tag
    if (versionName.isBlank()) return null

    val apk = release["assets"]?.jsonArray?.firstOrNull { asset ->
        (asset.jsonObject["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk")
    }?.jsonObject ?: return null

    val downloadUrl = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null
    val fileName = apk["name"]?.jsonPrimitive?.content ?: "update.apk"

    return UpdateInfo(
        versionName = versionName,
        tag = tag,
        releaseUrl = release["html_url"]?.jsonPrimitive?.content ?: fallbackReleaseUrl,
        downloadUrl = downloadUrl,
        fileName = fileName,
        changelog = normalizeBody(release["body"]?.jsonPrimitive?.content.orEmpty()),
        channel = channel,
    )
}

/**
 * GitHub Releases API（匿名访问）。
 */
class GitHubReleaseSource(
    private val okHttpClient: OkHttpClient,
) : ReleaseSource {
    companion object {
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    }

    override suspend fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub API returned ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty GitHub API response")
            return parseReleaseJson(
                payload = body,
                channel = UpdateChannel.GITHUB,
                fallbackReleaseUrl = "https://github.com/$REPO/releases/latest",
            )
        }
    }
}

/**
 * CNB 发布接口（匿名 JSON，返回结构与 GitHub Releases 完全一致）。
 *
 * 同一个 `/-/releases/latest` 地址按 Accept 头区分响应：
 * 浏览器默认返回 HTML 页面；带上 `Accept: application/json` 则直接返回 JSON。
 *
 * 唯一差异：CNB 的 body 用 `<br>` 而非换行，解析时统一转成换行符，
 * 使后续 markdown 处理与 GitHub 渠道一致。
 */
class CnbReleaseSource(
    private val okHttpClient: OkHttpClient,
) : ReleaseSource {
    companion object {
        private const val LATEST_URL = "https://cnb.cool/$REPO/-/releases/latest"
        private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    }

    override suspend fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CNB returned ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty CNB response")
            return parseReleaseJson(
                payload = body,
                channel = UpdateChannel.CNB,
                fallbackReleaseUrl = LATEST_URL,
                normalizeBody = { BR_TAG.replace(it, "\n") },
            )
        }
    }
}
