package org.jellyfin.mobile.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.BuildConfig
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
 * - `name` 形如 `3.0.0-49 (3.0.0-c7a636a4)`，括号内为 versionName，解析失败回退 `tag_name`
 *   （正式发版的 tag 可能带 `v` 前缀，需去掉后与 BuildConfig.VERSION_NAME 比较）；
 * - APK 优先取与当前构建 flavor 匹配的资产，避免正式发版同时附带 libre / proprietary 时选错；
 * - `body` 为更新说明（markdown 文本）；
 * - 校验值优先用 GitHub 的 `digest`，回退 CNB 的 `hash_algo`/`hash_value`。
 *
 * @param preferProprietary 当前构建是否为 proprietary flavor，用于在多 APK 发布中挑选资产
 * @param normalizeBody 渠道特有的正文预处理（如 CNB 的 `<br>` 换行）
 */
internal fun parseReleaseJson(
    payload: String,
    channel: String,
    fallbackReleaseUrl: String,
    preferProprietary: Boolean = BuildConfig.IS_PROPRIETARY,
    normalizeBody: (String) -> String = { it },
): UpdateInfo? {
    val release = json.parseToJsonElement(payload).jsonObject

    // 使用 contentOrNull：JSON 中显式为 null 的字段不会退化成字符串 "null"
    val name = release["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val versionName = (VERSION_IN_NAME.find(name)?.groupValues?.get(1) ?: tag)
        .removePrefix("v")
        .trim()
    if (versionName.isBlank()) return null

    val flavor = if (preferProprietary) "proprietary" else "libre"
    val apks = release["assets"]?.jsonArray
        ?.map { it.jsonObject }
        ?.filter { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".apk", ignoreCase = true) }
        .orEmpty()
    val apk = apks.firstOrNull {
        (it["name"]?.jsonPrimitive?.contentOrNull ?: "").contains(flavor, ignoreCase = true)
    } ?: apks.firstOrNull() ?: return null

    val downloadUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
    val fileName = apk["name"]?.jsonPrimitive?.contentOrNull ?: "update.apk"
    val digest = apk["digest"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank {
        // CNB 的资产信息用 hash_algo/hash_value 描述校验值
        val algo = apk["hash_algo"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val value = apk["hash_value"]?.jsonPrimitive?.contentOrNull
        if (algo == "sha256" && !value.isNullOrBlank()) "sha256:$value" else ""
    }

    return UpdateInfo(
        versionName = versionName,
        tag = tag,
        releaseUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: fallbackReleaseUrl,
        downloadUrl = downloadUrl,
        fileName = fileName,
        changelog = normalizeBody(release["body"]?.jsonPrimitive?.contentOrNull.orEmpty()),
        channel = channel,
        digest = digest,
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
