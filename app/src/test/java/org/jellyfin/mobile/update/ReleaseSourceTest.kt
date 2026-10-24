package org.jellyfin.mobile.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseSourceTest {

    @Test
    fun `github release parses version from name and digest from asset`() {
        val info = parseReleaseJson(
            payload = releaseJson(
                name = "3.0.0-57 (3.0.0-a28b22df)",
                tag = "3.0.0-57",
                assets = """
                    {"name":"version.txt","browser_download_url":"https://example.com/version.txt"},
                    {"name":"app-libre-release.apk","browser_download_url":"https://example.com/app.apk","digest":"sha256:abc123"}
                """.trimIndent(),
            ),
            channel = UpdateChannel.GITHUB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = false,
        )

        requireNotNull(info)
        assertEquals("3.0.0-a28b22df", info.versionName)
        assertEquals("https://example.com/app.apk", info.downloadUrl)
        assertEquals("sha256:abc123", info.digest)
    }

    @Test
    fun `cnb release converts hash algo and value to digest`() {
        val info = parseReleaseJson(
            payload = releaseJson(
                name = "3.0.0-cnb-1 (3.0.0-30b962ec)",
                tag = "3.0.0-cnb-1",
                assets = """
                    {"name":"app-libre-release.apk","browser_download_url":"https://example.com/app.apk","hash_algo":"sha256","hash_value":"5f70c0"}
                """.trimIndent(),
            ),
            channel = UpdateChannel.CNB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = false,
        )

        requireNotNull(info)
        assertEquals("3.0.0-30b962ec", info.versionName)
        assertEquals("sha256:5f70c0", info.digest)
    }

    @Test
    fun `tag fallback strips v prefix`() {
        val info = parseReleaseJson(
            payload = releaseJson(
                name = "",
                tag = "v3.0.0",
                assets = """{"name":"app-libre-release.apk","browser_download_url":"https://example.com/app.apk"}""",
            ),
            channel = UpdateChannel.CNB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = false,
        )

        requireNotNull(info)
        assertEquals("3.0.0", info.versionName)
    }

    @Test
    fun `apk matching build flavor is preferred`() {
        val payload = releaseJson(
            name = "3.0.0-1 (3.0.0-abc)",
            tag = "3.0.0-1",
            assets = """
                {"name":"app-proprietary-release.apk","browser_download_url":"https://example.com/proprietary.apk"},
                {"name":"app-libre-release.apk","browser_download_url":"https://example.com/libre.apk"}
            """.trimIndent(),
        )

        val libre = parseReleaseJson(
            payload = payload,
            channel = UpdateChannel.CNB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = false,
        )
        val proprietary = parseReleaseJson(
            payload = payload,
            channel = UpdateChannel.CNB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = true,
        )

        assertEquals("app-libre-release.apk", requireNotNull(libre).fileName)
        assertEquals("app-proprietary-release.apk", requireNotNull(proprietary).fileName)
    }

    @Test
    fun `release without apk asset returns null`() {
        val info = parseReleaseJson(
            payload = releaseJson(
                name = "3.0.0-1 (3.0.0-abc)",
                tag = "3.0.0-1",
                assets = """{"name":"version.txt","browser_download_url":"https://example.com/version.txt"}""",
            ),
            channel = UpdateChannel.GITHUB,
            fallbackReleaseUrl = "https://example.com/latest",
            preferProprietary = false,
        )

        assertNull(info)
    }

    private fun releaseJson(name: String, tag: String, assets: String): String =
        """
        {
          "name": "$name",
          "tag_name": "$tag",
          "html_url": "https://example.com/release/1",
          "body": "notes",
          "assets": [$assets]
        }
        """.trimIndent()
}
