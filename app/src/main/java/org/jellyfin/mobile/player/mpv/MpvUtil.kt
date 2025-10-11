package org.jellyfin.mobile.player.mpv

import androidx.annotation.CheckResult
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


object MpvUtil : KoinComponent{
    private val apiClient: ApiClient = get()
    private val videosApi: VideosApi = apiClient.videosApi


    fun convertJellyfinMediaSource(jellyfinMediaSource: JellyfinMediaSource)  : MediaItem {
        val mediaItem=createVideoMediaItem(jellyfinMediaSource).buildUpon()
            .setSubtitleConfigurations(createExternalSubtitleConfiguration(jellyfinMediaSource)).build()
        return mediaItem
    }


    /**
     * Builds the [MediaSource] for the main media stream (video/audio/embedded subs).
     *
     * @param source The [JellyfinMediaSource] object containing all necessary info about the item to be played.
     * @return A [MediaSource]. The type of MediaSource depends on the playback method/protocol.
     */
    @CheckResult
    private fun createVideoMediaItem(source: JellyfinMediaSource): MediaItem {
        val sourceInfo = source.sourceInfo
        val url = when (source.playMethod) {
            PlayMethod.DIRECT_PLAY -> {
                when (sourceInfo.protocol) {
                    MediaProtocol.FILE -> {
                        val url = videosApi.getVideoStreamUrl(
                            itemId = source.itemId,
                            static = true,
                            playSessionId = source.playSessionId,
                            mediaSourceId = source.id,
                            deviceId = apiClient.deviceInfo.id,
                        )
                        url
                    }
                    MediaProtocol.HTTP -> {
                        val url = requireNotNull(sourceInfo.path)
                        url
                    }
                    else -> throw IllegalArgumentException("Unsupported protocol ${sourceInfo.protocol}")
                }
            }
            PlayMethod.DIRECT_STREAM -> {
                val container = requireNotNull(sourceInfo.container) { "Missing direct stream container" }
                val url = videosApi.getVideoStreamByContainerUrl(
                    itemId = source.itemId,
                    container = container,
                    playSessionId = source.playSessionId,
                    mediaSourceId = source.id,
                    deviceId = apiClient.deviceInfo.id,
                )
                url
            }
            PlayMethod.TRANSCODE -> {
                val transcodingPath = requireNotNull(sourceInfo.transcodingUrl) { "Missing transcode URL" }
                val protocol = sourceInfo.transcodingSubProtocol
                require(protocol == MediaStreamProtocol.HLS) { "Unsupported transcode protocol '$protocol'" }
                val transcodingUrl = apiClient.createUrl(transcodingPath)
                transcodingUrl
            }
        }

        return MediaItem.Builder()
            .setMediaId(source.itemId.toString())
            .setUri(url)
            .build()

    }


    @CheckResult
    private fun createExternalSubtitleConfiguration(
        source: JellyfinMediaSource,
    ): List<MediaItem.SubtitleConfiguration> {
        return source.externalSubtitleStreams.map { stream ->
            val uri = apiClient.createUrl(stream.deliveryUrl).toUri()
            val mediaItem = MediaItem.SubtitleConfiguration.Builder(uri).apply {
                setId("${ExternalSubtitleStream.ID_PREFIX}${stream.index}")
                setLabel(stream.displayTitle)
                setMimeType(stream.mimeType)
                setLanguage(stream.language)
            }.build()
            mediaItem
        }.toMutableList()
    }



}
