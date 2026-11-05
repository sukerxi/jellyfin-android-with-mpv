package org.jellyfin.mobile.player.deviceprofile

import android.media.MediaCodecList
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The playback device profile is POSTed on every playback request, while the player
 * ViewModel lives for the whole Activity. These tests pin down that a preference
 * change is visible to the builder *without* recreating it.
 */
class DeviceProfileBuilderTest {
    private var playerType: String = VideoPlayerType.EXO_PLAYER
    private var directPlayAss: Boolean = false
    private lateinit var builder: DeviceProfileBuilder

    @BeforeEach
    fun setUp() {
        // Codec detection is an Android framework call and irrelevant to subtitle profiles;
        // an empty codec list keeps the JVM unit test off the framework stubs.
        mockkConstructor(MediaCodecList::class)
        every { anyConstructed<MediaCodecList>().codecInfos } returns emptyArray()

        val preferences = mockk<AppPreferences>(relaxed = true)
        every { preferences.videoPlayerType } answers { playerType }
        every { preferences.resolveExoPlayerDirectPlayAss(false) } answers { directPlayAss }
        builder = DeviceProfileBuilder(preferences)
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(MediaCodecList::class)
    }

    private fun announcesEmbeddedAss() = builder.getDeviceProfile().subtitleProfiles.any {
        (it.format == "ass" || it.format == "ssa") && it.method == SubtitleDeliveryMethod.EMBED
    }

    @Test
    fun `ASS switch is picked up on the next profile without recreating the builder`() {
        assertFalse(announcesEmbeddedAss(), "ASS disabled: server must keep burning subtitles in")

        directPlayAss = true
        assertTrue(announcesEmbeddedAss(), "ASS enabled: profile must announce SSA/ASS support")

        directPlayAss = false
        assertFalse(announcesEmbeddedAss(), "ASS disabled again: profile must drop SSA/ASS support")
    }

    @Test
    fun `unchanged settings reuse the cached profile`() {
        val first = builder.getDeviceProfile()
        assertSame(first, builder.getDeviceProfile())

        directPlayAss = true
        val updated = builder.getDeviceProfile()
        assertNotSame(first, updated)
        assertSame(updated, builder.getDeviceProfile())
    }

    @Test
    fun `changing the player engine invalidates the cached profile`() {
        val exoProfile = builder.getDeviceProfile()
        assertFalse(exoProfile.subtitleProfiles.any { it.format == "ass" })

        playerType = VideoPlayerType.MPV_PLAYER
        val mpvProfile = builder.getDeviceProfile()
        assertNotSame(exoProfile, mpvProfile)
        assertTrue(mpvProfile.subtitleProfiles.any { it.format == "ass" })
    }
}
