package org.jellyfin.mobile.player.source

import android.content.Context
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.deviceprofile.CodecHelpers
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 选中轨的可观察快照。切轨后由 [JellyfinMediaSource] 统一发布，Compose 直接订阅，
 * 不再需要「把当前选中轨塞进 remember 的 key」这种靠注释维持的补丁写法。
 */
data class TrackSelectionSnapshot(
    val videoStream: MediaStream? = null,
    val audioStream: MediaStream? = null,
    val subtitleStream: MediaStream? = null,
)

sealed class JellyfinMediaSource(
    val itemId: UUID,
    val item: BaseItemDto?,
    val sourceInfo: MediaSourceInfo,
    val playSessionId: String,
    playbackDetails: PlaybackDetails?,
) {
    val id: String = requireNotNull(sourceInfo.id) { "Media source has no id" }

    abstract val playMethod: PlayMethod

    var startTime: Duration = playbackDetails?.startTime ?: Duration.ZERO
    val runTime: Duration = sourceInfo.runTimeTicks?.ticks ?: Duration.ZERO

    val mediaStreams: List<MediaStream> = sourceInfo.mediaStreams.orEmpty()
    val audioStreams: List<MediaStream>
    val subtitleStreams: List<MediaStream>
    val externalSubtitleStreams: List<ExternalSubtitleStream>

    private val _trackSelection = MutableStateFlow(TrackSelectionSnapshot())
    val trackSelection: StateFlow<TrackSelectionSnapshot> = _trackSelection.asStateFlow()

    val selectedVideoStream: MediaStream?
        get() = _trackSelection.value.videoStream
    val selectedAudioStream: MediaStream?
        get() = _trackSelection.value.audioStream
    val selectedSubtitleStream: MediaStream?
        get() = _trackSelection.value.subtitleStream

    val selectedAudioStreamIndex: Int?
        get() = selectedAudioStream?.index
    val selectedSubtitleStreamIndex: Int
        // -1 disables subtitles, null would select the default subtitle
        // If the default should be played, it would be explicitly set above
        get() = selectedSubtitleStream?.index ?: -1

    init {
        // Classify MediaStreams
        val audio = ArrayList<MediaStream>()
        val subtitles = ArrayList<MediaStream>()
        val externalSubtitles = ArrayList<ExternalSubtitleStream>()
        var initialVideoStream: MediaStream? = null
        var initialAudioStream: MediaStream? = null
        var initialSubtitleStream: MediaStream? = null
        for (mediaStream in mediaStreams) {
            when (mediaStream.type) {
                MediaStreamType.VIDEO -> {
                    // Always select the first available video stream
                    if (initialVideoStream == null) {
                        initialVideoStream = mediaStream
                    }
                }
                MediaStreamType.AUDIO -> {
                    audio += mediaStream
                    if (mediaStream.index == (playbackDetails?.audioStreamIndex ?: sourceInfo.defaultAudioStreamIndex)) {
                        initialAudioStream = mediaStream
                    }
                }
                MediaStreamType.SUBTITLE -> {
                    subtitles += mediaStream
                    if (mediaStream.index == (playbackDetails?.subtitleStreamIndex ?: sourceInfo.defaultSubtitleStreamIndex)) {
                        initialSubtitleStream = mediaStream
                    }

                    // External subtitles as specified by the deliveryMethod.
                    // It is set to external either for external subtitle files or when transcoding.
                    // In the latter case, subtitles are extracted from the source file by the server.
                    if (mediaStream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL) {
                        val deliveryUrl = mediaStream.deliveryUrl
                        val mimeType = CodecHelpers.getSubtitleMimeType(mediaStream.codec)
                        if (deliveryUrl != null && mimeType != null) {
                            externalSubtitles += ExternalSubtitleStream(
                                index = mediaStream.index,
                                deliveryUrl = deliveryUrl,
                                mimeType = mimeType,
                                displayTitle = mediaStream.displayTitle.orEmpty(),
                                language = mediaStream.language ?: Constants.LANGUAGE_UNDEFINED,
                            )
                        }
                    }
                }
                MediaStreamType.EMBEDDED_IMAGE,
                MediaStreamType.DATA,
                MediaStreamType.LYRIC,
                -> Unit // ignore
            }
        }

        audioStreams = audio
        subtitleStreams = subtitles
        externalSubtitleStreams = externalSubtitles
        _trackSelection.value = TrackSelectionSnapshot(
            videoStream = initialVideoStream,
            audioStream = initialAudioStream,
            subtitleStream = initialSubtitleStream,
        )
    }

    /**
     * Select the specified [audio stream][stream] in the source.
     *
     * @param stream The stream to select.
     * @return true if the stream was found and selected, false otherwise.
     */
    fun selectAudioStream(stream: MediaStream): Boolean {
        require(stream.type == MediaStreamType.AUDIO)
        if (mediaStreams[stream.index] !== stream) {
            return false
        }

        _trackSelection.update { it.copy(audioStream = stream) }
        return true
    }

    /**
     * Select the specified [subtitle stream][stream] in the source.
     *
     * @param stream The stream to select, or null to disable subtitles.
     * @return true if the stream was found and selected, false otherwise.
     */
    fun selectSubtitleStream(stream: MediaStream?): Boolean {
        if (stream == null) {
            _trackSelection.update { it.copy(subtitleStream = null) }
            return true
        }

        require(stream.type == MediaStreamType.SUBTITLE)
        if (mediaStreams[stream.index] !== stream) {
            return false
        }

        _trackSelection.update { it.copy(subtitleStream = stream) }
        return true
    }

    /**
     * Returns the index of the media stream within the embedded streams.
     * Useful for handling track selection in ExoPlayer, where embedded streams are mapped first.
     */
    fun getEmbeddedStreamIndex(mediaStream: MediaStream): Int {
        var index = 0
        for (stream in mediaStreams) {
            when {
                stream === mediaStream -> return index
                !stream.isExternal -> index++
            }
        }
        throw IllegalArgumentException("Invalid media stream")
    }

    /**
     * Returns the index of the media stream within the embedded (non-external) streams of the same type.
     *
     * Jellyfin inserts external subtitle streams at the beginning of [mediaStreams] and re-indexes all
     * streams, so [MediaStream.index] is neither the container stream index nor contiguous per type.
     * mpv exposes internal tracks per type (audio / subtitle) in container order, which matches the
     * order of the non-external Jellyfin streams, making this ordinal safe to map to an mpv track.
     */
    fun getEmbeddedStreamIndexByType(mediaStream: MediaStream): Int {
        var index = 0
        for (stream in mediaStreams) {
            when {
                stream === mediaStream -> return index
                !stream.isExternal && stream.type == mediaStream.type -> index++
            }
        }
        throw IllegalArgumentException("Invalid media stream")
    }

    /**
     * Get the formatted name of the source.
     */
    @Suppress("CyclomaticComplexMethod")
    fun getName(context: Context): String {
        return item?.let {
            buildString {
                val name = if (
                    it.type in arrayOf(BaseItemKind.PROGRAM, BaseItemKind.RECORDING) &&
                    (it.isSeries == true || !it.episodeTitle.isNullOrEmpty())
                ) {
                    it.episodeTitle
                } else {
                    it.name
                }

                val extraInfo = when (it.type) {
                    BaseItemKind.TV_CHANNEL if !it.channelNumber.isNullOrEmpty() -> it.channelNumber
                    BaseItemKind.EPISODE if it.parentIndexNumber == 0 -> context.getString(R.string.special_episode)
                    in arrayOf(BaseItemKind.EPISODE, BaseItemKind.RECORDING) if it.indexNumber != null && it.parentIndexNumber != null ->
                        "S${it.parentIndexNumber}:E${it.indexNumber}${it.indexNumberEnd?.let { n -> "-$n" } ?: ""}"
                    else -> ""
                }

                listOf(it.seriesName, extraInfo, name)
                    .filter { str -> !str.isNullOrEmpty() }
                    .joinTo(this, separator = " - ")

                if (it.type == BaseItemKind.MOVIE && it.productionYear != null) {
                    append(" (${it.productionYear})")
                } else if (it.premiereDate != null) {
                    append(" (${it.premiereDate!!.year})")
                }
            }.ifEmpty { null }
        } ?: sourceInfo.name.orEmpty()
    }
}

data class PlaybackDetails(
    val startTime: Duration?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)
