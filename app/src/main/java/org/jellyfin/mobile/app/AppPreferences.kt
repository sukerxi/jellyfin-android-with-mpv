package org.jellyfin.mobile.app

import android.content.Context
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.player.mediasegments.MediaSegmentAction
import org.jellyfin.mobile.player.mediasegments.toMediaSegmentActionsString
import org.jellyfin.mobile.preferences.appPreferencesDataStore
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.MediaSegmentType

/**
 * Preferences facade backed by Jetpack Preferences DataStore.
 *
 * Existing call sites rely on synchronous access, so the full preference set is loaded
 * into an in-memory [snapshot] on construction (blocking, one-time) and every setter
 * updates the snapshot synchronously before persisting asynchronously. Reads therefore
 * always reflect the most recent write even when the write is still in flight.
 *
 * Compose UI observes changes through the dedicated `*Flow` properties.
 */
class AppPreferences(context: Context) {
    private val dataStore: DataStore<Preferences> = context.appPreferencesDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()

    private val snapshot: MutablePreferences = runBlocking {
        mutablePreferencesOf().apply { this += dataStore.data.first() }
    }

    init {
        migrateMpvUseEmbedFont()
    }

    /**
     * Migrates the removed "use embedded fonts" switch into an `embeddedfonts=no` line in the
     * custom mpv configuration. mpv itself defaults to `embeddedfonts=yes`, so only users who
     * explicitly turned the switch off are affected.
     */
    private fun migrateMpvUseEmbedFont() {
        val useEmbedFont = snapshot[KEY_MPV_USE_EMBED_FONT_LEGACY] ?: return
        if (!useEmbedFont && snapshot[KEY_MPV_CUSTOM_CONFIG].isNullOrEmpty()) {
            persistValue(KEY_MPV_CUSTOM_CONFIG, "embeddedfonts=no\n")
        }
        removeKey(KEY_MPV_USE_EMBED_FONT_LEGACY)
    }

    // region Persistence helpers

    private fun <T> persistValue(key: Preferences.Key<T>, value: T) {
        synchronized(writeLock) { snapshot[key] = value }
        scope.launch {
            dataStore.edit { preferences -> preferences[key] = value }
        }
    }

    private fun <T> removeKey(key: Preferences.Key<T>) {
        synchronized(writeLock) { snapshot.remove(key) }
        scope.launch {
            dataStore.edit { preferences -> preferences.remove(key) }
        }
    }

