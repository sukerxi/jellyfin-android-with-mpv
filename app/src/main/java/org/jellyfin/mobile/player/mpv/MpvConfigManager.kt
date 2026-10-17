package org.jellyfin.mobile.player.mpv

import android.content.Context
import dev.jdtech.mpv.MPVLib
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
    private val SECTION_LINE_REGEX = Regex("""^\s*\[[^\[\]]+\]\s*$""")

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

    /**
     * Options that only take effect while mpv starts up.
     *
     * They are set before `init()` (either by [MpvCore.baseOptions] or by mpv reading `mpv.conf`
     * itself), so re-applying them on a live instance would either fail or tear the video output
     * down in the middle of a render. Their config-file values still apply through mpv's own
     * config parsing.
     */
    private val INIT_ONLY_OPTIONS = setOf(
        "vo",
        "gpu-context",
        "gpu-api",
        "opengl-es",
        "config",
        "config-dir",
        "include",
        "input-conf",
        "input-default-bindings",
    )

    /** `name = value` (spaces optional, surrounding quotes stripped). */
    private val ASSIGNMENT_REGEX = Regex("""^\s*([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(.*?)\s*$""")

    /**
     * Applies [config] on top of everything the app itself set before `init()`.
     *
     * mpv gives options passed before initialization (the equivalent of command-line options)
     * precedence over `mpv.conf`, and [MpvCore.baseOptions] passes `profile=fast`. That profile -
     * see the table compiled into libmpv - pins `scale=bilinear`, `dscale=bilinear`, `dither=no`,
     * `sigmoid-upscaling=no` and more. Every one of those lines in the user's config was therefore
     * silently ignored: `scale=ewa_lanczossharp` rendered exactly like the default, which reads as
     * "the comparison is broken" when in fact the option never reached the video output.
     *
     * Re-applying the config's top-level options after initialization is what makes the user's own
     * settings win over the profile. Options that can only be set at startup are skipped (see
     * [INIT_ONLY_OPTIONS]); a `profile=` line is applied through mpv's `apply-profile` command.
     */
    fun applyUserConfig(instance: MPVLib, config: String) {
        topLevelOptions(config).forEach { (name, value) ->
            when {
                name == "profile" -> value.split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { profile ->
                        runCatching { instance.command(arrayOf("apply-profile", profile)) }
                            .onFailure { Timber.w(it, "mpv: could not apply profile %s", profile) }
                    }
                name in INIT_ONLY_OPTIONS -> Unit
                else -> runCatching { instance.setOptionString(name, value) }
                    .onFailure { Timber.w(it, "mpv: could not apply %s=%s", name, value) }
            }
        }
    }

    /**
     * The `name = value` assignments that are not inside a `[profile]` section.
     *
     * Options under a section header belong to that profile and are not active by default, so they
     * must not be applied to the instance behind the user's back. (`[default]` is the one exception
     * mpv applies by itself, and it is applied through mpv's own config parsing at startup.)
     */
    fun topLevelOptions(config: String): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()
        var inSection = false
        for (rawLine in config.lineSequence()) {
            val line = rawLine.trim()
            if (SECTION_LINE_REGEX.matches(line)) {
                inSection = true
                continue
            }
            if (inSection || line.isEmpty() || line.startsWith("#")) continue
            val match = ASSIGNMENT_REGEX.matchEntire(line) ?: continue
            val name = match.groupValues[1]
            val value = match.groupValues[2].removeSurrounding("\"")
            options += name to value
        }
        return options
    }
}
