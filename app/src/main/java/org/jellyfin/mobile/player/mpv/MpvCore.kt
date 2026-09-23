package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MPV_FORMAT_FLAG
import dev.jdtech.mpv.MPVLib.MPV_FORMAT_NONE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

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

    data object DecoderChanged : MpvEvent
    data object TrackListChanged : MpvEvent
}

/**
 * Thin wrapper around the native mpv player. Exposes a type-safe event stream via [MpvEvent].
 *
 * @author dr
 */
class MpvCore private constructor(context: Application) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postEvent(event: MpvEvent) {
        mainHandler.post {
            for (listener in eventListeners) listener(event)
        }
    }

    private val mpvLibEventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            when (property) {
                "track-list" -> postEvent(MpvEvent.TrackListChanged)
                "hwdec-current" -> postEvent(MpvEvent.DecoderChanged)
            }
        }

        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Double) {}

        override fun eventProperty(property: String, value: Boolean) {
            if (property == "paused-for-cache") postEvent(MpvEvent.Caching(value))
        }

        override fun eventProperty(property: String, value: String) {}

        override fun event(eventId: Int) {
            val event = when (eventId) {
                MPVLib.MPV_EVENT_START_FILE -> MpvEvent.StartFile
                MPVLib.MPV_EVENT_FILE_LOADED -> MpvEvent.FileLoaded
                MPVLib.MPV_EVENT_END_FILE -> MpvEvent.EndFile
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> MpvEvent.PlaybackRestart
                MPVLib.MPV_EVENT_SEEK -> MpvEvent.Seek
                else -> return
            }
            postEvent(event)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val eventListeners: CopyOnWriteArrayList<(MpvEvent) -> Unit> = CopyOnWriteArrayList()

        @Volatile
        private var INSTANCE: MpvCore? = null

        fun initialize(application: Application): MpvCore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MpvCore(application).also { INSTANCE = it }
            }
        }

        inline fun <reified T> getProperty(name: String): T? {
            return when (T::class) {
                String::class -> MPVLib.getPropertyString(name) as T?
                Int::class -> MPVLib.getPropertyInt(name) as T?
                Long::class -> MPVLib.getPropertyInt(name)?.toLong() as T?
                Float::class -> MPVLib.getPropertyDouble(name)?.toFloat() as T?
                Double::class -> MPVLib.getPropertyDouble(name) as T?
                Boolean::class -> MPVLib.getPropertyBoolean(name) as T?
                else -> throw IllegalArgumentException("Unsupported property type: ${T::class}")
            }
        }

        fun command(cmd: Array<String>) {
            MPVLib.command(cmd)
        }

        fun setOptions(name: String, value: String) {
            MPVLib.setOptionString(name, value)
        }

        fun setProperty(name: String, value: Any) {
            when (value) {
                is String -> MPVLib.setPropertyString(name, value)
                is Int -> MPVLib.setPropertyInt(name, value)
                is Double -> MPVLib.setPropertyDouble(name, value)
                is Boolean -> MPVLib.setPropertyBoolean(name, value)
                is Float -> MPVLib.setPropertyDouble(name, value.toDouble())
                is Long -> MPVLib.setPropertyInt(name, value.toInt())
                else -> throw IllegalArgumentException("Unsupported property type: ${value::class}")
            }
        }

        fun subscribe(eventListener: (MpvEvent) -> Unit) {
            eventListeners.add(eventListener)
        }

        fun unsubscribe(eventListener: (MpvEvent) -> Unit) {
            eventListeners.remove(eventListener)
        }

        fun attachSurface(surface: Surface) {
            MPVLib.attachSurface(surface)
        }

        fun detachSurface() {
            MPVLib.detachSurface()
        }

        fun getTracks(): List<MediaTrack> {
            val trackList = getProperty<String>("track-list") ?: return emptyList()
            return json.decodeFromString(trackList)
        }
    }

    init {
        MPVLib.create(context)
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
        ).forEach { (name, value) ->
            MPVLib.setOptionString(name, value)
        }
        MPVLib.init()

//        MPVLib.setOptionString("idle", "once")
        // MPVLib.setOptionString("force-window", "yes")
        observeProperties()
        MPVLib.addObserver(mpvLibEventObserver)
    }

    private fun observeProperties() {
        // This observes all properties needed by MpvPlayer or other classes
        data class Property(val name: String, val format: Int = MPV_FORMAT_NONE)
        val properties = arrayOf(
            Property("paused-for-cache", MPV_FORMAT_FLAG),
            Property("hwdec-current"),
            // Property("time-pos/full", MPV_FORMAT_INT64),
            // Property("duration/full", MPV_FORMAT_INT64),
            // Property("pause", MPV_FORMAT_FLAG),
            // Property("speed", MPV_FORMAT_STRING),
            // Property("track-list"),
            // Property("video-params/aspect", MPV_FORMAT_DOUBLE),
            // Property("video-params/rotate", MPV_FORMAT_DOUBLE),
            // Property("playlist-pos", MPV_FORMAT_INT64),
            // Property("playlist-count", MPV_FORMAT_INT64),
            // Property("current-tracks/video/image"),
            // Property("media-title", MPV_FORMAT_STRING),
            // Property("metadata"),
            // Property("loop-playlist"),
            // Property("loop-file"),
            // Property("shuffle", MPV_FORMAT_FLAG),
            // Property("mute", MPV_FORMAT_FLAG),
            // Property("current-tracks/audio/selected"),
        )
        for ((name, format) in properties) {
            MPVLib.observeProperty(name, format)
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
