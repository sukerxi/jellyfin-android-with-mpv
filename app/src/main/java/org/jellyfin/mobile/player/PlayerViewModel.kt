package org.jellyfin.mobile.player

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.PLAYER_EVENT_CHANNEL
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.interaction.PlayerLifecycleObserver
import org.jellyfin.mobile.player.interaction.PlayerMediaSessionCallback
import org.jellyfin.mobile.player.interaction.PlayerNotificationHelper
import org.jellyfin.mobile.player.mediasegments.MediaSegmentAction
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.mpv.MpvPlayer
import org.jellyfin.mobile.player.mpv.MpvUtil
import org.jellyfin.mobile.player.queue.QueueManager
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.DisplayPreferences
import org.jellyfin.mobile.player.ui.ChapterMarkingState
import org.jellyfin.mobile.player.ui.PlayState
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.mobile.utils.applyDefaultLocalAudioAttributes
import org.jellyfin.mobile.utils.extensions.end
import org.jellyfin.mobile.utils.extensions.scaleInRange
import org.jellyfin.mobile.utils.extensions.start
import org.jellyfin.mobile.utils.extensions.width
import org.jellyfin.mobile.utils.getVolumeLevelPercent
import org.jellyfin.mobile.utils.getVolumeRange
import org.jellyfin.mobile.utils.logTracks
import org.jellyfin.mobile.utils.seekToOffset
import org.jellyfin.mobile.utils.setPlaybackState
import org.jellyfin.mobile.utils.toMediaMetadata
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.DisplayPreferencesApi
import org.jellyfin.sdk.api.operations.HlsSegmentApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Suppress("TooManyFunctions")
class PlayerViewModel(application: Application) : AndroidViewModel(application), KoinComponent, Player.Listener {
    private val apiClient: ApiClient = get()
    private val displayPreferencesApi: DisplayPreferencesApi = apiClient.displayPreferencesApi
    private val playStateApi: PlayStateApi = apiClient.playStateApi
    private val hlsSegmentApi: HlsSegmentApi = apiClient.hlsSegmentApi
    private val userApi: UserApi = apiClient.userApi

    private val appPreferences: AppPreferences by inject()
    private val lifecycleObserver = PlayerLifecycleObserver(this)
    private val audioManager: AudioManager by lazy { getApplication<Application>().getSystemService()!! }
    val notificationHelper: PlayerNotificationHelper by lazy { PlayerNotificationHelper(this) }

    // Media source handling
    private val trackSelector = DefaultTrackSelector(getApplication())
    val trackSelectionHelper = TrackSelectionHelper(this, trackSelector,appPreferences)
    val queueManager = QueueManager(this)
    val mediaSourceOrNull: JellyfinMediaSource?
        get() = queueManager.getCurrentMediaSourceOrNull()
    private val mediaSegmentRepository: MediaSegmentRepository by inject()

    // ExoPlayer
    private val _player = MutableLiveData<Player?>()
    private val _playerState = MutableLiveData<Int>()
    /** Decoder selection made by the user; null means no explicit choice (auto). */
    private val _decoderType = MutableLiveData<DecoderType>()
    /** Decoder actually in use, as reported by the player after (re)loading. */
    private val _detectedDecoderType = MutableLiveData<DecoderType>()
    val player: LiveData<Player?> get() = _player
    val playerState: LiveData<Int> get() = _playerState
    val decoderType: LiveData<DecoderType> get() = _decoderType
    val detectedDecoderType: LiveData<DecoderType> get() = _detectedDecoderType

    // Player Menus
    // 阶段 5：播放器控制层 Compose 化。章节标记与"跳过"按钮不再直接操作 View，
    // 改为以状态流暴露，由 Compose 层（PlayerScreen）渲染。
    private val _chapterMarkings = MutableStateFlow<List<ChapterMarkingState>>(emptyList())
    private val _activeAskToSkipSegment = MutableStateFlow<MediaSegmentDto?>(null)

    /** 章节标记（按 item.chapters 顺序）：bias 为在进度条上的相对位置，watched 为是否已播放过。 */
    val chapterMarkings: StateFlow<List<ChapterMarkingState>> = _chapterMarkings.asStateFlow()

    /** 当前处于可跳过的媒体分段内时非空（用于展示"跳过"按钮）。 */
    val activeAskToSkipSegment: StateFlow<MediaSegmentDto?> = _activeAskToSkipSegment.asStateFlow()

    // Media Segments Ask to Skip
    private var askToSkipMediaSegments: List<MediaSegmentDto> = emptyList()

    // Media Segments configured to be skipped automatically (used by the mpv polling watcher)
    private var autoSkipMediaSegments: List<MediaSegmentDto> = emptyList()

    /** Segments already auto-skipped since the current item started; cleared on backward seeks. */
    private val skippedAutoSegments = mutableSetOf<MediaSegmentDto>()
    private var lastAutoSkipPositionMs = -1L

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val eventLogger = EventLogger()
    private var analyticsCollector = buildAnalyticsCollector()
    private val initialTracksSelected = AtomicBoolean(false)
    private var fallbackPreferExtensionRenderers = false
    private var playSpeed = 1f

