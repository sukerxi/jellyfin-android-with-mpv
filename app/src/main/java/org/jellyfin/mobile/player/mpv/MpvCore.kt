package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.app.AppPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Events emitted by [MpvCore]. Observers always run on the main thread.
 */
sealed interface MpvEvent {
    data object StartFile : MpvEvent
    data object FileLoaded : MpvEvent
    data object EndFile : MpvEvent
    data object PlaybackRestart : MpvEvent
    data object Seek : MpvEvent

    /** Playback was paused (true) or resumed (false) while waiting for the cache to fill. */
    data class Caching(val isCaching: Boolean) : MpvEvent

    /**
     * Playback reached (true) or left (false) end-of-file.
     * With `keep-open=yes`, mpv stays on the last frame instead of unloading the file,
     * so natural playback completion is reported through this property rather than [EndFile].
     */
    data class EofReached(val isEofReached: Boolean) : MpvEvent

    /** Media duration changed, e.g. when it becomes known only after loading starts (HLS). */
    data class DurationChanged(val durationMs: Long) : MpvEvent

    data object DecoderChanged : MpvEvent
    data object TrackListChanged : MpvEvent

    /**
     * A hot property served to [MpvPlayer.getState] from the cache (currently `pause`/`speed`)
     * changed on mpv's side, e.g. an external pause. Only used to trigger a state refresh; the
     * value itself is already in the cache.
     */
    data object HotPropertyChanged : MpvEvent
}

/**
 * Installs the bundled mpv assets (e.g. `subfont.ttf`) from `assets/mpv` into the
 * directory passed to mpv as `config-dir`.
 *
 * The potentially multi-megabyte copy runs on a background thread started from
 * `Application.onCreate`; [awaitInstalled] blocks the mpv initialization path until it
 * has finished. Assets are re-copied (and stale files removed) whenever the app version
 * code changes, so shipped updates are never shadowed by files copied by an older version.
 */
object MpvAssetInstaller {
    private const val ASSET_DIR = "mpv"
    private const val PREFS_NAME = "mpv_assets"
    private const val KEY_VERSION = "version"
    private const val KEY_FILES = "files"

    private val latch = CountDownLatch(1)

    @Volatile
    private var started = false

    /**
     * Starts asset installation on a background thread. Safe to call once from
     * `Application.onCreate`; subsequent calls are no-ops.
     */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            val appContext = context.applicationContext
            Thread {
                install(appContext)
            }.apply {
                name = "mpv-asset-installer"
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Blocks until installation has finished. If [start] was never called, the
     * installation is started (on a background thread) and awaited as well.
     */
    fun awaitInstalled(context: Context) {
        if (!started) start(context)
        latch.await()
    }

    private fun install(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val versionCode = currentVersionCode(context)
            if (prefs.getLong(KEY_VERSION, -1L) != versionCode) {
                val targetDir = context.filesDir
                val copiedFiles = copyAssetDir(context.assets, ASSET_DIR, targetDir, targetDir, mutableSetOf())
                removeStaleFiles(targetDir, prefs.getStringSet(KEY_FILES, null).orEmpty(), copiedFiles)
                prefs.edit()
                    .putLong(KEY_VERSION, versionCode)
                    // SharedPreferences may mutate the set in place on some platforms, so hand it a copy.
                    .putStringSet(KEY_FILES, copiedFiles.toHashSet())
                    .apply()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to install mpv assets")
        } finally {
            latch.countDown()
        }
    }

    /**
     * Recursively copies [assetDir] from the APK assets into [outDir].
     * Asset directories are detected via a non-empty [AssetManager.list] result,
     * since `AssetManager` offers no explicit way to distinguish files from folders.
     *
     * @return relative paths (against [rootDir]) of all copied files.
     */
    private fun copyAssetDir(
        assets: AssetManager,
        assetDir: String,
        outDir: File,
        rootDir: File,
        copiedFiles: MutableSet<String>,
    ): Set<String> {
        outDir.mkdirs()
        val entries = assets.list(assetDir).orEmpty()
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val children = assets.list(assetPath)
            if (!children.isNullOrEmpty()) {
                copyAssetDir(assets, assetPath, File(outDir, entry), rootDir, copiedFiles)
            } else {
                val target = File(outDir, entry)
                target.parentFile?.mkdirs()
                val tempFile = File(outDir, ".$entry.tmp")
                assets.open(assetPath).use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                // Move into place atomically so an interrupted copy never leaves a half-written font.
                if (!tempFile.renameTo(target)) {
                    target.delete()
                    check(tempFile.renameTo(target)) { "Failed to install asset $assetPath" }
                }
                copiedFiles += target.relativeTo(rootDir).invariantSeparatorsPath
            }
        }
        return copiedFiles
    }