    private fun <T> preferenceFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }

    private fun <T> nullablePreferenceFlow(key: Preferences.Key<T>): Flow<T?> =
        dataStore.data.map { it[key] }

    // endregion

    var currentServerId: Long?
        get() = snapshot[KEY_SERVER_ID]?.takeIf { it >= 0 }
        set(value) {
            if (value != null) persistValue(KEY_SERVER_ID, value) else removeKey(KEY_SERVER_ID)
        }

    var currentUserId: Long?
        get() = snapshot[KEY_USER_ID]?.takeIf { it >= 0 }
        set(value) {
            if (value != null) persistValue(KEY_USER_ID, value) else removeKey(KEY_USER_ID)
        }

    var ignoreBatteryOptimizations: Boolean
        get() = snapshot[KEY_IGNORE_BATTERY_OPTIMIZATIONS] ?: false
        set(value) = persistValue(KEY_IGNORE_BATTERY_OPTIMIZATIONS, value)

    var ignoreWebViewChecks: Boolean
        get() = snapshot[KEY_IGNORE_WEBVIEW_CHECKS] ?: false
        set(value) = persistValue(KEY_IGNORE_WEBVIEW_CHECKS, value)

    var ignoreBluetoothPermission: Boolean
        get() = snapshot[KEY_IGNORE_BLUETOOTH_PERMISSION] ?: false
        set(value) = persistValue(KEY_IGNORE_BLUETOOTH_PERMISSION, value)

    var downloadMethod: DownloadMethod
        get() = DownloadMethod.fromInt(snapshot[KEY_DOWNLOAD_METHOD] ?: DownloadMethod.DEFAULT.intValue)
            ?: DownloadMethod.DEFAULT
        set(value) = persistValue(KEY_DOWNLOAD_METHOD, value.intValue)
    val downloadMethodFlow: Flow<DownloadMethod> =
        dataStore.data.map {
            DownloadMethod.fromInt(it[KEY_DOWNLOAD_METHOD] ?: DownloadMethod.DEFAULT.intValue)
                ?: DownloadMethod.DEFAULT
        }

    var storageLocation: String?
        get() = snapshot[KEY_STORAGE_LOCATION]
        set(value) {
            if (value != null) persistValue(KEY_STORAGE_LOCATION, value)
            else removeKey(KEY_STORAGE_LOCATION)
        }
    val storageLocationFlow: Flow<String?> = nullablePreferenceFlow(KEY_STORAGE_LOCATION)

    /**
     * The actions to take for each media segment type. Managed by the MediaSegmentRepository.
     */
    var mediaSegmentActions: String
        get() = snapshot[KEY_MEDIA_SEGMENT_ACTIONS] ?: DEFAULT_MEDIA_SEGMENT_ACTIONS
        set(value) = persistValue(KEY_MEDIA_SEGMENT_ACTIONS, value)

    var musicNotificationAlwaysDismissible: Boolean
        get() = snapshot[KEY_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE] ?: false
        set(value) = persistValue(KEY_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE, value)
    val musicNotificationAlwaysDismissibleFlow: Flow<Boolean> =
        preferenceFlow(KEY_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE, false)

    var videoPlayerType: String
        get() = snapshot[KEY_VIDEO_PLAYER_TYPE] ?: VideoPlayerType.EXO_PLAYER
        set(value) = persistValue(KEY_VIDEO_PLAYER_TYPE, value)
    val videoPlayerTypeFlow: Flow<String> =
        preferenceFlow(KEY_VIDEO_PLAYER_TYPE, VideoPlayerType.EXO_PLAYER)

    var exoPlayerStartLandscapeVideoInLandscape: Boolean
        get() = snapshot[KEY_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE] ?: false
        set(value) = persistValue(KEY_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE, value)
    val exoPlayerStartLandscapeVideoInLandscapeFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE, false)

    var exoPlayerAllowSwipeGestures: Boolean
        get() = snapshot[KEY_EXOPLAYER_ALLOW_SWIPE_GESTURES] ?: true
        set(value) = persistValue(KEY_EXOPLAYER_ALLOW_SWIPE_GESTURES, value)
    val exoPlayerAllowSwipeGesturesFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_ALLOW_SWIPE_GESTURES, true)

    var exoPlayerAllowPressSpeedUp: Boolean
        get() = snapshot[KEY_EXOPLAYER_ALLOW_PRESS_SPEED_UP] ?: true
        set(value) = persistValue(KEY_EXOPLAYER_ALLOW_PRESS_SPEED_UP, value)
    val exoPlayerAllowPressSpeedUpFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_ALLOW_PRESS_SPEED_UP, true)

    var exoPlayerRememberBrightness: Boolean
        get() = snapshot[KEY_EXOPLAYER_REMEMBER_BRIGHTNESS] ?: false
        set(value) = persistValue(KEY_EXOPLAYER_REMEMBER_BRIGHTNESS, value)
    val exoPlayerRememberBrightnessFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_REMEMBER_BRIGHTNESS, false)

    var exoPlayerBrightness: Float
        get() = snapshot[KEY_EXOPLAYER_BRIGHTNESS] ?: BRIGHTNESS_OVERRIDE_NONE
        set(value) = persistValue(KEY_EXOPLAYER_BRIGHTNESS, value)

    var exoPlayerAllowBackgroundAudio: Boolean
        get() = snapshot[KEY_EXOPLAYER_ALLOW_BACKGROUND_AUDIO] ?: false
        set(value) = persistValue(KEY_EXOPLAYER_ALLOW_BACKGROUND_AUDIO, value)
    val exoPlayerAllowBackgroundAudioFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_ALLOW_BACKGROUND_AUDIO, false)

    var exoPlayerAllowHorizontalGesture: Boolean
        get() = snapshot[KEY_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE] ?: true
        set(value) = persistValue(KEY_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE, value)
    val exoPlayerAllowHorizontalGestureFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE, true)

    var exoPlayerDoubleTapPause: Boolean
        get() = snapshot[KEY_EXOPLAYER_DOUBLE_TAP_PAUSE] ?: false
        set(value) = persistValue(KEY_EXOPLAYER_DOUBLE_TAP_PAUSE, value)
    val exoPlayerDoubleTapPauseFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_DOUBLE_TAP_PAUSE, false)

    var exoPlayerDirectPlayAss: Boolean
        get() = snapshot[KEY_EXOPLAYER_DIRECT_PLAY_ASS] ?: false
        set(value) = persistValue(KEY_EXOPLAYER_DIRECT_PLAY_ASS, value)
    val exoPlayerDirectPlayAssFlow: Flow<Boolean> =
        preferenceFlow(KEY_EXOPLAYER_DIRECT_PLAY_ASS, false)

    var exoPlayerNetworkBuffer: String
        get() = snapshot[KEY_EXOPLAYER_NETWORK_BUFFER] ?: Constants.NETWORK_BUFFER_AUTO
        set(value) = persistValue(KEY_EXOPLAYER_NETWORK_BUFFER, value)
    val exoPlayerNetworkBufferFlow: Flow<String> =
        preferenceFlow(KEY_EXOPLAYER_NETWORK_BUFFER, Constants.NETWORK_BUFFER_AUTO)

    /**
     * 移动网络（蜂窝）下开始播放前是否弹窗确认，避免流量偷跑。
     *
     * 关闭后不再询问，直接按下方的码率上限播放。
     */
    var mobileDataPlaybackPrompt: Boolean
        get() = snapshot[KEY_MOBILE_DATA_PLAYBACK_PROMPT] ?: true
        set(value) = persistValue(KEY_MOBILE_DATA_PLAYBACK_PROMPT, value)
    val mobileDataPlaybackPromptFlow: Flow<Boolean> =
        preferenceFlow(KEY_MOBILE_DATA_PLAYBACK_PROMPT, true)

    /**
     * 移动网络播放时使用的码率上限（bps）。默认 720p / 4 Mbps。
     *
     * 该值会作为服务端转码的 maxStreamingBitrate 下发，因此会强制走转码而不是直连。
     */
    var mobileDataMaxBitrate: Int
        get() = snapshot[KEY_MOBILE_DATA_MAX_BITRATE] ?: Constants.MOBILE_DATA_MAX_BITRATE_DEFAULT
        set(value) = persistValue(KEY_MOBILE_DATA_MAX_BITRATE, value)
    val mobileDataMaxBitrateFlow: Flow<Int> =
        preferenceFlow(KEY_MOBILE_DATA_MAX_BITRATE, Constants.MOBILE_DATA_MAX_BITRATE_DEFAULT)

    @ExternalPlayerPackage
    var externalPlayerApp: String
        get() = snapshot[KEY_EXTERNAL_PLAYER_APP] ?: ExternalPlayerPackage.SYSTEM_DEFAULT
        set(value) = persistValue(KEY_EXTERNAL_PLAYER_APP, value)
    val externalPlayerAppFlow: Flow<String> =
        preferenceFlow(KEY_EXTERNAL_PLAYER_APP, ExternalPlayerPackage.SYSTEM_DEFAULT)

    /**
     * Resolves whether ExoPlayer should render SSA/ASS subtitles natively instead of
     * letting the server burn them into the video stream.
     *
     * Transcoding wins over the preference: the server already draws the subtitles into
     * the video frames, so the client must not claim SSA/ASS support in its device profile
     * (that would stop the burn-in) and does not need libass to render a text track that
     * does not exist.
     */
    fun resolveExoPlayerDirectPlayAss(isVideoTranscoding: Boolean): Boolean =
        exoPlayerDirectPlayAss && !isVideoTranscoding

    /**
     * User-provided [mpv.conf](https://mpv.io/manual/master/#configuration-files) content.
     * Written into mpv's config directory and parsed natively before playback starts.
     */
    var mpvCustomConfig: String
        get() = snapshot[KEY_MPV_CUSTOM_CONFIG].orEmpty()
        set(value) = persistValue(KEY_MPV_CUSTOM_CONFIG, value)
    val mpvCustomConfigFlow: Flow<String> =
        dataStore.data.map { it[KEY_MPV_CUSTOM_CONFIG].orEmpty() }

    /**
     * Whether the player controls offer the mpv configuration comparison.
     * Defaults to on: the entry is cheap and only appears while mpv is playing.
     */
    var mpvShowCompareEntry: Boolean
        get() = snapshot[KEY_MPV_SHOW_COMPARE_ENTRY] ?: true
        set(value) = persistValue(KEY_MPV_SHOW_COMPARE_ENTRY, value)
    val mpvShowCompareEntryFlow: Flow<Boolean> =
        preferenceFlow(KEY_MPV_SHOW_COMPARE_ENTRY, true)

    var autoCheckUpdates: Boolean
        get() = snapshot[KEY_AUTO_CHECK_UPDATES] ?: false
        set(value) = persistValue(KEY_AUTO_CHECK_UPDATES, value)
    val autoCheckUpdatesFlow: Flow<Boolean> =
        preferenceFlow(KEY_AUTO_CHECK_UPDATES, false)

    /** 自动检查发现新版本时是否静默后台下载。 */
    var silentUpdateDownload: Boolean
        get() = snapshot[KEY_SILENT_DOWNLOAD] ?: false
        set(value) = persistValue(KEY_SILENT_DOWNLOAD, value)
    val silentUpdateDownloadFlow: Flow<Boolean> =
        preferenceFlow(KEY_SILENT_DOWNLOAD, false)

    var dismissedUpdateVersion: String?
        get() = snapshot[KEY_DISMISSED_UPDATE_VERSION]
        set(value) {
            if (value != null) persistValue(KEY_DISMISSED_UPDATE_VERSION, value)
            else removeKey(KEY_DISMISSED_UPDATE_VERSION)
        }

    var lastUpdateCheckTime: Long
        get() = snapshot[KEY_LAST_UPDATE_CHECK_TIME] ?: 0L
        set(value) = persistValue(KEY_LAST_UPDATE_CHECK_TIME, value)

    private companion object {
        private val DEFAULT_MEDIA_SEGMENT_ACTIONS = mapOf(
            MediaSegmentType.INTRO to MediaSegmentAction.ASK_TO_SKIP,
            MediaSegmentType.OUTRO to MediaSegmentAction.ASK_TO_SKIP,
        ).toMediaSegmentActionsString()

        private val KEY_SERVER_ID = longPreferencesKey(Constants.PREF_SERVER_ID)
        private val KEY_USER_ID = longPreferencesKey(Constants.PREF_USER_ID)
        private val KEY_IGNORE_BATTERY_OPTIMIZATIONS =
            booleanPreferencesKey(Constants.PREF_IGNORE_BATTERY_OPTIMIZATIONS)
        private val KEY_IGNORE_WEBVIEW_CHECKS =
            booleanPreferencesKey(Constants.PREF_IGNORE_WEBVIEW_CHECKS)
        private val KEY_IGNORE_BLUETOOTH_PERMISSION =
            booleanPreferencesKey(Constants.PREF_IGNORE_BLUETOOTH_PERMISSION)
        private val KEY_DOWNLOAD_METHOD = intPreferencesKey(Constants.PREF_DOWNLOAD_METHOD)
        private val KEY_STORAGE_LOCATION = stringPreferencesKey(Constants.PREF_STORAGE_LOCATION)
        private val KEY_MEDIA_SEGMENT_ACTIONS =
            stringPreferencesKey(Constants.PREF_MEDIA_SEGMENT_ACTIONS)
        private val KEY_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE =
            booleanPreferencesKey(Constants.PREF_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE)
        private val KEY_VIDEO_PLAYER_TYPE =
            stringPreferencesKey(Constants.PREF_VIDEO_PLAYER_TYPE)
        private val KEY_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE)
        private val KEY_EXOPLAYER_ALLOW_SWIPE_GESTURES =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_ALLOW_SWIPE_GESTURES)
        private val KEY_EXOPLAYER_ALLOW_PRESS_SPEED_UP =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_ALLOW_PRESS_SPEED_UP)
        private val KEY_EXOPLAYER_REMEMBER_BRIGHTNESS =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_REMEMBER_BRIGHTNESS)
        private val KEY_EXOPLAYER_BRIGHTNESS =
            floatPreferencesKey(Constants.PREF_EXOPLAYER_BRIGHTNESS)
        private val KEY_EXOPLAYER_ALLOW_BACKGROUND_AUDIO =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO)
        private val KEY_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE)
        private val KEY_EXOPLAYER_DOUBLE_TAP_PAUSE =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_DOUBLE_TAP_PAUSE)
        private val KEY_EXOPLAYER_DIRECT_PLAY_ASS =
            booleanPreferencesKey(Constants.PREF_EXOPLAYER_DIRECT_PLAY_ASS)
        private val KEY_EXOPLAYER_NETWORK_BUFFER =
            stringPreferencesKey(Constants.PREF_EXOPLAYER_NETWORK_BUFFER)
        private val KEY_MOBILE_DATA_PLAYBACK_PROMPT =
            booleanPreferencesKey(Constants.PREF_MOBILE_DATA_PLAYBACK_PROMPT)
        private val KEY_MOBILE_DATA_MAX_BITRATE =
            intPreferencesKey(Constants.PREF_MOBILE_DATA_MAX_BITRATE)
        private val KEY_EXTERNAL_PLAYER_APP =
            stringPreferencesKey(Constants.PREF_EXTERNAL_PLAYER_APP)
        private val KEY_MPV_CUSTOM_CONFIG = stringPreferencesKey(Constants.PREF_MPV_CUSTOM_CONFIG)
        private val KEY_MPV_SHOW_COMPARE_ENTRY =
            booleanPreferencesKey(Constants.PREF_MPV_SHOW_COMPARE_ENTRY)
        private val KEY_MPV_USE_EMBED_FONT_LEGACY =
            booleanPreferencesKey(Constants.PREF_MPV_USE_EMBED_FONT_LEGACY)
        private val KEY_AUTO_CHECK_UPDATES =
            booleanPreferencesKey(Constants.PREF_AUTO_CHECK_UPDATES)
        private val KEY_SILENT_DOWNLOAD = booleanPreferencesKey(Constants.PREF_SILENT_DOWNLOAD)
        private val KEY_DISMISSED_UPDATE_VERSION =
            stringPreferencesKey("pref_dismissed_update_version")
        private val KEY_LAST_UPDATE_CHECK_TIME = longPreferencesKey("pref_last_update_check_time")
    }
}
