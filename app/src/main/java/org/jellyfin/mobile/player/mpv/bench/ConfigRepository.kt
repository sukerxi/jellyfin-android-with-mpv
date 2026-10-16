package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.mpv.MpvConfigManager

/**
 * Owns the user's `mpv.conf` for the duration of a comparison.
 *
 * The single rule: a benchmark must never leave the user's configuration changed behind. That
 * includes the failure paths, so the restore lives here rather than in a screen's `onDispose`.
 */
class ConfigRepository(
    private val context: Context,
    private val appPreferences: AppPreferences,
) {
    /** The configuration the user had before the benchmark installed its profiles. */
    private var userConfig: String? = null

    fun currentConfig(): String = appPreferences.mpvCustomConfig

    /** Context the benchmark writes its configuration for; used to build a [MpvConfigSwapper]. */
    fun appContext(): Context = context

    /**
     * Writes [config] into `mpv.conf` together with its benchmark profiles and resets mpv to the
     * "no chunk applied" state.
     */
    fun install(config: String, swapper: MpvConfigSwapper) {
        if (userConfig == null) userConfig = currentConfig()
        swapper.reset(config)
    }

    /**
     * Restores the configuration the benchmark started from, both in the preferences (so a restart
     * is unaffected) and in `mpv.conf`.
     */
    fun restore(swapper: MpvConfigSwapper) {
        val original = userConfig ?: return
        userConfig = null
        swapper.uninstall(original)
        MpvConfigManager.syncConfigFile(context, original)
    }

    /**
     * Keeps the edited configuration as the user's own. Used when the user confirms the result of
     * a comparison and wants to continue playing with the configuration they just validated.
     */
    fun commit(config: String, swapper: MpvConfigSwapper) {
        userConfig = null
        appPreferences.mpvCustomConfig = config
        MpvConfigManager.syncConfigFile(context, config)
        swapper.uninstall(config)
    }
}