    private var progressUpdateJob: Job? = null
    private var chapterMarkingUpdateJob: Job? = null
    private var skipMediaSegmentUpdateJob: Job? = null
    private var autoSkipMediaSegmentUpdateJob: Job? = null
    private var fallbackRetryJob: Job? = null

    /**
     * The current player, or null when no player session is alive. Can be an [ExoPlayer] or
     * an mpv based player, depending on the selected engine.
     */
    val playerOrNull: Player? get() = _player.value

    /**
     * The current player if it is of type [T], or null otherwise.
     */
    inline fun <reified T : Player> playerOrNull(): T? = playerOrNull as? T

    private val playerEventChannel: Channel<PlayerEvent> by inject(named(PLAYER_EVENT_CHANNEL))

    private val mediaSessionCallback = PlayerMediaSessionCallback(this)
    private var _mediaSession: MediaSession? = null

    /**
     * The session is created lazily and survives [releasePlayer], so that a later
     * [setupPlayer] reuses it. Only [onCleared] releases it for good.
     */
    val mediaSession: MediaSession
        get() = _mediaSession ?: createMediaSession().also { _mediaSession = it }

    private var displayPreferences = DisplayPreferences()
    private var autoPlayNextEpisodeEnabled: Boolean = false

    /**
     * Whether the player was closed by the user ("back" / closing the player overlay), as
     * opposed to the web app ending playback. The web app only calls its stop methods when
     * it stops playback itself, so leaving the player screen has to stop the native player
     * explicitly - otherwise the (foreground service backed) playback keeps running.
     */
    private var closedByUser = false

    /**
     * Whether the web app is streaming to a remote device (Cast). The remote player service
     * keeps its own playback alive in that case and must not be interrupted by the native
     * player being closed.
     */
    private val remotePlaybackActive: Boolean
        get() = (getApplication<Application>() as? MainActivity)?.serviceBinder?.isPlaying == true

    /**
     * Whether playback should be allowed to run while the app is in the background:
     * the user consented by enabling "Background audio" (now called "Play in the
     * background"), or the web app is playing to a remote device (Cast).
     */
    private val backgroundPlaybackAllowed: Boolean
        get() = appPreferences.exoPlayerAllowBackgroundAudio || remotePlaybackActive

    init {
        // The process lifecycle observer is registered per playback session in [setupPlayer]
        // and removed in [releasePlayer]: the ViewModel outlives individual player sessions
        // (it lives in the activity's ViewModelStore), and a stale observer would tear down
        // the playback of a session it does not belong to.

        // Load display preferences
        viewModelScope.launch {
            var customPrefs: Map<String, String?>? = null
            try {
                val displayPreferencesDto = withContext(Dispatchers.IO) {
                    displayPreferencesApi.getDisplayPreferences(
                        displayPreferencesId = Constants.DISPLAY_PREFERENCES_ID_USER_SETTINGS,
                        client = Constants.DISPLAY_PREFERENCES_CLIENT_EMBY,
                    ).content
                }

                customPrefs = displayPreferencesDto.customPrefs
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load display preferences from API")
            }

            displayPreferences = DisplayPreferences(
                skipBackLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_BACK_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
                skipForwardLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_FORWARD_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
            )
        }

        viewModelScope.launch {
            try {
                val userConfig = withContext(Dispatchers.IO) {
                    userApi.getCurrentUser().content.configuration
                }
                autoPlayNextEpisodeEnabled = userConfig?.enableNextEpisodeAutoPlay ?: false
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load auto play preference")
            }
        }

        // Subscribe to player events from webapp
        viewModelScope.launch {
            for (event in playerEventChannel) {
                when (event) {
                    PlayerEvent.Pause -> mediaSessionCallback.onPause()
                    PlayerEvent.Resume -> mediaSessionCallback.onPlay()
                    PlayerEvent.Stop, PlayerEvent.Destroy -> mediaSessionCallback.onStop()
                    is PlayerEvent.Seek -> playerOrNull?.seekTo(event.duration.inWholeMilliseconds)
                    is PlayerEvent.SetVolume -> {
                        setVolume(event.volume)
                        playerOrNull?.reportPlaybackState()
                    }
                }
            }
        }
    }

    private fun buildAnalyticsCollector() = DefaultAnalyticsCollector(Clock.DEFAULT).apply {
        addListener(eventLogger)
    }

    private fun createMediaSession() = MediaSession(
        getApplication<Application>().applicationContext,
        javaClass.simpleName.removePrefix(BuildConfig.APPLICATION_ID),
    ).apply {
        @Suppress("DEPRECATION")
        setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        setCallback(mediaSessionCallback)
        applyDefaultLocalAudioAttributes(AudioAttributes.CONTENT_TYPE_MOVIE)
    }

