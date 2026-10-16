package org.jellyfin.mobile.player.mpv.bench

import androidx.media3.common.Player
import org.jellyfin.mobile.player.mpv.MpvPlayer
import org.jellyfin.mobile.player.mpv.MpvUtil
import org.jellyfin.mobile.player.source.JellyfinMediaSource

/**
 * Captures the scene for a configuration comparison from the item that is currently playing.
 *
 * Reading the scene off the live player is what makes the in-player variant feel like "compare the
 * frame I am looking at": the URL, the position and the viewport all come from the session the user
 * is already in, and nothing has to be picked or downloaded again.
 *
 * The capture happens *while playback is still running*: once the benchmark swaps the configuration
 * and decodes its own frames, the session's position keeps moving, so the frozen copy is the only
 * trustworthy reference.
 */
object PlaybackSceneCapture {

    /**
     * Builds the [SceneSpec] for [mediaSource] playing on [player].
     *
     * @return null when the session cannot be frozen (no mpv player, no media source, an unknown
     * viewport), which the caller reports instead of starting a comparison nothing can be rendered
     * for.
     */
    fun capture(
        player: Player?,
        mediaSource: JellyfinMediaSource?,
        viewportWidth: Int,
        viewportHeight: Int,
    ): SceneSpec? {
        val mpvPlayer = player as? MpvPlayer ?: return null
        val source = mediaSource ?: return null
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val url = mediaUrl(source) ?: return null
        return SceneSpec(
            sourceUrl = url,
            positionMs = mpvPlayer.currentPosition.coerceAtLeast(0),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    /**
     * The URL mpv is playing.
     *
     * [MpvUtil] is the same conversion the player itself uses, so the benchmark decodes exactly the
     * bytes the user is watching rather than a re-resolved stream that could differ in bitrate or
     * container.
     */
    private fun mediaUrl(source: JellyfinMediaSource): String? =
        MpvUtil.convertJellyfinMediaSource(source).localConfiguration?.uri?.toString()
}
