package org.jellyfin.mobile.player.mpv.bench

/**
 * One comparable unit of a configuration: the lines that make up a single change.
 *
 * A chunk is both a name (the mpv profile it is materialized as) and a label for the UI.
 */
data class ConfigChunk(
    /** mpv profile name carrying this chunk alone. */
    val profileName: String,
    /** Short label for the UI, e.g. "scale=ewa_lanczossharp". */
    val label: String,
    /** Option lines belonging to this chunk, comments and blanks stripped. */
    val lines: List<String>,
)

/**
 * An ordered configuration to render, identified by the profile mpv applies for it.
 */
data class ConfigCandidate(
    val id: String,
    val label: String,
    /** Name of the profile to apply, or null for "no profile" (mpv defaults). */
    val profileName: String?,
) {
    companion object {
        /** The render configuration used for the baseline frame: whatever the user has configured. */
        const val ID_BASELINE = "baseline"
    }
}