    /**
     * Builds the player selected in the preferences.
     *
     * Deliberately a *concrete* factory instead of the previous generic
     * `buildPlayer<T>()`: that one returned `T` through an unchecked `as T` cast, and its
     * callers then immediately dereferenced the result. Keeping the two engines in named
     * functions makes the return type follow the actual builder (an [ExoPlayer] or an
     * [MpvPlayer]) instead of an erased type parameter, so nothing is cast and the
     * construction path can be read - and reviewed - linearly.
     */
    private fun buildPlayer(): Player = when (val type = appPreferences.videoPlayerType) {
        VideoPlayerType.EXO_PLAYER -> buildExoPlayer()
        VideoPlayerType.MPV_PLAYER -> buildMpvPlayer()
        else -> throw IllegalArgumentException("Invalid video player type: $type")
    }

    @Suppress("MagicNumber")
    private fun buildLoadControl(): DefaultLoadControl = when (appPreferences.exoPlayerNetworkBuffer) {
        Constants.NETWORK_BUFFER_LARGE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
            .build()
        Constants.NETWORK_BUFFER_EXTRA_LARGE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(80_000, 240_000, 5_000, 10_000)
            .build()
        else -> DefaultLoadControl()
    }

    private fun buildRenderersFactory(preferExtensionRenderers: Boolean): RenderersFactory {
        val application: Application = getApplication()
        val defaultFactory = DefaultRenderersFactory(application).apply {
            setEnableDecoderFallback(true) // Fallback only works if initialization fails, not decoding at playback time
            val rendererMode = when {
                preferExtensionRenderers -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            setExtensionRendererMode(rendererMode)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoderInfoList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                // Allow decoder selection only for video track
                if (!MimeTypes.isVideo(mimeType)) {
                    return@setMediaCodecSelector decoderInfoList
                }
                val filteredDecoderList = when (decoderType.value) {
                    DecoderType.HARDWARE -> decoderInfoList.filter(MediaCodecInfo::hardwareAccelerated)
                    DecoderType.SOFTWARE -> decoderInfoList.filterNot(MediaCodecInfo::hardwareAccelerated)
                    else -> decoderInfoList
                }
                // Update the detected decoder based on the first decoder selected; never
                // overwrite the user's selection, which would feed back into the next load.
                filteredDecoderList.firstOrNull()?.let { decoder ->
                    val decoderType = when {
                        decoder.hardwareAccelerated -> DecoderType.HARDWARE
                        else -> DecoderType.SOFTWARE
                    }
                    _detectedDecoderType.postValue(decoderType)
                }

                filteredDecoderList
            }
        }
        // AssRenderersFactory wraps the default factory with libass support; it only
        // implements RenderersFactory (there is no context-taking constructor), so the
        // handler is passed explicitly here rather than resolved again per playback.
        return if (appPreferences.exoPlayerDirectPlayAss) {
            AssRenderersFactory(get<AssHandler>(), defaultFactory)
        } else {
            defaultFactory
        }
    }