    /** Deletes files copied by a previous app version that are no longer shipped. */
    private fun removeStaleFiles(rootDir: File, previousFiles: Set<String>, currentFiles: Set<String>) {
        for (relativePath in previousFiles - currentFiles) {
            File(rootDir, relativePath).delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }
}

/**
 * Thin wrapper around the native mpv player. Exposes a type-safe event stream via [MpvEvent].
 *
 * @author dr
 */
class MpvCore private constructor(context: Application) : KoinComponent {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The preference singleton, injected instead of constructing a second
     * [AppPreferences] here: its constructor reads the DataStore from disk, so a second
     * instance would pay for that read again on whichever thread creates it.
     */
    private val appPreferences: AppPreferences by inject()

    /**
     * Native mpv instance. libmpv-android 1.0.0 replaced the static MPVLib API with
     * multi-instance support, so every call goes through this handle.
     *
     * Created by the initialization task on the [ipcExecutor] thread and null until
     * that task has run. Every IPC call is queued behind it (see [ipcExecutor]), so
     * callers never observe the null window.
     */
    @Volatile
    private var mpv: MPVLib? = null

    private fun postEvent(event: MpvEvent) {
        mainHandler.post {
            for (listener in eventListeners) listener(event)
        }
    }

    private val mpvLibEventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            // Nothing to cache without a value; every property observed with a format below
            // arrives through the typed overloads instead.
        }

        override fun eventProperty(property: String, value: Long) {}

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "duration" -> {
                    cachedDurationMs = (value * 1000).toLong()
                    postEvent(MpvEvent.DurationChanged(cachedDurationMs))
                }
                // Hot properties are cached here, off the main thread: MpvPlayer.getState() reads
                // them from the cache instead of blocking the UI thread on a synchronous
                // mpv_get_property while mpv's core is busy (e.g. during initial load).
                "time-pos" -> cachedTimePosMs = (value * 1000).toLong()
                "demuxer-cache-time" -> cachedDemuxerCacheMs = (value * 1000).toLong()
                "speed" -> if (cachedSpeed != value) {
                    cachedSpeed = value
                    postEvent(MpvEvent.HotPropertyChanged)
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "paused-for-cache" -> postEvent(MpvEvent.Caching(value))
                "eof-reached" -> {
                    cachedEofReached = value
                    postEvent(MpvEvent.EofReached(value))
                }
                "pause" -> if (cachedPause != value) {
                    cachedPause = value
                    postEvent(MpvEvent.HotPropertyChanged)
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                // Cached as raw JSON and parsed on demand (see getTracks): the parse is pure
                // CPU, whereas the old synchronous property read could block on mpv's core
                // lock while the demuxer/decoder were busy - during the initial load that
                // stalled the UI thread long enough to stutter the loading spinner.
                "track-list" -> {
                    cachedTrackListJson = value
                    postEvent(MpvEvent.TrackListChanged)
                }
                "hwdec-current" -> {
                    cachedHwdecCurrent = value
                    postEvent(MpvEvent.DecoderChanged)
                }
            }
        }

