package org.jellyfin.mobile.player.mpv.bench

/**
 * Turns a user's `mpv.conf` text into the profiles the benchmark applies at runtime.
 *
 * Why profiles: mpv reads `mpv.conf` once, at startup. Changing a render option live is done with
 * `apply-profile <name>`, which is exactly the granularity a comparison needs. So each line of the
 * user's configuration is emitted twice:
 *
 * - inside `[all]`, so applying that one profile reproduces the configuration as written;
 * - inside its own `[<prefix>-<n>]`, preceded by `profile=all-defaults` so the chunk resets every
 *   option the previous chunks had set. Applying chunks 1..N in order therefore renders exactly
 *   "the first N changes", and applying the baseline profile first renders "no changes at all".
 *
 * mpv has no way to un-apply a single option, so the reset is what makes an incremental run
 * meaningful; it is also why chunks are cumulative rather than independently toggleable.
 */
object MpvConfigProfiles {

    /**
     * Name of the profile that carries the complete configuration. Never collides with a chunk
     * profile because chunk names are suffixed with their line number.
     */
    const val ALL_PROFILE = "__ab-all"

    /** Name of the profile that resets every render option to its mpv default. */
    const val BASELINE_PROFILE = "__ab-baseline"

    /** Prefix of the per-line profiles. */
    const val CHUNK_PREFIX = "__ab"

    /** mpv's built-in profile that restores the option defaults. */
    private const val RESET_PROFILE = "all-defaults"

    /**
     * Splits [config] into comparable chunks, one per effective line.
     *
     * Comments, blank lines and `[section]` headers are not changes, so they never become chunks;
     * they are still kept inside [ALL_PROFILE] so the all-changes candidate behaves exactly like
     * the configuration the user wrote.
     */
    fun resolveChunks(config: String): List<ConfigChunk> {
        val chunks = mutableListOf<ConfigChunk>()
        config.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return@forEachIndexed
            chunks += ConfigChunk(
                profileName = "$CHUNK_PREFIX-${index + 1}",
                label = line,
                lines = listOf(line),
            )
        }
        return chunks
    }

    /**
     * Builds the mpv.conf text that materializes [config] plus the benchmark profiles.
     *
     * [chunks] must come from [resolveChunks] with the same [config]; it is passed in rather than
     * recomputed so the caller cannot end up with profiles that do not match the lines.
     */
    fun buildConfigFile(config: String, chunks: List<ConfigChunk>): String = buildString {
        append(config.trim())
        if (isNotEmpty()) append('\n')

        append("\n# --- mpv 配置对比（临时生成，退出对比页后自动移除）---\n")

        append("[$BASELINE_PROFILE]\n")
        append("profile=$RESET_PROFILE\n")

        append("[$ALL_PROFILE]\n")
        config.lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .forEach { append(it).append('\n') }

        chunks.forEach { chunk ->
            append("[${chunk.profileName}]\n")
            append("profile=$RESET_PROFILE\n")
            chunk.lines.forEach { append(it).append('\n') }
        }
    }

    /**
     * The render configuration a benchmark always starts from: `[all]` is the user's own
     * configuration, while the baseline candidate deliberately pins the options that only exist
     * because of this feature (the screenshot target) so they do not pollute the comparison.
     */
    fun baselineConfigCandidate(): ConfigCandidate = ConfigCandidate(
        id = ConfigCandidate.ID_BASELINE,
        label = "当前配置",
        profileName = ALL_PROFILE,
    )
}
