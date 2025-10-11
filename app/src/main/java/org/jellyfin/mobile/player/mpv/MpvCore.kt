package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.util.Log

import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MPV_FORMAT_FLAG
import dev.jdtech.mpv.MPVLib.MPV_FORMAT_NONE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiConsumer

/**
 * @author dr
 */
class MpvCore private constructor(context: Application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mpvLibEventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            if(property=="track-list"){
                mainHandler.post {
                    currentEvent = MPV_EVENT_TRACK_LIST_CHANGE
                    for (consumer in eventListeners) {
                        consumer.accept(currentEvent, "")
                    }
                    currentEvent= MPV_EVENT_NONE
                }
            }else if (property=="hwdec-current"){
                mainHandler.post {
                    currentEvent = MPV_EVENT_DECODER_CHANGE
                    for (consumer in eventListeners) {
                        consumer.accept(currentEvent, "")
                    }
                    currentEvent= MPV_EVENT_NONE
                }
            }
        }
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun eventProperty(property: String, value: Boolean) {
            if(property=="paused-for-cache"){
                mainHandler.post {
                    currentEvent =if (value) MPV_EVENT_PAUSED_FOR_CACHE_START
                    else  MPV_EVENT_PAUSED_FOR_CACHE_END
                    for (consumer in eventListeners) {
                        consumer.accept(currentEvent, value)
                    }
                    currentEvent= MPV_EVENT_NONE
                }
            }
        }
        override fun eventProperty(property: String, value: String) {}
        override fun event(eventId: Int) {
            mainHandler.post {
                currentEvent=eventId
                for (consumer in eventListeners) {
                    consumer.accept(eventId, "")
                }
                currentEvent= MPV_EVENT_NONE
            }
        }
    }



    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val eventListeners: CopyOnWriteArrayList<BiConsumer<Int, Any>> = CopyOnWriteArrayList()
        var currentEvent: Int = MPV_EVENT_NONE
            private set
        const val MPV_EVENT_PAUSED_FOR_CACHE_START =1000
        const val MPV_EVENT_PAUSED_FOR_CACHE_END =1001
        const val MPV_EVENT_TRACK_LIST_CHANGE =1002
        const val MPV_EVENT_DECODER_CHANGE =1003
        const val MPV_EVENT_START_FILE =MPVLib.MPV_EVENT_START_FILE
        const val MPV_EVENT_FILE_LOADED =MPVLib.MPV_EVENT_FILE_LOADED
        const val MPV_EVENT_END_FILE = MPVLib.MPV_EVENT_END_FILE
        const val MPV_EVENT_PLAYBACK_RESTART = MPVLib.MPV_EVENT_PLAYBACK_RESTART
        const val MPV_EVENT_SEEK =MPVLib.MPV_EVENT_SEEK
        const val MPV_EVENT_NONE =MPVLib.MPV_EVENT_NONE


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
        fun setOptions(name: String,value: String) {
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
        fun subscribe(eventListener:BiConsumer<Int, Any>) {
            eventListeners.add(eventListener)
        }
        fun unsubscribe(eventListener:BiConsumer<Int, Any>) {
            eventListeners.remove(eventListener)
        }
        fun attachSurface(surface: Surface) {
            MPVLib.attachSurface(surface)
        }
        fun detachSurface() {
            MPVLib.detachSurface()
        }

        fun getTracks(): List<MediaTrack> {
            val trackList = getProperty<String>("track-list")
            trackList?.let { Log.d("MpvCore",it) }
            return trackList?.let { tracks ->
                json.decodeFromString(tracks)
            }?: emptyList()
        }

    }



    init {
        val configDir = context.filesDir.path
        MPVLib.create(context)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("gpu-context", "android")  //auto
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("input-default-bindings", "yes")
        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("vd-lavc-film-grain", "cpu")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("cache-pause-initial", "yes")
        MPVLib.setOptionString("vo", "gpu_next,gpu")
        MPVLib.init()

        MPVLib.setOptionString("save-position-on-quit", "no")
//        MPVLib.setOptionString("idle", "once")
        // MPVLib.setOptionString("force-window", "yes")
        observeProperties()
        MPVLib.addObserver(mpvLibEventObserver)
    }

    private fun observeProperties() {
        // This observes all properties needed by MPVView, MPVActivity or other classes
        data class Property(val name: String, val format: Int = MPV_FORMAT_NONE)
        val p = arrayOf(
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
        for ((name, format) in p)
            MPVLib.observeProperty(name, format)
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
    ){
        fun getTrackType(): TrackType? = when (type.lowercase()) {
            "sub" -> TrackType.SUBTITLE
            "subtitle" -> TrackType.SUBTITLE
            "audio" -> TrackType.AUDIO
            "video" -> TrackType.VIDEO
            else -> null
        }
    }

    enum class TrackType {
        SUBTITLE, AUDIO, VIDEO
    }


    class MediaTrackManager(private val tracks: List<MediaTrack>) {

        // 获取当前选中的各类型轨道
        fun getSelectedTracks(): Map<TrackType, MediaTrack> {
            val selectedTracks = mutableMapOf<TrackType, MediaTrack>()
            for (track in tracks) {
                if (track.selected == true) { // 假设selected字段表示是否被选中
                    track.getTrackType()?.let { selectedTracks[it] = track }
                }
            }
            return selectedTracks
        }

        // 获取给定类型的所有轨道
        fun getTracksByType(trackType: TrackType): List<MediaTrack> {
            return tracks.filter { it.getTrackType() == trackType }
        }
    }
}
