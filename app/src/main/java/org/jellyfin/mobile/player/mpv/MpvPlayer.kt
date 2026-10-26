package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.Surface
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.utils.Constants
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Media3  [SimpleBasePlayer]  backed by native mpv via [MpvCore].
 *
 * @author dr
 */
class MpvPlayer(
    application: Application,
    looper: Looper,
    /**
     * demuxer 预读上限：预读时长（cache-secs，秒）与字节上限（demuxer-max-bytes，字节）。
     * 每次加载媒体前下发；为 null 的项不下发，保持 mpv.conf / 内置默认值。
     */
    private val bufferLimitsProvider: () -> Pair<Double?, Long?> = { null to null },
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
     * Target position of the latest explicit seek, kept until mpv's [time-pos] actually caught
     * up with it. While a seek is in flight mpv keeps reporting the pre-seek time-pos (network
     * seeks can take well over a second), whereas ExoPlayer exposes the seek target
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
                // A new file invalidates any error reported for the previous file.
                pendingError = null
                playbackState = STATE_BUFFERING
            }
            MpvEvent.FileLoaded -> {
                fileLoadedForCurrentItem = true
                tracks = MpvCore.getTracks()
                durationMs = readDurationMs()
                applyAudioTrack()
                applySubtitleTrack()
                // ExoPlayer semantics: STATE_READY means "able to immediately play from the
                // current position", not "the file was opened". FILE_LOADED only reports that
                // the demuxer is open - with cache-pause-initial=yes the initial cache fill
                // still has to complete before playback can start, and the paused-for-cache
                // event arriving afterwards used to flip the state back to BUFFERING. That
                // BUFFERING -> READY -> BUFFERING flicker disposed and recreated the loading
                // indicator (restarting its spin) on every load. Staying in BUFFERING here
                // collapses the whole initial fill into a single transition to READY, exactly
                // like media3's load control reports READY only once enough is buffered.
                // A prepared player that is not asked to play is STATE_READY in media3 as
                // well, hence the pause check.
                if (MpvCore.pause) playbackState = STATE_READY
            }
            MpvEvent.EndFile -> {
                if (!loadingNewFile) {
                    // Distinguish natural end of file from manual stop / load errors.
                    // With keep-open=yes natural EOF usually arrives via MpvEvent.EofReached
                    // instead, but keep this as a fallback for stop-less EOF reporting.
                    // eof-reached comes from the observer cache (never a synchronous read on
                    // this main-thread handler - see readDurationMs).
                    val eofReached = MpvCore.eofReached
                    when {
                        stopRequested -> playbackState = STATE_IDLE
                        !fileLoadedForCurrentItem -> {
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
                        }
                        // 文件已经播放过：判断"正常播完"还是"中途断流"。
                        //
                        // 不能只看 eof-reached：流被截断时 mpv 同样会把 eof-reached 置真
                        // （日志为 `http: Stream ends prematurely` → `end-file`），于是旧逻辑
                        // 走 STATE_ENDED，播放页当成"播完了"直接关闭，用户只看到退回详情页，
                        // 完全不知道是断网。播放位置才是可靠的判据 —— 距结尾还差一大截就结束，
                        // 一定是被打断的。
                        isEndOfFileNearEnd() -> playbackState = if (eofReached) STATE_ENDED else STATE_IDLE
                        else -> {
                            pendingError = PlaybackException(
                                "mpv playback ended before the end of the file (stream interrupted)",
                                null,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            )
                            playbackState = STATE_IDLE
                        }
                    }
                }
            }
            MpvEvent.PlaybackRestart -> {
                playbackState = STATE_READY
                // Note: the pending seek target is intentionally NOT cleared here. mpv emits
                // PLAYBACK_RESTART before time-pos reflects the new position, so clearing it
                // here made the position briefly fall back to the pre-seek value (the seek bar
                // jumped to the target, snapped back, then jumped again). It is cleared in
                // currentPositionMs() once time-pos has actually settled on the target.
                if (!firstFrameRendered) {
                    firstFrameRendered = true
                    pendingFirstFrame = true
                }
            }
            MpvEvent.Seek -> playbackState = STATE_BUFFERING
            // paused-for-cache is mpv's genuine "cannot proceed right now" signal - both the
            // initial fill (cache-pause-initial=yes) and rebuffering during playback. It is
            // the ExoPlayer-equivalent of the load control dropping back to STATE_BUFFERING,
            // and unlike the old FILE_LOADED-based READY it does not flicker.
            is MpvEvent.Caching ->
                playbackState = if (event.isCaching) STATE_BUFFERING else STATE_READY
            is MpvEvent.EofReached -> {
                // keep-open=yes keeps the file loaded on the last frame: this is the primary
                // signal for natural playback completion, and seeking away clears it again.
                //
                // 但 eof-reached 并不等于"正常播完"：流被截断时 mpv 同样会把它置真（内嵌
                // ffmpeg 先报 `http: Stream ends prematurely`，再走 end-file）。只看这个标志
                // 会把"播放中途断网"当成播完 —— 播放页据此直接静默关闭退回详情页，用户
                // 完全不知道发生了什么。所以还要核对播放位置是否真的到了片尾。
                playbackState = when {
                    !event.isEofReached -> if (playbackState == STATE_ENDED) STATE_READY else playbackState
                    isEndOfFileNearEnd() -> STATE_ENDED
                    else -> {
                        pendingError = PlaybackException(
                            "mpv reported end of file well before the end of the stream (interrupted)",
                            null,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                        STATE_IDLE
                    }
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
            // pause/speed changed on mpv's side (e.g. an external pause): the new value is already
            // in MpvCore's cache, so only the state refresh below is needed.
            MpvEvent.HotPropertyChanged -> Unit
        }
        invalidateState()
    }

    init {
        MpvCore.initialize(application)
        MpvCore.subscribe(eventListener)
    }

    /**
     * The surface currently handed to mpv, or null when mpv holds none.
     *
     * media3's `PlayerView.setPlayer` calls `clearVideoSurfaceView` right before
     * `setVideoSurfaceView` on *every* invocation, and the Compose player screen recomposes
     * several times per second (its position poll). Acting on those two transitions literally -
     * detaching on the clear, re-attaching on the set - made the platform [Surface] be torn
     * down and recreated at that rate, which killed mpv's video output while its audio track
     * kept playing: a black picture with sound.
     *
     * [handleSetVideoOutput] / [handleClearVideoOutput] and the holder callbacks therefore all
     * go through [attachSurface] / [detachSurface], which only act on a real change of output.
     */
    private var attachedSurface: Surface? = null

    /** The view [surfaceHolderCallback] is currently registered on, or null. */
    private var attachedSurfaceView: SurfaceView? = null

    /** Pending teardown posted by [handleClearVideoOutput], cancelled by a matching set. */
    private var pendingDetach: Runnable? = null

    private val mainHandler = Handler(looper)

    /**
     * 结束播放时的位置是否已经接近片尾。
     *
     * 时长未知（直播 / 时长还没读到的 HLS）时无法判断，一律按"已到结尾"处理 —— 宁可漏报
     * 一次断流，也不要把正常看到结尾误报成错误。
     */
    private fun isEndOfFileNearEnd(): Boolean {
        if (durationMs <= 0) return true
        // 用缓存里的 time-pos：END_FILE 之后 mpv 可能已经把它清零，而缓存保留着最后观测到的位置。
        return currentPositionMs() >= durationMs - END_OF_FILE_TOLERANCE_MS
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            attachSurface(holder.surface, attachedSurfaceView)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            updateSurfaceSize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // The handler is called with the surface it is destroying; only drop the output if
            // that is still the one mpv is using.
            if (attachedSurface === holder.surface) detachSurface()
        }
    }

    /** Hands [surface] to mpv. Repeated calls for the surface already attached are no-ops. */
    private fun attachSurface(surface: Surface?, surfaceView: SurfaceView?) {
        if (surface == null || attachedSurface === surface) return
        // mpv holds a single video output: drop the previous one before replacing it.
        if (attachedSurface != null) {
            MpvCore.detachSurface()
            attachedSurface = null
        }
        attachedSurface = surface
        MpvCore.attachSurface(surface)
        MpvCore.setProperty("vid", "auto")
        // Report the size on (re)attach instead of trusting the last surfaceChanged: that callback
        // is only guaranteed for size *changes*, and a stale android-surface-size changes the
        // aspect of the picture (mpv scales into the size it was told), it does not just blur it.
        surfaceView?.let { updateSurfaceSize(it.width, it.height) }
    }

    /**
     * Tells mpv how large the video surface currently is, in pixels.
     *
     * The value must be the surface size in pixels - never a layout size in dp: mpv renders into
     * the size it is told and the platform stretches that buffer onto the real surface.
     */
    private fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        MpvCore.setProperty("android-surface-size", "${width}x${height}")
    }

    /** Takes the current surface away from mpv, if there is one. */
    private fun detachSurface() {
        if (attachedSurface == null) return
        attachedSurface = null
        MpvCore.setProperty("vid", "no")
        MpvCore.detachSurface()
    }

    /** Calls off the teardown [handleClearVideoOutput] posted, if one is still pending. */
    private fun cancelPendingDetach() {
        pendingDetach?.let { mainHandler.removeCallbacks(it) }
        pendingDetach = null
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
        // Read from MpvCore's cache, never via a synchronous mpv property read: getState() runs
        // on the main thread (media3) on every state refresh, and during initial load such a read
        // can block on mpv's core lock long enough to stutter the loading spinner.
        val paused = MpvCore.pause
        val speed = MpvCore.speed.toFloat()

        val mediaItemData = MediaItemData.Builder(mediaItemUid)
            .setMediaItem(currentMediaItem ?: MediaItem.EMPTY)
            .setDurationUs(if (durationMs > 0) Util.msToUs(durationMs) else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        // A reported player error is terminal for the current item. The fallback triggered
        // in PlayerViewModel is asynchronous, so while it runs, late property events of the
        // failed file (paused-for-cache / eof-reached) may move playbackState out of IDLE.
        // media3's State only permits a playerError in STATE_IDLE, so pin the reported state
        // to IDLE until StartFile of the next item clears pendingError.
        val effectivePlaybackState = if (pendingError != null) STATE_IDLE else playbackState

        return State.Builder()
            .setPlaylist(listOf(mediaItemData))
            .setAvailableCommands(permanentAvailableCommands)
            // keep-open leaves mpv internally paused on the last frame without changing the pause
            // property, so STATE_ENDED has to report playWhenReady=false explicitly (media3 semantics).
            .setPlayWhenReady(effectivePlaybackState != STATE_ENDED && !paused, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(effectivePlaybackState)
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
        // media3 pairs this with a [handleClearVideoOutput] for the very same view: the pending
        // teardown of that clear is called off here.
        cancelPendingDetach()
        if (surfaceView !== attachedSurfaceView) {
            unregisterSurfaceHolder()
            attachedSurfaceView = surfaceView
            surfaceView.holder.addCallback(surfaceHolderCallback)
        }
        attachSurface(surfaceView.holder.surface, surfaceView)
        return Futures.immediateFuture(null)
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        // Called *before* every [handleSetVideoOutput], usually for the very same view (see
        // [attachedSurface] for why reacting immediately broke video playback). The teardown is
        // therefore deferred by one main-loop turn and called off by the matching set: a pair
        // collapses into a no-op, while a clear that is not followed by a set still releases
        // mpv's output.
        val view = videoOutput as? SurfaceView
        cancelPendingDetach()
        val runnable = Runnable {
            pendingDetach = null
            // A null output means "drop whatever is attached"; a specific view only drops the
            // output if that view is still the one mpv renders into.
            if (view == null || attachedSurfaceView === view) {
                unregisterSurfaceHolder()
                detachSurface()
            }
        }
        pendingDetach = runnable
        mainHandler.post(runnable)
        return Futures.immediateFuture(null)
    }

    /** Unregisters [surfaceHolderCallback] from whatever view it was registered on. */
    private fun unregisterSurfaceHolder() {
        attachedSurfaceView?.holder?.removeCallback(surfaceHolderCallback)
        attachedSurfaceView = null
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

        // 「播放缓冲区大小」设置：在文件加载前下发 demuxer 预读上限（排队先于下面的
        // loadfile 执行），蜂窝网络下已是降档后的值。
        bufferLimitsProvider().let { MpvCore.setDemuxerBufferLimits(it.first, it.second) }

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
        cancelPendingDetach()
        unregisterSurfaceHolder()
        detachSurface()
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

    /**
     * Attaches [analyticsCollector] to this player.
     *
     * Takes a non-null collector on purpose: attaching is what makes the collector releasable
     * ([AnalyticsCollector.release] must follow [AnalyticsCollector.setPlayer]), so the caller
     * has to hand over a usable instance rather than a nullable one the player then has to
     * assert away.
     */
    @UnstableApi
    fun setAnalyticsCollector(analyticsCollector: AnalyticsCollector) {
        analyticsCollector.setPlayer(this, applicationLooper)
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
        // From the cache maintained by MpvCore's property observer (the value arrives with the
        // change event, off the main thread). The previous synchronous read ran on the main
        // thread from the FILE_LOADED handler and could block on mpv's core lock while the
        // demuxer was busy - during the initial load that stalled the UI thread long enough
        // to visibly stutter the loading spinner.
        return MpvCore.durationMs
    }

    private fun currentPositionMs(): Long {
        // From the cache (updated by mpv's observer), not a synchronous read: this feeds
        // getState()'s content-position supplier on the main thread.
        val timePosMs = MpvCore.timePosMs
        // While a seek is in flight mpv keeps reporting the pre-seek time-pos (for network
        // sources this can take more than a second). Return the seek target instead until mpv
        // actually caught up to it, matching ExoPlayer's immediate position update on seekTo().
        // Releasing the target as soon as playback "restarted" is not enough: PLAYBACK_RESTART
        // can fire while time-pos is still the old value, which made the position snap back
        // to the pre-seek point for a poll cycle before settling on the target.
        val pendingTarget = pendingSeekTargetMs
        if (pendingTarget != null) {
            if (abs(timePosMs - pendingTarget) > Constants.SEEK_POSITION_SETTLE_EPSILON_MS) {
                return pendingTarget
            }
            // mpv caught up (or the seek was smaller than the epsilon): time-pos is authoritative
            pendingSeekTargetMs = null
        }
        return timePosMs
    }

    private fun bufferedPositionMs(): Long {
        // From the cache, not a synchronous `demuxer-cache-time` read (see currentPositionMs).
        return MpvCore.demuxerCacheMs
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
        // hwdec-current comes from the observer cache: DecoderChanged is posted right after
        // the value was cached, so it is fresh here without a synchronous read on the main
        // thread (see readDurationMs for why that matters during the initial load).
        val hwdec = MpvCore.hwdecCurrent
        val decoderType = if (hwdec.isNullOrEmpty() || hwdec == "no") {
            DecoderType.SOFTWARE
        } else {
            DecoderType.HARDWARE
        }
        decoderListener(decoderType)
    }

    private companion object {
        /** 距结尾小于该时长时视为正常播完，不当作断流。 */
        const val END_OF_FILE_TOLERANCE_MS = 5_000L
    }
}
