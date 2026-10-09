package org.jellyfin.mobile.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * GitHub Releases API（匿名访问）。
 *
 * `name` 形如 `3.0.0-49 (3.0.0-c7a636a4)`，括号内为 versionName；
 * 解析失败时回退到 `tag_name`。APK 取 assets 中第一个 `.apk` 文件。
 */
class GitHubReleaseSource(
    private val okHttpClient: OkHttpClient,
) : ReleaseSource {
    companion object {
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private val VERSION_IN_NAME = Regex("""\(([^)]+)\)""")
    }

    override suspend fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub API returned ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty GitHub API response")
            val release = json.parseToJsonElement(body).jsonObject

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
                releaseUrl = release["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$REPO/releases/latest",
                downloadUrl = downloadUrl,
                fileName = fileName,
                changelog = release["body"]?.jsonPrimitive?.content.orEmpty(),
                channel = UpdateChannel.GITHUB,
            )
        }
    }
}

/**
 * CNB 发布页（REST API 需鉴权，故解析网页）。
 *
 * `/-/releases/latest` 会 307 重定向到 `/-/releases/tag/<tag>`，
 * OkHttp 自动跟随后从最终 URL 提取 tag；页面内嵌 JSON 中的
 * `jellyfin-android-v<versionName>-libre-release.apk` 文件名用于推导
 * versionName，下载地址按固定模板拼接。
 */
class CnbReleaseSource(
    private val okHttpClient: OkHttpClient,
) : ReleaseSource {
    companion object {
        private const val BASE_URL = "https://cnb.cool/$REPO"
        private const val LATEST_URL = "$BASE_URL/-/releases/latest"
        private val APK_NAME = Regex("""jellyfin-android-v([0-9A-Za-z.\-]+?)-libre-release\.apk""")
    }

    override suspend fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CNB returned ${response.code}")
            val html = response.body?.string() ?: throw IOException("Empty CNB response")

            // 从最终重定向地址中提取 tag
            val finalUrl = response.request.url.toString()
            val tag = finalUrl.substringAfterLast("/tag/", "").ifBlank {
                Regex("""/releases/tag/([^\s"'<>]+)""").find(html)?.groupValues?.get(1).orEmpty()
            }
            if (tag.isBlank()) return null

            val match = APK_NAME.find(html) ?: return null
            val versionName = match.groupValues[1]
            val fileName = match.value

            // 尝试从页面内嵌 JSON 中提取更新说明（description 字段）
            val changelog = Regex("\"description\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let { raw ->
                    raw.replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace(Regex("""<br\s*/?>"""), "\n")
                        .let { text -> Regex("""</?[^>]+>""").replace(text, "") }
                        .trim()
                }
                .orEmpty()

            return UpdateInfo(
                versionName = versionName,
                tag = tag,
                releaseUrl = "$BASE_URL/-/releases/tag/$tag",
                downloadUrl = "$BASE_URL/-/releases/download/$tag/$fileName",
                fileName = fileName,
                changelog = changelog,
                channel = UpdateChannel.CNB,
            )
        }
    }
}
