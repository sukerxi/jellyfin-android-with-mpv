package org.jellyfin.mobile.player.source

import android.content.Context
import androidx.annotation.StringRes
import org.jellyfin.mobile.R
import org.jellyfin.sdk.model.api.TranscodeReason

/**
 * Maps [TranscodeReason]s to user facing text.
 *
 * The playback info panel is the only place a user can find out *why* the server transcodes, and
 * the enum serial names ("VideoCodecNotSupported") are addressed at developers. Every member has
 * its own resource so translations follow the usual Android tooling; a reason this client does not
 * know yet keeps its serial name instead of disappearing from the panel.
 *
 * Exposes resource ids rather than text so both the View and the Compose implementation can
 * resolve them their own way.
 */
object TranscodeReasonFormatter {
    /**
     * Returns the string resource for [reason], or null when this client predates it. Callers fall
     * back to [TranscodeReason.serialName] in that case.
     */
    @StringRes
    fun labelResOrNull(reason: TranscodeReason): Int? = when (reason) {
        TranscodeReason.CONTAINER_NOT_SUPPORTED -> R.string.transcode_reason_container
        TranscodeReason.VIDEO_CODEC_NOT_SUPPORTED -> R.string.transcode_reason_video_codec
        TranscodeReason.AUDIO_CODEC_NOT_SUPPORTED -> R.string.transcode_reason_audio_codec
        TranscodeReason.SUBTITLE_CODEC_NOT_SUPPORTED -> R.string.transcode_reason_subtitle_codec
        TranscodeReason.AUDIO_IS_EXTERNAL -> R.string.transcode_reason_audio_external
        TranscodeReason.SECONDARY_AUDIO_NOT_SUPPORTED -> R.string.transcode_reason_secondary_audio
        TranscodeReason.VIDEO_PROFILE_NOT_SUPPORTED -> R.string.transcode_reason_video_profile
        TranscodeReason.VIDEO_LEVEL_NOT_SUPPORTED -> R.string.transcode_reason_video_level
        TranscodeReason.VIDEO_RESOLUTION_NOT_SUPPORTED -> R.string.transcode_reason_video_resolution
        TranscodeReason.VIDEO_BIT_DEPTH_NOT_SUPPORTED -> R.string.transcode_reason_video_bit_depth
        TranscodeReason.VIDEO_FRAMERATE_NOT_SUPPORTED -> R.string.transcode_reason_video_framerate
        TranscodeReason.REF_FRAMES_NOT_SUPPORTED -> R.string.transcode_reason_ref_frames
        TranscodeReason.ANAMORPHIC_VIDEO_NOT_SUPPORTED -> R.string.transcode_reason_anamorphic
        TranscodeReason.INTERLACED_VIDEO_NOT_SUPPORTED -> R.string.transcode_reason_interlaced
        TranscodeReason.AUDIO_CHANNELS_NOT_SUPPORTED -> R.string.transcode_reason_audio_channels
        TranscodeReason.AUDIO_PROFILE_NOT_SUPPORTED -> R.string.transcode_reason_audio_profile
        TranscodeReason.AUDIO_SAMPLE_RATE_NOT_SUPPORTED -> R.string.transcode_reason_audio_sample_rate
        TranscodeReason.AUDIO_BIT_DEPTH_NOT_SUPPORTED -> R.string.transcode_reason_audio_bit_depth
        TranscodeReason.CONTAINER_BITRATE_EXCEEDS_LIMIT -> R.string.transcode_reason_bitrate_exceeds_limit
        TranscodeReason.VIDEO_BITRATE_NOT_SUPPORTED -> R.string.transcode_reason_video_bitrate
        TranscodeReason.AUDIO_BITRATE_NOT_SUPPORTED -> R.string.transcode_reason_audio_bitrate
        TranscodeReason.UNKNOWN_VIDEO_STREAM_INFO -> R.string.transcode_reason_unknown_video
        TranscodeReason.UNKNOWN_AUDIO_STREAM_INFO -> R.string.transcode_reason_unknown_audio
        TranscodeReason.DIRECT_PLAY_ERROR -> R.string.transcode_reason_direct_play_error
        TranscodeReason.VIDEO_RANGE_TYPE_NOT_SUPPORTED -> R.string.transcode_reason_video_range
        TranscodeReason.VIDEO_CODEC_TAG_NOT_SUPPORTED -> R.string.transcode_reason_video_codec_tag
        TranscodeReason.STREAM_COUNT_EXCEEDS_LIMIT -> R.string.transcode_reason_stream_count
        else -> null
    }

    /**
     * Resolves [reason] to display text, falling back to the serial name for reasons this client
     * does not know yet.
     */
    fun format(context: Context, reason: TranscodeReason): String =
        labelResOrNull(reason)?.let(context::getString) ?: reason.serialName
}