        override fun event(eventId: Int) {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE) {
                // A new file starts at position 0 (or its start offset): drop the previous file's
                // cached position so the reported position never briefly shows the old value.
                cachedTimePosMs = 0L
                cachedDemuxerCacheMs = 0L
                // Same for the per-file values below: a new file must not report the previous
                // one's duration/tracks/eof state until mpv has learned the new values (the
                // change events deliver them shortly after FILE_LOADED).
                cachedDurationMs = 0L
                cachedTrackListJson = null
                cachedEofReached = false
            }
            val event = when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> MpvEvent.StartFile
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> MpvEvent.FileLoaded
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> MpvEvent.EndFile
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> MpvEvent.PlaybackRestart
                MPVLib.MpvEvent.MPV_EVENT_SEEK -> MpvEvent.Seek
                else -> return
            }
            postEvent(event)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val eventListeners: CopyOnWriteArrayList<(MpvEvent) -> Unit> = CopyOnWriteArrayList()

        /**
         * Serial executor for every libmpv client call: the native initialization,
         * property sets, commands and surface attach/detach.
         *
         * All synchronous libmpv client calls (`mpv_set_property`, `mpv_command`,
         * `mpv_attach_surface`) acquire mpv's dispatch lock: `mp_dispatch_lock` blocks
         * the caller until mpv's core reaches a safe point - inside its dispatch loop
         * and not running an item. While mpv is busy - opening the demuxer, filling the
         * initial cache (`cache-pause-initial=yes`), or initializing the decoder for
         * the first frame - the core does not reach safe points for tens to hundreds
         * of milliseconds, so issuing IPC from the main thread stalls the UI thread
         * exactly during the loading phase. Seeking is unaffected (during playback the
         * core passes a safe point every frame), which is why only the loading spinner
         * stuttered.
         *
         * IPC is therefore queued here instead of running on the caller's thread: a
         * single thread keeps the relative order of the calls, and callers never wait
         * for mpv. The hot-property cache in [setProperty] is still updated
         * synchronously, so [MpvPlayer.getState] reports new values without waiting.
         *
         * The native initialization (see the `init` block) is queued as the first task,
         * so it - and the disk I/O and preference read it performs - runs off the main
         * thread while every later call is still ordered behind it.
         */
        private val ipcExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-ipc").apply { isDaemon = true }
        }

        /**
         * Cache for the properties [MpvPlayer.getState] needs on the animation-critical main
         * thread (`pause`, `speed`, playback position, buffered position). media3's
         * [SimpleBasePlayer] requires [MpvPlayer.getState] to be a cheap, synchronous read, but a
         * synchronous `mpv_get_property` can block on mpv's core lock while the core is busy -
         * notably during initial load (demuxer open / cache fill), where it stalled the UI thread
         * long enough to visibly stutter the loading spinner. The values are therefore maintained
         * here instead: updated off the main thread by the property observer, and synchronously
         * by [setProperty] for the values the app sets itself.
         */
        @Volatile
        private var cachedPause: Boolean = true

        @Volatile
        private var cachedSpeed: Double = 1.0

        @Volatile
        private var cachedTimePosMs: Long = 0L

        @Volatile
        private var cachedDemuxerCacheMs: Long = 0L

        /**
         * Raw `track-list` JSON as delivered by the property observer, parsed on demand by
         * [getTracks]. Null while no file is loaded (reset on START_FILE).
         */
        @Volatile
        private var cachedTrackListJson: String? = null

        /** Duration in ms as delivered by the property observer; 0 while unknown. */
        @Volatile
        private var cachedDurationMs: Long = 0L

        /** Current hwdec backend as delivered by the property observer, e.g. "mediacodec". */
        @Volatile
        private var cachedHwdecCurrent: String? = null

        /** Whether playback reached end-of-file, as delivered by the property observer. */
        @Volatile
        private var cachedEofReached: Boolean = false

        /** Whether playback is paused, from the cache (never blocks). */
        val pause: Boolean get() = cachedPause

        /** Playback speed, from the cache (never blocks). */
        val speed: Double get() = cachedSpeed

        /** Current playback position in ms, from the cache (never blocks). */
        val timePosMs: Long get() = cachedTimePosMs

        /** Buffered (demuxer cache) position in ms, from the cache (never blocks). */
        val demuxerCacheMs: Long get() = cachedDemuxerCacheMs

        /** Duration in ms, from the cache (never blocks); 0 while unknown. */
        val durationMs: Long get() = cachedDurationMs

        /** Current hwdec backend, from the cache (never blocks); null while unknown. */
        val hwdecCurrent: String? get() = cachedHwdecCurrent

        /** Whether playback reached end-of-file, from the cache (never blocks). */
        val eofReached: Boolean get() = cachedEofReached

        @Volatile
        private var INSTANCE: MpvCore? = null

        fun initialize(application: Application): MpvCore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MpvCore(application).also { INSTANCE = it }
            }
        }

        /**
         * Since libmpv-android 1.0.0 [MPVLib] is a multi-instance class. The singleton
         * [MpvCore] owns the only instance, which these static-style helpers delegate to,
         * keeping the call sites unchanged.
         */
        @PublishedApi
        internal val mpvLib: MPVLib
            get() = INSTANCE?.mpv ?: error("MpvCore is not initialized")

        // JNI calls into libmpv can surface arbitrary failures; on the background IPC thread
        // they must not take the process down, so they are logged here.
        @Suppress("TooGenericExceptionCaught")
        fun command(cmd: Array<String>) {
            // Queued to the mpv-ipc thread: the synchronous call blocks on mpv's core
            // lock, and the main thread must not wait for that during load (see ipcExecutor).
            ipcExecutor.execute {
                try {
                    mpvLib.command(cmd)
                } catch (e: Exception) {
                    Timber.e(e, "mpv command failed: ${cmd.joinToString(" ")}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun setProperty(name: String, value: Any) {
            // Keep the hot-property cache in sync for values the app sets itself, so a getState()
            // right after (media3 does one per command) already reports the new value instead of
            // waiting for the observer on mpv's thread.
            when (name) {
                "pause" -> if (value is Boolean) cachedPause = value
                "speed" -> if (value is Double) cachedSpeed = value
            }
            // The IPC itself runs on the mpv-ipc thread (see ipcExecutor): setting a property
            // blocks until mpv's core reaches a safe point, and during the initial load that
            // stalled the UI thread long enough to stutter the loading spinner.
            ipcExecutor.execute {
                try {
                    val mpv = mpvLib
                    when (value) {
                        is String -> mpv.setPropertyString(name, value)
                        is Int -> mpv.setPropertyInt(name, value)
                        is Double -> mpv.setPropertyDouble(name, value)
                        is Boolean -> mpv.setPropertyBoolean(name, value)
                        is Float -> mpv.setPropertyDouble(name, value.toDouble())
                        is Long -> mpv.setPropertyInt(name, value.toInt())
                        else -> throw IllegalArgumentException("Unsupported property type: ${value::class}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "mpv setProperty failed: $name = $value")
                }
            }
        }

        fun subscribe(eventListener: (MpvEvent) -> Unit) {
            eventListeners.add(eventListener)
        }

        fun unsubscribe(eventListener: (MpvEvent) -> Unit) {
            eventListeners.remove(eventListener)
        }

        // Queued like every other libmpv call (see ipcExecutor): the callers are the
        // SurfaceView holder callbacks on the main thread, and attaching/detaching
        // reaches into mpv (it can block on the core lock during load). Queueing also
        // keeps the ordering against the vid=auto/vid=no property sets that surround
        // these calls in MpvPlayer.
        @Suppress("TooGenericExceptionCaught")
        fun attachSurface(surface: Surface) {
            ipcExecutor.execute {
                try {
                    mpvLib.attachSurface(surface)
                } catch (e: Exception) {
                    Timber.e(e, "mpv attachSurface failed")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun detachSurface() {
            ipcExecutor.execute {
                try {
                    mpvLib.detachSurface()
                } catch (e: Exception) {
                    Timber.e(e, "mpv detachSurface failed")
                }
            }
        }

        fun getTracks(): List<MediaTrack> {
            // Parsed from the JSON cached by the property observer (delivered with the change
            // event on mpv's thread). Never read the property synchronously here: getTracks
            // runs on the main thread from the FILE_LOADED / TRACK_LIST_CHANGED handlers,
            // and mpv_get_property can block on the core lock while the demuxer is busy -
            // during the initial load that stalled the UI thread long enough to stutter the
            // loading spinner. Parsing the cached string is pure CPU and touches no locks.
            val trackList = cachedTrackListJson ?: return emptyList()
            return json.decodeFromString(trackList)
        }
    }

    init {
        // The whole native initialization runs as the first task on the IPC executor
        // (see [ipcExecutor]): it waits for the asset copy, reads the DataStore-backed
        // preferences, writes mpv.conf and creates/initializes the native instance -
        // disk I/O plus a native handshake that must not block the main thread while
        // the player is being set up. Every later IPC call is queued behind it, so mpv
        // is fully initialized by the time any command runs.
        ipcExecutor.execute { initializeNativeAsync(context) }
    }

    /**
     * Runs the native initialization on the IPC executor, logging a failure instead of
     * killing the process: an uncaught exception on that thread would take the app
     * down, while a failed init only leaves playback unavailable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun initializeNativeAsync(context: Application) {
        try {
            initializeNative(context)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize mpv")
        }
    }

    /**
     * Creates and initializes the native mpv instance. Runs on the [ipcExecutor] thread
     * as its first task, so constructing [MpvCore] never blocks its caller.
     */
    private fun initializeNative(context: Application) {
        // Wait for assets (e.g. subfont.ttf) to be copied before mpv reads its config directory.
        MpvAssetInstaller.awaitInstalled(context)
        // Publish the user-provided mpv.conf so that mpv picks it up during init().
        MpvConfigManager.syncConfigFile(context, appPreferences.mpvCustomConfig)
        val mpv = MPVLib.create(context) ?: error("Failed to create native mpv instance")
        this.mpv = mpv
        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        val cacheBytes = "${cacheMegs * 1024 * 1024}"
        listOf(
            "config" to "yes",
            "config-dir" to context.filesDir.path,
            "profile" to "fast",
            "hwdec" to "auto",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
            "gpu-context" to "android", // auto
            "opengl-es" to "yes",
            "ao" to "audiotrack,opensles",
            "input-default-bindings" to "yes",
            "demuxer-max-bytes" to cacheBytes,
            "demuxer-max-back-bytes" to cacheBytes,
            "vd-lavc-film-grain" to "cpu",
            "ytdl" to "no",
            "cache-pause-initial" to "yes",
            "vo" to "gpu_next,gpu",
            "save-position-on-quit" to "no",
            // Keep the file loaded and pause on the last frame at EOF. Without this, seeking
            // to (or past) the end unloads the file and playback can no longer be revived by
            // seeking back, and natural EOF is indistinguishable from an overshooting seek.
            "keep-open" to "yes",
        ).forEach { (name, value) -> mpv.setOptionString(name, value) }
        mpv.init()

//        mpv.setOptionString("idle", "once")
        // mpv.setOptionString("force-window", "yes")
        observeProperties(mpv)
        mpv.addObserver(mpvLibEventObserver)
    }

    private fun observeProperties(mpv: MPVLib) {
        // This observes all properties needed by MpvPlayer or other classes
        data class Property(val name: String, val format: Int = MPVLib.MpvFormat.MPV_FORMAT_NONE)
        val properties = arrayOf(
            Property("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            // Observed with their format so the observer receives the value with the change
            // event (see the cached values in the companion): reading these synchronously
            // from the main-thread event handlers could block on mpv's busy core lock.
            Property("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING),
            Property("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            // Hot properties cached for MpvPlayer.getState(); never read synchronously on the
            // main thread (see the cache in the companion).
            Property("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("track-list", MPVLib.MpvFormat.MPV_FORMAT_STRING),
            // Property("duration/full", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            // Property("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            // Property("video-params/rotate", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            // Property("playlist-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            // Property("playlist-count", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            // Property("current-tracks/video/image"),
            // Property("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING),
            // Property("metadata"),
            // Property("loop-playlist"),
            // Property("loop-file"),
            // Property("shuffle", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            // Property("mute", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            // Property("current-tracks/audio/selected"),
        )
        for ((name, format) in properties) {
            mpv.observeProperty(name, format)
        }
    }

    @Serializable
    data class MediaTrack(
        @SerialName("id") val id: Long = -1L,
        @SerialName("type") val type: String = "",
        @SerialName("src-id") val srcId: Long = -1L,
        @SerialName("title") val title: String = "",
        @SerialName("lang") val lang: String = "",
        @SerialName("image") val image: Boolean = false,
        @SerialName("albumart") val albumart: Boolean = false,
        @SerialName("default") val isDefault: Boolean = false,
        @SerialName("forced") val forced: Boolean = false,
        @SerialName("dependent") val dependent: Boolean = false,
        @SerialName("visual-impaired") val visualImpaired: Boolean = false,
        @SerialName("hearing-impaired") val hearingImpaired: Boolean = false,
        @SerialName("hls-bitrate") val hlsBitrate: Long = 0L,
        @SerialName("program-id") val programId: Long = -1L,
        @SerialName("selected") val selected: Boolean = false,
        @SerialName("main-selection") val mainSelection: Long = -1L,
        @SerialName("external") val external: Boolean = false,
        @SerialName("external-filename") val externalFilename: String = "",
        @SerialName("codec") val codec: String = "",
        @SerialName("codec-desc") val codecDesc: String = "",
        @SerialName("codec-profile") val codecProfile: String = "",
        @SerialName("ff-index") val ffIndex: Long = -1L,
        @SerialName("decoder") val decoder: String = "",
        @SerialName("decoder-desc") val decoderDesc: String = "",
        @SerialName("demux-w") val demuxW: Long = 0L,
        @SerialName("demux-h") val demuxH: Long = 0L,
        @SerialName("demux-crop-x") val demuxCropX: Long = 0L,
        @SerialName("demux-crop-y") val demuxCropY: Long = 0L,
        @SerialName("demux-crop-w") val demuxCropW: Long = 0L,
        @SerialName("demux-crop-h") val demuxCropH: Long = 0L,
        @SerialName("demux-channel-count") val demuxChannelCount: Long = 0L,
        @SerialName("demux-channels") val demuxChannels: String = "",
        @SerialName("demux-samplerate") val demuxSamplerate: Long = 0L,
        @SerialName("demux-fps") val demuxFps: Double = 0.0,
        @SerialName("demux-bitrate") val demuxBitrate: Long = 0L,
        @SerialName("demux-rotation") val demuxRotation: Long = 0L,
        @SerialName("demux-par") val demuxPar: Double = 0.0,
        @SerialName("format-name") val formatName: String = "",
        @SerialName("audio-channels") val audioChannels: Long = 0L,
        @SerialName("replaygain-track-peak") val replaygainTrackPeak: Double = 0.0,
        @SerialName("replaygain-track-gain") val replaygainTrackGain: Double = 0.0,
        @SerialName("replaygain-album-peak") val replaygainAlbumPeak: Double = 0.0,
        @SerialName("replaygain-album-gain") val replaygainAlbumGain: Double = 0.0,
        @SerialName("dolby-vision-profile") val dolbyVisionProfile: Long = 0L,
        @SerialName("dolby-vision-level") val dolbyVisionLevel: Long = 0L,
    ) {
        fun getTrackType(): TrackType? = when (type.lowercase()) {
            "sub" -> TrackType.SUBTITLE
            "subtitle" -> TrackType.SUBTITLE
            "audio" -> TrackType.AUDIO
            "video" -> TrackType.VIDEO
            else -> null
        }
    }

    enum class TrackType {
        SUBTITLE, AUDIO, VIDEO,
    }
}
