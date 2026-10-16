package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.player.mpv.MpvCore

/**
 * Applies a configuration chunk to the running mpv instance.
 *
 * Keeps the two halves of "changing a configuration" together: the file on disk (so a restart
 * behaves the same) and the profile mpv needs to apply for the change to take effect right now.
 * Chunks are only ever applied forward, so the swapper tracks how far it has got and re-initializes
 * mpv when a caller asks for something other than the next chunk.
 */
class MpvConfigSwapper(private val context: Context) {

    /** The config text the running profiles were generated from. */
    private var installedConfig: String? = null

    /** Number of chunks already applied since the last reset. */
    private var appliedChunks: Int = 0

    /**
     * Writes [config] together with its benchmark profiles and resets mpv to the configuration
     * with no chunk applied.
     *
     * Re-initializing mpv is the only reliable way to undo an earlier chunk: a profile override
     * cannot be taken back selectively (see [MpvConfigProfiles]).
     */
    fun reset(config: String) {
        val chunks = MpvConfigProfiles.resolveChunks(config)
        MpvConfigManager.syncConfigFile(context, MpvConfigProfiles.buildConfigFile(config, chunks))
        MpvCore.reinitialize()
        MpvCore.command(arrayOf("apply-profile", MpvConfigProfiles.BASELINE_PROFILE))
        installedConfig = config
        appliedChunks = 0
    }

    /**
     * Makes [candidate] the active render configuration.
     *
     * @return false when the candidate cannot be reached from the current state, which happens for
     * chunks that are not the next one in order. Callers recover with [reset].
     */
    fun select(candidate: ConfigCandidate, chunks: List<ConfigChunk>): Boolean {
        val profile = candidate.profileName ?: MpvConfigProfiles.BASELINE_PROFILE
        if (profile == MpvConfigProfiles.ALL_PROFILE || profile == MpvConfigProfiles.BASELINE_PROFILE) {
            MpvCore.command(arrayOf("apply-profile", profile))
            appliedChunks = 0
            return true
        }
        val index = chunks.indexOfFirst { it.profileName == profile }
        if (index < 0) return false
        if (index < appliedChunks) return false
        for (next in appliedChunks..index) {
            MpvCore.command(arrayOf("apply-profile", chunks[next].profileName))
        }
        appliedChunks = index + 1
        return true
    }

    /** Whether the profiles for [config] are the ones currently installed in mpv.conf. */
    fun isInstalled(config: String): Boolean = installedConfig == config

    /**
     * Removes the generated profiles from `mpv.conf`, leaving the user's own configuration in
     * place. mpv keeps running with whatever profile is active until it is restarted, which is
     * what the benchmark wants: the real playback session usually ends before that happens.
     */
    fun uninstall(config: String) {
        MpvConfigManager.syncConfigFile(context, config)
        installedConfig = null
        appliedChunks = 0
    }
}