    private fun buildExoPlayer(): ExoPlayer {
        val application: Application = getApplication()
        val renderersFactory = buildRenderersFactory(fallbackPreferExtensionRenderers)
        val player = ExoPlayer.Builder(application, renderersFactory, get())
            .setUsePlatformDiagnostics(false)
            .setTrackSelector(trackSelector)
            .setAnalyticsCollector(analyticsCollector)
            .setLoadControl(buildLoadControl())
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)
        if (appPreferences.exoPlayerDirectPlayAss) get<AssHandler>().init(player)
        return player
    }

    private fun buildMpvPlayer(): MpvPlayer {
        val application: Application = getApplication()
        return MpvPlayer(application, Looper.getMainLooper()).apply {
            setDecoderProcessor(
                { _decoderType.value ?: DecoderType.HARDWARE },
                { _detectedDecoderType.postValue(it) },
            )
            setAnalyticsCollector(analyticsCollector)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)
        }
    }

    /**
     * The ExoPlayer based engine, or null when the mpv engine is selected (or no player
     * is set up). All ExoPlayer specific interactions go through this accessor so the
     * generic [Player] API is never downcast at the use site.
     */
    val exoPlayerOrNull: ExoPlayer? get() = playerOrNull()

    /**
     * The mpv based engine, or null when the ExoPlayer engine is selected (or no player
     * is set up).
     */
    val mpvPlayerOrNull: MpvPlayer? get() = playerOrNull()

    /**
     * Sets up the player for a playback session: creates the engine, registers the listener
     * and starts observing the process lifecycle.
     *
     * Idempotent - the screen calls this before every session, while a player may still exist
     * from the previous one (closing the screen releases it, but a failed setup or an
     * in-flight fallback retry does not necessarily). Rebuilding unconditionally would hand
     * the UI a fresh player mid-session.
     */
    fun setupPlayer() = rebuildPlayer(fallbackPreferExtensionRenderers)

    /**
     * (Re)creates the player and optionally restarts the current media source on it.
     *
     * The single entry point for every player restart (initial setup, decoder change,
     * renderer fallback): one place that owns teardown, listener registration, lifecycle
     * observation and the restart, instead of each call site reimplementing it.
     */
    private fun rebuildPlayer(
        preferExtensionRenderers: Boolean,
        restartPlayback: Boolean = false,
    ) {
        if (playerOrNull != null) return

        // A player is being (re)created: drop the "user closed the player" latch so the new
        // player is not muted by [pauseInBackground].
        closedByUser = false
        fallbackPreferExtensionRenderers = preferExtensionRenderers

        // Swap the analytics collector *before* building the player (the builder wires the
        // new collector into it), but only release the old one once the new player exists:
        // an engine build that throws must not leave the ViewModel holding a collector that
        // was already torn down.
        val previousCollector = analyticsCollector
        analyticsCollector = buildAnalyticsCollector()

        val player = buildPlayer()
        previousCollector.release()

        // The listener is registered before the player is published, so an observer of
        // [player] - or a callback fired during `addListener` - already finds it subscribed.
        player.addListener(this@PlayerViewModel)
        _player.value = player

        // Observe the process lifecycle only while a player session is alive. Registered here
        // (not in `init`) because the ViewModel outlives player sessions: a stale observer
        // would pause/stop a session it does not belong to.
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        if (restartPlayback) queueManager.tryRestartPlayback()
    }

    /**
     * Tears the current player down and builds a new one, then restarts the current media
     * source on it. Used for decoder switches and error fallbacks, which have to preserve
     * the playback position of the item being played.
     */
    private fun rebuildPlayerAndRestart() {
        val playedTime = (playerOrNull?.currentPosition ?: 0L).milliseconds
        releasePlayerForRebuild()
        queueManager.getCurrentMediaSourceOrNull()?.startTime = playedTime
        rebuildPlayer(fallbackPreferExtensionRenderers, restartPlayback = true)
    }

    /**
     * Releases the current player *without* ending the playback session: the ViewModel keeps
     * its state (and the caller rebuilds the player) so playback can be restarted where it
     * was interrupted.
     */
    private fun releasePlayerForRebuild() {
        playerOrNull?.run {
            removeListener(this@PlayerViewModel)
            release()
        }
        _player.value = null
    }

    /**
     * Release the current player, stop progress reporting and drop the notification.
     *
     * The [MediaSession] is only deactivated here: a later [setupPlayer] (e.g. the user
     * returns and starts playback again) reuses it. Releasing it would leave the rebuilt
     * player writing to a dead session. [onCleared] releases it for good.
     */
    private fun releasePlayer() {
        // Reset the audio focus handling latch: the next player is a fresh start.
        closedByUser = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        val releasedPlayer = playerOrNull
        _player.value = null
        releasedPlayer?.run {
            removeListener(this@PlayerViewModel)
            release()
        }

        // Stop progress reporting / notifications before tearing the session down. The
        // session is kept around (inactive) so a future setupPlayer() can reuse it.
        _mediaSession?.run {
            isActive = false
            setPlaybackState(PlaybackState.STATE_NONE, 0L, 0L)
        }
        notificationHelper.dismissNotification()
    }

    fun load(jellyfinMediaSource: JellyfinMediaSource, exoMediaSource: MediaSource, playWhenReady: Boolean) {
        val startTime = jellyfinMediaSource.startTime
        // Defensive: a caller that reaches playback without a player would otherwise get an
        // a silently empty screen (the old code just returned).
        if (playerOrNull == null) setupPlayer()
        val player = playerOrNull ?: return
        if (player is ExoPlayer){
            player.setMediaSource(exoMediaSource)
        }else if (player is MpvPlayer){
            player.setMediaItem(MpvUtil.convertJellyfinMediaSource(jellyfinMediaSource),startTime.inWholeMilliseconds)
        }

        player.prepare()

        initialTracksSelected.set(false)
        if (startTime > Duration.ZERO) player.seekTo(startTime.inWholeMilliseconds)

        applyMediaSegments(jellyfinMediaSource)

        player.playWhenReady = playWhenReady

        mediaSession.setMetadata(jellyfinMediaSource.toMediaMetadata())

        if (jellyfinMediaSource is RemoteJellyfinMediaSource) {
            viewModelScope.launch {
                player.reportPlaybackStart(jellyfinMediaSource)
            }
        }
    }

    fun loadDirect( playWhenReady: Boolean) {
        if (playerOrNull == null) setupPlayer()
        val player = playerOrNull ?: return
        player.setMediaItem(MediaItem.fromUri("http://baidu.com"))
        player.prepare()

        initialTracksSelected.set(false)

        val startTime = Duration.ZERO
        if (startTime > Duration.ZERO) player.seekTo(startTime.inWholeMilliseconds)


        player.playWhenReady = playWhenReady
    }

    private fun startProgressUpdates() {
        if (mediaSourceOrNull != null && mediaSourceOrNull !is RemoteJellyfinMediaSource) return
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.PLAYER_TIME_UPDATE_RATE)
                playerOrNull?.reportPlaybackState()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
    }

    private fun startChapterMarkingUpdates() {
        chapterMarkingUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.CHAPTER_MARKING_UPDATE_DELAY)
                playerOrNull?.setWatchedChapterMarkings()
            }
        }
    }

    private fun stopChapterMarkingUpdates() {
        chapterMarkingUpdateJob?.cancel()
    }

    private fun startSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY)
                playerOrNull?.updateSkipMediaSegmentButton()
            }
        }
    }

    private fun stopSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob?.cancel()
    }

    /**
     * Polls the playback position to auto-skip media segments for players without
     * ExoPlayer's scheduled-message support (i.e. mpv).
     */
    private fun startAutoSkipMediaSegmentUpdates() {
        autoSkipMediaSegmentUpdateJob?.cancel()
        autoSkipMediaSegmentUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY)
                playerOrNull?.performAutoSkipMediaSegments()
            }
        }
    }

    private fun stopAutoSkipMediaSegmentUpdates() {
        autoSkipMediaSegmentUpdateJob?.cancel()
        autoSkipMediaSegmentUpdateJob = null
    }

    /**
     * Updates the decoder of the [Player]. This will destroy the current player and
     * recreate the player with the selected decoder type
     */
    fun updateDecoderType(type: DecoderType) {
        _decoderType.value = type
        rebuildPlayerAndRestart()
    }

    private suspend fun Player.reportPlaybackStart(mediaSource: RemoteJellyfinMediaSource) {
        try {
            val isPaused = !isPlaying
            withContext(Dispatchers.IO) {
                playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = mediaSource.itemId,
                        playMethod = mediaSource.playMethod,
                        playSessionId = mediaSource.playSessionId,
                        liveStreamId = mediaSource.liveStreamId,
                        audioStreamIndex = mediaSource.selectedAudioStream?.index,
                        subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = mediaSource.startTime.inWholeTicks,
                        volumeLevel = audioManager.getVolumeLevelPercent(),
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to report playback start")
        }
    }

    /**
     * 队列项切换后由 Compose 层调用：按当前 mediaSource 重建章节标记与跳过按钮状态。
     */
    fun refreshPlayerMenuState() {
        playerOrNull?.setWatchedChapterMarkings()
        playerOrNull?.updateSkipMediaSegmentButton()
    }

    private fun Player.setWatchedChapterMarkings() {
        val playbackPosition = currentPosition.milliseconds
        val chapters = mediaSourceOrNull?.item?.chapters
        if (chapters.isNullOrEmpty()) {
            _chapterMarkings.value = emptyList()
            return
        }
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        val runTimeTicks = mediaSourceOrNull?.item?.runTimeTicks
        _chapterMarkings.value = chapters.mapIndexed { index, chapter ->
            ChapterMarkingState(
                bias = if (runTimeTicks != null && runTimeTicks > 0) {
                    (chapter.startPositionTicks.toFloat() / runTimeTicks).coerceIn(0f, 1f)
                } else {
                    0f
                },
                watched = playbackPosition >= startPositions[index],
            )
        }
    }

    /**
     * Auto-skips intro/outro segments for players without ExoPlayer's scheduled-message
     * support (i.e. mpv). Each segment is skipped at most once per loaded item; the
     * registry is reset whenever a new media source is applied.
     */
    private fun Player.performAutoSkipMediaSegments() {
        val segments = autoSkipMediaSegments
        if (segments.isEmpty()) return

        val positionMs = currentPosition
        // A backward jump beyond what normal playback progress can explain means the user
        // rewound (e.g. back into the skipped intro); allow the segments to skip again.
        if (lastAutoSkipPositionMs >= 0 && positionMs < lastAutoSkipPositionMs - Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY) {
            skippedAutoSegments.clear()
        }
        lastAutoSkipPositionMs = positionMs

        val playbackPosition = positionMs.milliseconds
        val segmentToSkip = segments.firstOrNull { seg ->
            playbackPosition >= seg.start && playbackPosition < seg.end && seg !in skippedAutoSegments
        } ?: return

        skippedAutoSegments += segmentToSkip
        seekTo(segmentToSkip.end.inWholeMilliseconds)
    }

    private fun Player.updateSkipMediaSegmentButton() {
        val mediaSegments = askToSkipMediaSegments
        if (mediaSegments.isEmpty()) {
            _activeAskToSkipSegment.value = null
            return
        }

        val playbackPosition = currentPosition.milliseconds
        val currentMediaSegment = mediaSegments.find { seg -> playbackPosition in seg.start..seg.end }
        _activeAskToSkipSegment.value = currentMediaSegment
    }

    private suspend fun Player.reportPlaybackState() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val playbackPosition = currentPosition.milliseconds
        if (playbackState != Player.STATE_ENDED) {
            val stream = AudioManager.STREAM_MUSIC
            val volumeRange = audioManager.getVolumeRange(stream)
            val currentVolume = audioManager.getStreamVolume(stream)
            val isPaused = !isPlaying
            try {
                withContext(Dispatchers.IO) {
                    playStateApi.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = mediaSource.itemId,
                            playMethod = mediaSource.playMethod,
                            playSessionId = mediaSource.playSessionId,
                            liveStreamId = mediaSource.liveStreamId,
                            audioStreamIndex = mediaSource.selectedAudioStream?.index,
                            subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                            isPaused = isPaused,
                            isMuted = false,
                            canSeek = true,
                            positionTicks = playbackPosition.inWholeTicks,
                            volumeLevel = (currentVolume - volumeRange.first) * Constants.PERCENT_MAX / volumeRange.width,
                            repeatMode = RepeatMode.REPEAT_NONE,
                            playbackOrder = PlaybackOrder.DEFAULT,
                        ),
                    )
                }
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback progress")
            }
        }
    }

    private fun reportPlaybackStop() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val player = playerOrNull ?: return
        val hasFinished = player.playbackState == Player.STATE_ENDED
        val lastPositionTicks = when {
            hasFinished -> mediaSource.runTime.inWholeTicks
            else -> player.currentPosition.milliseconds.inWholeTicks
        }

        // viewModelScope may already be cancelled at this point, so we need to fallback
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Report stopped playback
                withContext(Dispatchers.IO) {
                    playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = mediaSource.itemId,
                            positionTicks = lastPositionTicks,
                            playSessionId = mediaSource.playSessionId,
                            liveStreamId = mediaSource.liveStreamId,
                            failed = false,
                        ),
                    )
                }

                // Mark video as watched if playback finished
                if (hasFinished) {
                    withContext(Dispatchers.IO) {
                        playStateApi.markPlayedItem(itemId = mediaSource.itemId)
                    }
                }

                // Stop active encoding if transcoding
                stopTranscoding(mediaSource)
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback stop")
            }
        }
    }

    suspend fun stopTranscoding(mediaSource: RemoteJellyfinMediaSource) {
        if (mediaSource.playMethod == PlayMethod.TRANSCODE) {
            withContext(Dispatchers.IO) {
                hlsSegmentApi.stopEncodingProcess(
                    deviceId = apiClient.deviceInfo.id,
                    playSessionId = mediaSource.playSessionId,
                )
            }
        }
    }

    private fun applyMediaSegments(jellyfinMediaSource: JellyfinMediaSource) {
        askToSkipMediaSegments = emptyList()
        autoSkipMediaSegments = emptyList()
        skippedAutoSegments.clear()
        lastAutoSkipPositionMs = -1L
        stopAutoSkipMediaSegmentUpdates()
        _activeAskToSkipSegment.value = null

        viewModelScope.launch {
            if (jellyfinMediaSource.item != null) {
                val mediaSegments = mediaSegmentRepository.getSegmentsForItem(jellyfinMediaSource.item)
                val newAskToSkipMediaSegments = mutableListOf<MediaSegmentDto>()

                for (mediaSegment in mediaSegments) {
                    val action = mediaSegmentRepository.getMediaSegmentAction(mediaSegment)

                    when (action) {
                        MediaSegmentAction.SKIP -> {
                            autoSkipMediaSegments += mediaSegment
                            addSkipAction(mediaSegment)
                        }
                        MediaSegmentAction.ASK_TO_SKIP -> newAskToSkipMediaSegments.add(mediaSegment)
                        MediaSegmentAction.NOTHING -> Unit
                    }
                }

                askToSkipMediaSegments = newAskToSkipMediaSegments

                // ExoPlayer skips via the scheduled messages sent in addSkipAction; mpv has no
                // equivalent message bus, so poll the position to trigger the same auto-skips.
                // The player may already be READY (and thus past onPlayerStateChanged) by the time
                // the segments resolve, so start the watcher here too.
                if (autoSkipMediaSegments.isNotEmpty() &&
                    playerOrNull !is ExoPlayer &&
                    playerOrNull?.isPlaying == true
                ) {
                    startAutoSkipMediaSegmentUpdates()
                }
            }
        }
    }

    private fun addSkipAction(mediaSegment: MediaSegmentDto) {
        val player = playerOrNull ?: return
        if (player is ExoPlayer){
            player
                .createMessage { _, _ ->
                    viewModelScope.launch(Dispatchers.Main) {
                        player.seekTo(mediaSegment.end.inWholeMilliseconds)
                    }
                }
                // Segments at position 0 will never be hit by ExoPlayer so we need to add a minimum value
                .setPosition(mediaSegment.start.inWholeMilliseconds.coerceAtLeast(1))
                .setDeleteAfterDelivery(false)
                .send()
        }
    }

    // Player controls
    fun play() {
        playerOrNull?.play()
    }

    fun pause() {
        playerOrNull?.pause()
    }

    fun rewind() {
        playerOrNull?.seekToOffset(displayPreferences.skipBackLength.unaryMinus())
    }

    fun fastForward() {
        playerOrNull?.seekToOffset(displayPreferences.skipForwardLength)
    }

    fun seekTo(positionMs: Long) {
        val player = playerOrNull ?: return
        val durationMs = player.duration
        val clamped = if (durationMs != C.TIME_UNSET && durationMs > 0) {
            positionMs.coerceIn(0, durationMs)
        } else {
            positionMs.coerceAtLeast(0)
        }
        player.seekTo(clamped)
    }

    private fun getCurrentChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        return startPositions.findLast { pos -> playbackPosition >= pos }
    }

    private fun getNextChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        val currentChapterIdx = startPositions.indexOfLast { pos -> playbackPosition >= pos }
        if (currentChapterIdx == -1) return null
        val nextChapterIndex = currentChapterIdx + 1
        return startPositions.getOrElse(nextChapterIndex) { _ -> Duration.INFINITE }
    }

    fun previousChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return

        // Update the playback position to be slightly in the past, to check if we should go back to the beginning of the current
        // chapter or the previous one, if not enough time has elapsed since the start of the current chapter
        val skipToPreviousDuration = Constants.MAX_SKIP_TO_PREV_CHAPTER_MS.milliseconds
        val playbackPosition = currentPosition - skipToPreviousDuration
        // If we'd end up with a negative position then we need to play the previous item
        if (playbackPosition < Duration.ZERO) {
            skipToPrevious()
        } else {
            val seekToPosition = getCurrentChapterStartPosition(chapters, playbackPosition) ?: return
            playerOrNull?.seekTo(seekToPosition.inWholeMilliseconds)
        }
    }

    fun nextChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return
        val playbackPosition = getNextChapterStartPosition(chapters, currentPosition) ?: return

        if (playbackPosition == Duration.INFINITE) {
            skipToNext()
        } else {
            playerOrNull?.seekTo(playbackPosition.inWholeMilliseconds)
        }
    }

    fun skipToPrevious() {
        val player = playerOrNull ?: return
        when {
            // Skip to previous element
            player.currentPosition <= Constants.MAX_SKIP_TO_PREV_MS -> viewModelScope.launch {
                pause()
                if (!queueManager.previous()) {
                    // Skip to previous failed, go to start of video anyway
                    playerOrNull?.seekTo(0)
                    play()
                }
            }
            // Rewind to start of track if not at the start already
            else -> player.seekTo(0)
        }
    }

    /**
     * Switch to the queue item at [index], e.g. an episode picked from the episode list.
     */
    fun selectQueueItem(index: Int) {
        viewModelScope.launch {
            queueManager.jumpTo(index)
        }
    }

    fun skipToNext() {
        viewModelScope.launch {
            queueManager.next()
        }
    }

    fun skipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        val player = playerOrNull ?: return
        val mediaSegment = mediaSegmentDto ?: return
        player.seekTo(mediaSegment.end.inWholeMilliseconds + 1)
    }

    fun getStateAndPause(): PlayState? {
        val player = playerOrNull ?: return null

        val playWhenReady = player.playWhenReady
        player.pause()
        val position = player.contentPosition.milliseconds

        return PlayState(playWhenReady, position)
    }

    fun logTracks() {
        playerOrNull?.logTracks(analyticsCollector)
    }

    suspend fun changeBitrate(bitrate: Int?): Boolean {
        return queueManager.changeBitrate(bitrate)
    }

    /**
     * Set the playback speed to [speed]
     *
     * @return true if the speed was changed
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        val player = playerOrNull ?: return false

        val parameters = player.playbackParameters
        if (parameters.speed != speed) {
            player.playbackParameters = parameters.withSpeed(speed)
            return true
        }
        return false
    }

    fun setPressSpeedUp(isPressing: Boolean, speed: Float): Boolean {
        if (!isPressing) {
            return setPlaybackSpeed(playSpeed)
        }
        val player = playerOrNull ?: return false
        val parameters = player.playbackParameters
        playSpeed = parameters.speed
        return setPlaybackSpeed(speed)
    }

    /**
     * Called when the user leaves the player screen (back button, closing the player
     * overlay, or navigating away). Web playback is handed back to the web app; the native
     * player is only stopped when the web app does not keep playing in the background
     * (music playback, or Cast). Without this the player - kept alive by the foreground
     * service - would keep playing after the screen is closed.
     */
    fun onPlayerClosed() {
        if (!keepPlaybackOnExit()) {
            endPlayback()
            return
        }

        // Background playback keeps the engine (and its audio) alive, but the *session* is
        // still over: the screen is gone, and the next session must not inherit this one's
        // bookkeeping.
        //
        // Skipping this is what made "start playback, leave the player, then play again"
        // need two attempts: the retained player kept the previous media item and the
        // [closedByUser] latch from [pauseInBackground], so the first play() of the new
        // session was immediately paused again and only the second one stuck. Clearing the
        // latch here (rather than only in [rebuildPlayer], which is a no-op while a player
        // exists) is what makes a retained player start cleanly.
        resetSessionState()
    }

    /**
     * Called when the app goes to the background. Pauses the native playback unless the user
     * enabled background playback or the web app keeps playing on a remote device.
     */
    fun pauseInBackground() {
        if (backgroundPlaybackAllowed) return
        // Latch the user-initiated pause so the audio listener does not try to re-acquire
        // audio focus and restart playback on the next foreground transition.
        closedByUser = true
        pause()
    }

    /**
     * Keeps the native playback alive when the player screen (or the app) is left: either the
     * user enabled background playback, or the web app is casting to a remote device, which
     * keeps its own session and must not be interrupted.
     */
    fun keepPlaybackOnExit(): Boolean = backgroundPlaybackAllowed

    /**
     * Stops playback, whichever engine is set up.
     *
     * mpv keeps its audio track alive when it is merely paused (media3's audio focus handling
     * only exists on the ExoPlayer side), so both engines are stopped at the player API level
     * rather than relying on pause() having an effect on the audio output.
     */
    fun stop() {
        playerOrNull?.stop()
    }

    /**
     * Ends the current playback session for good: stops the engine, reports the stop to the
     * server and tears the player down, so the next session starts from a clean state.
     */
    fun endPlayback() {
        // The session state is dropped even when there is no player left to tear down:
        // playback may have ended on its own ([onPlayerStateChanged]) between the screen's
        // last check and this call.
        if (playerOrNull != null) {
            stop()
            reportPlaybackStop()
            releasePlayer()
        }
        resetSessionState()
    }

    /**
     * Drops the per-session state a *new* playback session must not inherit.
     *
     * Called both when the player is torn down ([endPlayback]) and when it is deliberately
     * kept alive for background playback ([onPlayerClosed]): the player may outlive the
     * screen, but the session bookkeeping must not.
     *
     * - [closedByUser]: otherwise the next play() is paused again as soon as it starts
     *   playing (see [onIsPlayingChanged]), which reads as "the first click does nothing";
     * - the current media source: its `startTime` is mutated while seeking, so keeping it
     *   around makes the next session resume where the previous one stopped;
     * - the initial-track latch: the reused player has to re-run track selection for the
     *   new media source.
     */
    private fun resetSessionState() {
        closedByUser = false
        initialTracksSelected.set(false)
        queueManager.resetCurrentMediaSource()
    }

    private fun setVolume(percent: Int) {
        if (audioManager.isVolumeFixed) return
        val stream = AudioManager.STREAM_MUSIC
        val volumeRange = audioManager.getVolumeRange(stream)
        val scaled = volumeRange.scaleInRange(percent)
        audioManager.setStreamVolume(stream, scaled, 0)
    }

    @Suppress("DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        val player = playerOrNull ?: return

        // Notify fragment of current state
        _playerState.value = playbackState

        // Initialise various components
        if (playbackState == Player.STATE_READY) {
            if (!initialTracksSelected.getAndSet(true)) {
                trackSelectionHelper.selectInitialTracks()
            }
            mediaSession.isActive = true
            notificationHelper.postNotification()
        }

        // Setup or stop regular progress updates
        if (playbackState == Player.STATE_READY && playWhenReady) {
            startProgressUpdates()
            if (!mediaSourceOrNull?.item?.chapters.isNullOrEmpty()) {
                startChapterMarkingUpdates()
            }
            if (askToSkipMediaSegments.isNotEmpty()) {
                startSkipMediaSegmentUpdates()
            }
            if (autoSkipMediaSegments.isNotEmpty() && player !is ExoPlayer) {
                startAutoSkipMediaSegmentUpdates()
            }
        } else {
            stopProgressUpdates()
            stopChapterMarkingUpdates()
            stopSkipMediaSegmentUpdates()
            stopAutoSkipMediaSegmentUpdates()
        }

        // Update media session
        var playbackActions = SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
        if (queueManager.hasPrevious()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        if (queueManager.hasNext()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        mediaSession.setPlaybackState(player, playbackActions)

        // Force update playback state and position
        viewModelScope.launch {
            when (playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    player.reportPlaybackState()
                }
                Player.STATE_ENDED -> {
                    reportPlaybackStop()
                    if (!autoPlayNextEpisodeEnabled || !queueManager.next()) {
                        // Nothing else to play: end the session so the screen closes and the
                        // next one starts from a clean state.
                        releasePlayer()
                        queueManager.resetCurrentMediaSource()
                    }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        playerOrNull?.setWatchedChapterMarkings()
        playerOrNull?.updateSkipMediaSegmentButton()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // A player that restarted by itself after the app was sent to the background (the
        // audio focus coming back is what mpv reacts to; ExoPlayer has
        // setHandleAudioBecomingNoisy(true)) must not keep playing behind the UI. Leaving the
        // player screen tears the player down outright (see [endPlayback]), so this only
        // catches a resurrection while the session is still alive.
        if (isPlaying && closedByUser) pause()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.cause is MediaCodecDecoderException && !fallbackPreferExtensionRenderers) {
            Timber.e(error.cause, "Decoder failed, attempting to restart playback with decoder extensions preferred")
            // Release the old player *before* rebuilding: [rebuildPlayer] reuses an existing
            // player, which would otherwise leave the failing one in place.
            releasePlayerForRebuild()
            rebuildPlayer(preferExtensionRenderers = true, restartPlayback = true)
        } else {
            Timber.w(error, "Playback error, attempting fallback")
            val startPosition = (playerOrNull?.currentPosition ?: 0L).milliseconds
            fallbackRetryJob?.cancel()
            fallbackRetryJob = viewModelScope.launch {
                val retried = queueManager.restartPlaybackWithFallback(startPosition)
                if (!retried) {
                    _error.postValue(error.localizedMessage.orEmpty())
                }
            }
        }
    }

    fun cancelFallbackRetry() {
        fallbackRetryJob?.cancel()
        fallbackRetryJob = null
    }

    override fun onCleared() {
        reportPlaybackStop()
        releasePlayer()
        _mediaSession?.release()
        _mediaSession = null
    }
}
