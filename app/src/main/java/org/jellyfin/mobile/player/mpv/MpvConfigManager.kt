package org.jellyfin.mobile.player.mpv

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Manages the user-provided [mpv.conf](https://mpv.io/manual/master/#configuration-files).
 *
 * The text is stored in SharedPreferences (edited from settings) and synchronized into
 * `config-dir/mpv.conf` before mpv is initialized. mpv already runs with `config=yes` and
 * `config-dir` pointed at the app's files directory, so the file is parsed natively and the
 * full mpv config syntax (comments, profiles, quoted values, …) is supported.
 */
object MpvConfigManager {
    private const val MPV_CONF_FILENAME = "mpv.conf"

    /** A single option assignment, e.g. `embeddedfonts=no` or `sub-scale = 1.1`. */
    private val OPTION_LINE_REGEX = Regex("""^\s*[A-Za-z][A-Za-z0-9_-]*\s*=.*$""")

    /** A profile section header, e.g. `[my-profile]`. */
    private val SECTION_LINE_REGEX = Regex("""^\s*\[[^[\]]+\]\s*$""")

    /**
     * Finds the first line that is not valid mpv.conf syntax.
     *
     * @return 1-based line number of the offending line, or `null` if the whole config is valid.
     */
    fun findInvalidLine(config: String): Int? {
        config.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val valid = line.isEmpty() ||
                line.startsWith("#") ||
                SECTION_LINE_REGEX.matches(line) ||
                OPTION_LINE_REGEX.matches(line)
            if (!valid) return index + 1
        }
        return null
    }

    /** Number of active (non-empty, non-comment, non-section) option lines. */
    fun countOptions(config: String): Int = config.lines().count { line ->
        val trimmed = line.trim()
        trimmed.isNotEmpty() &&
            !trimmed.startsWith("#") &&
            !SECTION_LINE_REGEX.matches(trimmed)
    }

    /**
     * Writes [config] into mpv's config directory as `mpv.conf`. Blank/whitespace-only configs
     * remove the file so that mpv falls back to its built-in defaults.
     */
    fun syncConfigFile(context: Context, config: String) {
        val configFile = File(context.filesDir, MPV_CONF_FILENAME)
        val content = config.trim()
        try {
            if (content.isEmpty()) {
                if (configFile.exists()) configFile.delete()
                return
            }
            // Write to a temp file and rename atomically, so mpv never reads a half-written config.
            val tempFile = File(context.filesDir, ".$MPV_CONF_FILENAME.tmp")
            tempFile.writeText(content + "\n")
            if (!tempFile.renameTo(configFile)) {
                configFile.delete()
                check(tempFile.renameTo(configFile)) { "Failed to install $MPV_CONF_FILENAME" }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync custom mpv config")
        }
    }
}
