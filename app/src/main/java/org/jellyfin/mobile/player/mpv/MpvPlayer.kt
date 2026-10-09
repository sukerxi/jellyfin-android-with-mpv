package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.utils.Constants
import java.util.Locale
import java.util.UUID

/**
 * Media3  [SimpleBasePlayer]  backed by native mpv via [MpvCore].
 *
 * @author dr
 */
class MpvPlayer(
    application: Application,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    /**
     * Pending/current subtitle selection, restored on every newly loaded file.
     */
    private sealed interface SubtitleSelection {
        /** No explicit selection yet, let mpv pick its default track. */
        data object Auto : SubtitleSelection

        /** User explicitly disabled subtitles. */
        data object Disabled : SubtitleSelection

        /**
         * Embedded subtitle, identified by its ordinal among the non-external mpv subtitle tracks.
         * Jellyfin re-indexes its streams once external subtitle files are present, so the Jellyfin
         * stream index cannot be matched against mpv's ff-index; both sides do, however, keep the
         * same container order for their internal tracks.
         */
        data class Embedded(val trackIndex: Int) : SubtitleSelection

        /** External subtitle, matched against mpv's external-filename. */
        data class External(val filename: String?) : SubtitleSelection

        /** Subtitles are burned into the video by the server. */
        data object Encoded : SubtitleSelection
    }

    private var playbackState: Int = STATE_IDLE
    private var durationMs: Long = 0

    /** Stable identity of the currently loaded item, kept constant across state refreshes. */
    private var mediaItemUid: Any = UUID.randomUUID()
    private var currentMediaItem: MediaItem? = null

    private var tracks: List<MpvCore.MediaTrack> = emptyList()
    private var selectedAudioTrackIndex: Int? = null
    private var subtitleSelection: SubtitleSelection = SubtitleSelection.Auto

    private var firstFrameRendered = false
    private var pendingFirstFrame = false

    /**
     * Target position of the latest explicit seek, kept until playback actually restarts at
     * that position. While a seek is in flight mpv keeps reporting the pre-seek [time-pos]
     * (network seeks can take well over a second), whereas ExoPlayer exposes the seek target
     * immediately. The pending target is therefore reported from [currentPositionMs] during
     * the seek, matching ExoPlayer semantics — required e.g. by the upstream "press previous
     * twice to switch episodes" logic (see [org.jellyfin.mobile.player.PlayerViewModel]).
     */
    private var pendingSeekTargetMs: Long? = null

    /**
     * True while a new file is being loaded. The END_FILE of the previously playing file
     * that is emitted by mpv during replacement must not be treated as playback end.
     */
    private var loadingNewFile = false

    /**
     * Whether the current item ever reached FILE_LOADED. An END_FILE without a preceding
     * FILE_LOADED means mpv failed to open/decode the stream (e.g. a direct-play source the
     * codec-profile relaxation now allows but mpv itself cannot handle). In that case a
     * [pendingError] is reported so the ViewModel can fall back to direct stream / transcoding.
     */
    private var fileLoadedForCurrentItem = false

    /**
     * Error surfaced through [getState] to trigger the playback fallback in PlayerViewModel.
     * Reset whenever a new item starts loading.
     */
    private var pendingError: PlaybackException? = null

    /**
     * Set while an explicit stop was requested (handleStop / handleRelease), so the resulting
     * END_FILE is not misinterpreted as a failed load.
     */
    private var stopRequested = false

    private var decoderProvider: () -> DecoderType = { DecoderType.HARDWARE }
    private var decoderListener: (DecoderType) -> Unit = {}

    private val eventListener: (MpvEvent) -> Unit = { event ->
        when (event) {
            MpvEvent.StartFile -> {
                loadingNewFile = false
                fileLoadedForCurrentItem = false
                stopRequested = false
                durationMs = 0
                firstFrameRendered = false
                pendingSeekTargetMs = null
                playbackState = STATE_BUFFERING
            }
            MpvEvent.FileLoaded -> {
                fileLoadedForCurrentItem = true
                tracks = MpvCore.getTracks()
                durationMs = readDurationMs()
                applyAudioTrack()
                applySubtitleTrack()
                playbackState = STATE_READY
            }
            MpvEvent.EndFile -> {
                if (!loadingNewFile) {
                    // Distinguish natural end of file from manual stop / load errors.
                    // With keep-open=yes natural EOF usually arrives via MpvEvent.EofReached
                    // instead, but keep this as a fallback for stop-less EOF reporting.
                    val eofReached = MpvCore.getProperty<Boolean>("eof-reached") == true
                    if (!fileLoadedForCurrentItem && !stopRequested && !eofReached) {
                        // mpv never reached FILE_LOADED for the current item and playback ended
                        // without an explicit stop: the stream could not be opened/decoded
                        // (e.g. a direct-play source allowed by the relaxed codec profiles that
                        // mpv itself cannot handle). Report an error so PlayerViewModel falls back
                        // to direct stream / transcoding instead of hanging in STATE_IDLE.
                        pendingError = PlaybackException(
                            "mpv failed to open the media stream",
                            null,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        )
                        playbackState = STATE_IDLE
                    } else {
                        playbackState = if (eofReached) STATE_ENDED else STATE_IDLE
                    }
                }
            }
            MpvEvent.PlaybackRestart -> {
                playbackState = STATE_READY
                // Seek completed: time-pos is authoritative again
                pendingSeekTargetMs = null
                if (!firstFrameRendered) {
                    firstFrameRendered = true
                    pendingFirstFrame = true
                }
            }
            MpvEvent.Seek -> playbackState = STATE_BUFFERING
            is MpvEvent.Caching ->
                playbackState = if (event.isCaching) STATE_BUFFERING else STATE_READY
            is MpvEvent.EofReached -> {
                // keep-open=yes keeps the file loaded on the last frame: this is the primary
                // signal for natural playback completion, and seeking away clears it again.
                playbackState = when {
                    event.isEofReached -> STATE_ENDED
                    playbackState == STATE_ENDED -> STATE_READY
                    else -> playbackState
                }
            }
            is MpvEvent.DurationChanged -> {
                // Duration may only become known (or grow) after loading started.
                if (event.durationMs > 0 && event.durationMs != durationMs) {
                    durationMs = event.durationMs
                }
            }
            MpvEvent.DecoderChanged -> reportDecoderState()
            MpvEvent.TrackListChanged -> {
                // External subtitle tracks, in particular network ones, may appear only after
                // FILE_LOADED. Refresh and re-apply the pending selection once they are known.
                tracks = MpvCore.getTracks()
                applyAudioTrack()
                applySubtitleTrack()
            }
        }
        invalidateState()
    }

    init {
        MpvCore.initialize(application)
        MpvCore.subscribe(eventListener)
    }

    val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            MpvCore.attachSurface(holder.surface)
            MpvCore.setProperty("vid", "auto")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            MpvCore.setProperty("android-surface-size", "${width}x${height}")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            MpvCore.setProperty("vid", "no")
            MpvCore.detachSurface()
        }
    }

    private val permanentAvailableCommands =
        Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_PLAYLIST_METADATA,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_GET_AUDIO_ATTRIBUTES,
                COMMAND_SET_AUDIO_ATTRIBUTES,
                COMMAND_GET_VOLUME,
                COMMAND_SET_VOLUME,
                COMMAND_SET_VIDEO_SURFACE,
                COMMAND_GET_TEXT,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                COMMAND_RELEASE,
                // COMMAND_SET_SHUFFLE_MODE,
                // COMMAND_SET_REPEAT_MODE,
                // COMMAND_CHANGE_MEDIA_ITEMS,
                // COMMAND_GET_TRACKS, tracks are managed via the Jellyfin UI and MpvPlayer methods,
                // not through media3's track information
                // COMMAND_PREPARE, mpv can use the start option to jump to a position while
                // loading, so an explicit prepare step is unnecessary
            )
            .build()

    override fun getState(): State {
        val paused = MpvCore.getProperty<Boolean>("pause") ?: true
        val speed = MpvCore.getProperty<Double>("speed")?.toFloat() ?: 1f

        val mediaItemData = MediaItemData.Builder(mediaItemUid)
            .setMediaItem(currentMediaItem ?: MediaItem.EMPTY)
            .setDurationUs(if (durationMs > 0) Util.msToUs(durationMs) else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        return State.Builder()
            .setPlaylist(listOf(mediaItemData))
            .setAvailableCommands(permanentAvailableCommands)
            // keep-open leaves mpv internally paused on the last frame without changing the pause
            // property, so STATE_ENDED has to report playWhenReady=false explicitly (media3 semantics).
            .setPlayWhenReady(playbackState != STATE_ENDED && !paused, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(pendingError)
            .setNewlyRenderedFirstFrame(consumePendingFirstFrame())
            .setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
            .setContentPositionMs { currentPositionMs() }
            .setContentBufferedPositionMs { bufferedPositionMs() }
            .setPlaybackParameters(PlaybackParameters(speed))
            .build()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val surfaceView = videoOutput as SurfaceView
        surfaceView.holder.addCallback(surfaceHolderCallback)
        return Futures.immediateFuture(null)
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        val surfaceView = videoOutput as SurfaceView
        surfaceView.holder.removeCallback(surfaceHolderCallback)
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        MpvCore.setProperty("pause", !playWhenReady)
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val wasEnded = playbackState == STATE_ENDED

        // Duration may only become known after FILE_LOADED (e.g. HLS transcodes); refresh it
        // so the EOF safety margin also works for those sources.
        val currentDurationMs = readDurationMs()
        if (currentDurationMs > 0) durationMs = currentDurationMs

        // Never let a user seek (double-tap fast forward, swipe gesture, progress bar) land
        // exactly on EOF: mpv would end playback immediately, mark the episode as watched and
        // advance to the next one. Keep a small margin and let playback finish naturally.
        val targetMs = if (durationMs > Constants.SEEK_TO_END_SAFETY_MARGIN_MS) {
            positionMs.coerceIn(0, durationMs - Constants.SEEK_TO_END_SAFETY_MARGIN_MS)
        } else {
            positionMs.coerceAtLeast(0)
        }

        // Report the target position immediately, even before mpv processed the command,
        // so callers reading currentPosition right after seekTo() (e.g. a quick second
        // press of "previous") don't observe the stale pre-seek position.
        pendingSeekTargetMs = targetMs

        val seconds = "%.3f".format(Locale.ROOT, targetMs / 1000.0)
        MpvCore.command(arrayOf("seek", seconds, "absolute+exact"))
        invalidateState()

        // With keep-open=yes mpv stays internally paused on the last frame at EOF.
        // Seeking away from it has to resume playback, matching ExoPlayer's seekTo()
        // behavior after STATE_ENDED.
        if (wasEnded) {
            MpvCore.setProperty("pause", false)
        }
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        MpvCore.setProperty("speed", playbackParameters.speed.toDouble())
        return Futures.immediateFuture(null)
    }

    override fun handleStop(): ListenableFuture<*> {
        stopRequested = true
        MpvCore.command(arrayOf("stop"))
        return Futures.immediateFuture(null)
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        this.trackSelectionParameters = trackSelectionParameters
        return Futures.immediateFuture(null)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val mediaItem = mediaItems[0]
        val localConfiguration = mediaItem.localConfiguration
            ?: return Futures.immediateFuture(null)

        loadingNewFile = true
        fileLoadedForCurrentItem = false
        stopRequested = false
        pendingError = null
        mediaItemUid = UUID.randomUUID()
        currentMediaItem = mediaItem
        tracks = emptyList()
        durationMs = 0
        firstFrameRendered = false
        pendingFirstFrame = false

        // hwdec is a global runtime option, not a per-file loadfile option.
        MpvCore.setProperty(
            "hwdec",
            if (decoderProvider() == DecoderType.HARDWARE) "auto" else "no",
        )

        val options = mutableListOf<String>()
        val externalSubtitleFiles = localConfiguration.subtitleConfigurations.joinToString(":") { config ->
            // Escape list separators in mpv's path-list syntax.
            config.uri.toString().replace(":", "\\:")
        }
        if (externalSubtitleFiles.isNotEmpty()) {
            options += "sub-files-set=$externalSubtitleFiles"
        }
        options += "start=+%.3f".format(Locale.ROOT, startPositionMs / 1000.0)

        MpvCore.command(
            arrayOf(
                "loadfile",
                localConfiguration.uri.toString(),
                "replace",
                "0",
                options.joinToString(","),
            ),
        )
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        stopRequested = true
        MpvCore.unsubscribe(eventListener)
        MpvCore.command(arrayOf("stop"))
        return Futures.immediateFuture(null)
    }

    override fun handleSetAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        return Futures.immediateFuture(null)
    }

    @UnstableApi
    fun setAnalyticsCollector(analyticsCollector: AnalyticsCollector?) {
        Assertions.checkNotNull<AnalyticsCollector?>(analyticsCollector)
        analyticsCollector!!.setPlayer(this, applicationLooper)
    }

    fun disableSubTrack() {
        subtitleSelection = SubtitleSelection.Disabled
        MpvCore.setProperty("sid", "no")
    }

    fun setSubtitleEncodeTrack() {
        subtitleSelection = SubtitleSelection.Encoded
        MpvCore.setProperty("sid", "no")
    }

    fun setSubtitleEmbedTrack(trackIndex: Int) {
        subtitleSelection = SubtitleSelection.Embedded(trackIndex)
        applySubtitleTrack()
    }

    fun setSubtitleExternalTrack(filename: String?) {
        subtitleSelection = SubtitleSelection.External(filename)
        applySubtitleTrack()
    }

    fun setAudioTrack(trackIndex: Int) {
        selectedAudioTrackIndex = trackIndex
        applyAudioTrack()
    }

    fun setDecoderProcessor(decoderProvider: () -> DecoderType, decoderListener: (DecoderType) -> Unit) {
        this.decoderProvider = decoderProvider
        this.decoderListener = decoderListener
    }

    private fun readDurationMs(): Long {
        val duration = MpvCore.getProperty<Double>("duration") ?: return 0
        return (duration * 1000).toLong()
    }

    private fun currentPositionMs(): Long {
        // While a seek is in flight mpv keeps reporting the pre-seek time-pos (for network
        // sources this can take more than a second). Return the seek target instead until
        // playback restarts, matching ExoPlayer's immediate position update on seekTo().
        val pendingTarget = pendingSeekTargetMs
        if (pendingTarget != null &&
            (MpvCore.getProperty<Boolean>("seeking") == true || playbackState == STATE_BUFFERING)
        ) {
            return pendingTarget
        }
        val timePos = MpvCore.getProperty<Double>("time-pos/full") ?: 0.0
        return (timePos * 1000).toLong()
    }

    private fun bufferedPositionMs(): Long {
        val cacheTime = MpvCore.getProperty<Double>("demuxer-cache-time") ?: 0.0
        return (cacheTime * 1000).toLong()
    }

    private fun consumePendingFirstFrame(): Boolean {
        return pendingFirstFrame.also { pendingFirstFrame = false }
    }

    private fun applyAudioTrack() {
        val trackIndex = selectedAudioTrackIndex ?: return
        val trackId = tracks
            .filter { track ->
                track.getTrackType() == MpvCore.TrackType.AUDIO && !track.external
            }.getOrNull(trackIndex)?.id ?: return
        MpvCore.setProperty("aid", trackId)
    }

    private fun applySubtitleTrack() {
        val sid = when (val selection = subtitleSelection) {
            SubtitleSelection.Auto -> return // Let mpv choose its default track
            SubtitleSelection.Disabled, SubtitleSelection.Encoded -> "no"
            is SubtitleSelection.Embedded -> {
                tracks
                    .filter { track ->
                        track.getTrackType() == MpvCore.TrackType.SUBTITLE && !track.external
                    }.getOrNull(selection.trackIndex)?.id ?: "no"
            }
            is SubtitleSelection.External -> {
                tracks.firstOrNull { track ->
                    track.getTrackType() == MpvCore.TrackType.SUBTITLE &&
                        track.external &&
                        selection.filename?.let(track.externalFilename::contains) == true
                }?.id ?: "no"
            }
        }
        MpvCore.setProperty("sid", sid)
    }

    private fun reportDecoderState() {
        val hwdec = MpvCore.getProperty<String>("hwdec-current")
        val decoderType = if (hwdec.isNullOrEmpty() || hwdec == "no") {
            DecoderType.SOFTWARE
        } else {
            DecoderType.HARDWARE
        }
        decoderListener(decoderType)
    }
}
