package org.jellyfin.mobile.player.mpv.bench

/**
 * One complete configuration to render, i.e. a whole `mpv.conf` worth of options.
 *
 * Candidates carry the *entire* config text instead of an incremental patch on purpose: every
 * candidate is rendered by a fresh mpv instance that reads the file at startup, so nothing carries
 * over from the previous candidate and no option has to be un-applied afterwards. Incremental
 * application is what made the previous implementation's results depend on the order it happened to
 * run in (applying a profile cannot reset another profile's options).
 */
data class BenchCandidate(
    val id: String,
    /** Short label for the UI: the option line that differs, already formatted for display. */
    val label: String,
    /** Complete `mpv.conf` content. */
    val configText: String,
    /** True for the frame rendered with the configuration playback is using right now. */
    val isBaseline: Boolean = false,
)

/**
 * Turns "the config playback uses" plus "the config the user just edited" into the list of
 * configurations worth rendering.
 *
 * The model is deliberately line oriented, because that is how the user edits `mpv.conf`:
 *
 * - the baseline is the saved configuration - the one the frame on screen was produced with;
 * - every option that was added, changed or removed relative to it becomes one candidate, so the
 *   user sees the effect of that single change;
 * - when more than one change is pending, the edited configuration itself is added as the last
 *   candidate, which is what the user would end up with.
 *
 * Candidates are built by editing the *baseline* text, not by appending to it, so a candidate can
 * also *undo* one of the user's current options. Comment and profile lines are preserved: the
 * comparison renders the same file mpv would read.
 */
object MpvBenchConfig {

    /** Id of the candidate that carries the whole edited configuration. */
    const val ALL_ID = "all"

    /** Id of the baseline candidate: the saved configuration. */
    const val BASELINE_ID = "baseline"

    /** The configuration normal playback is currently using. */
    fun baseline(config: String): BenchCandidate = BenchCandidate(
        id = BASELINE_ID,
        label = "",
        configText = config.trim(),
        isBaseline = true,
    )

    /**
     * Every single difference between [baselineConfig] and [editedConfig], plus the edited
     * configuration itself when there is more than one.
     *
     * Empty when the two are equivalent, which the UI reports instead of rendering identical
     * frames: a comparison with nothing changed cannot answer anything.
     */
    fun changes(baselineConfig: String, editedConfig: String): List<BenchCandidate> {
        val baselineLines = baselineConfig.lines()
        val baselineOptions = optionsOf(baselineConfig)
        val editedOptions = optionsOf(editedConfig)
        val baselineByName = baselineOptions.associateBy { it.name }
        val editedByName = editedOptions.associateBy { it.name }

        val changes = mutableListOf<BenchCandidate>()

        editedByName.values.forEach { edited ->
            val current = baselineByName[edited.name]
            when {
                current == null -> changes += BenchCandidate(
                    id = "add:${edited.name}",
                    label = edited.text,
                    configText = insertOption(baselineLines, edited.text),
                )
                current.text != edited.text -> changes += BenchCandidate(
                    id = "set:${edited.name}",
                    label = edited.text,
                    configText = replaceOption(baselineLines, edited.name, edited.text),
                )
            }
        }
        baselineByName.values.forEach { current ->
            if (current.name !in editedByName) {
                changes += BenchCandidate(
                    id = "remove:${current.name}",
                    label = "-${current.text}",
                    configText = removeOption(baselineLines, current.name),
                )
            }
        }

        if (changes.size > 1) {
            changes += BenchCandidate(
                id = ALL_ID,
                label = "",
                configText = editedConfig.trim(),
            )
        }
        return changes
    }

    /** An option assignment, as written in the config file. */
    private data class OptionLine(val name: String, val text: String)

    /**
     * Option lines of [config], in file order. Comments, blank lines, section headers and anything
     * without an `=` are not assignments and are left out.
     */
    private fun optionsOf(config: String): List<OptionLine> = config.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            line.startsWith("#") -> null
            line.startsWith("[") -> null
            !line.contains('=') -> null
            else -> OptionLine(name = line.substringBefore('=').trim(), text = line)
        }
    }

    /**
     * [baseline] with [option] added to the global section.
     *
     * The global section ends at the first `[profile]` header: options written after one belong to
     * that profile, so inserting there would change their meaning.
     */
    private fun insertOption(baseline: List<String>, option: String): String {
        val lines = baseline.toMutableList()
        val firstSection = lines.indexOfFirst { it.trim().startsWith("[") }
        lines.add(if (firstSection >= 0) firstSection else lines.size, option)
        return lines.joinToString("\n").trim()
    }

    /** [baseline] with every assignment of [name] replaced by [option]. */
    private fun replaceOption(baseline: List<String>, name: String, option: String): String =
        baseline.joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith("[") -> raw
                line.contains('=') && line.substringBefore('=').trim() == name -> option
                else -> raw
            }
        }.trim()

    /** [baseline] without any assignment of [name]. */
    private fun removeOption(baseline: List<String>, name: String): String =
        baseline.filterNot { raw ->
            val line = raw.trim()
            line.isNotEmpty() &&
                !line.startsWith("#") &&
                !line.startsWith("[") &&
                line.contains('=') &&
                line.substringBefore('=').trim() == name
        }.joinToString("\n").trim()
}
