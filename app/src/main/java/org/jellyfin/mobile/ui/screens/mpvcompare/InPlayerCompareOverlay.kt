package org.jellyfin.mobile.ui.screens.mpvcompare

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.player.mpv.bench.BenchCandidate
import org.jellyfin.mobile.player.mpv.bench.ComparedFrame
import org.jellyfin.mobile.player.mpv.bench.FrameCompareViewModel
import org.jellyfin.mobile.player.mpv.bench.MpvBenchConfig
import org.jellyfin.mobile.player.mpv.bench.MpvConfigCompareState
import org.jellyfin.mobile.player.mpv.bench.PlaybackSceneCapture
import org.jellyfin.mobile.ui.screens.player.ControlsScrim
import org.jellyfin.mobile.ui.screens.player.formatSeekOverlayTime

/**
 * The comparison overlay of the in-player variant.
 *
 * Opened from the player controls, it sits above the video the same way the info panel does, so the
 * user never leaves the item they are watching. Everything it does goes through the shared
 * [FrameCompareViewModel]; the overlay itself only pauses playback, freezes the current frame, edits
 * the configuration, starts runs and shows the resulting frames.
 *
 * The one thing it deliberately does *not* do is apply anything to the running player: the frames
 * are rendered on throwaway mpv instances (see `MpvBenchInstance`), so closing the overlay leaves
 * playback exactly as it was.
 *
 * ## Why the frame pane owns its whole column
 *
 * A phone in landscape is ~200 dp shorter than the same phone in portrait, and the frame pane is
 * the only flexible child of the overlay. Everything that sat *below* it inside the pane (the
 * display-mode chips, the zoom chips, the summary, the change navigation) therefore used to eat the
 * pane's entire height before the image got a single pixel: a run would finish, the frames were
 * rendered, and the screen showed emptiness - "spinner stops, nothing appears". The portrait layout
 * has the height to stack those rows under the image, landscape does not.
 *
 * Landscape therefore splits the overlay in two: the left column is nothing but the image, the right
 * column is a scrollable stack of the display options, the editor and a pinned action row. The
 * image can never be squeezed out, whatever the aspect ratio, font scale or number of controls is.
 */
@Composable
fun InPlayerCompareOverlay(
    viewModel: PlayerViewModel,
    compareViewModel: FrameCompareViewModel,
    viewportSize: () -> IntSize,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = compareViewModel.controller

    val state by controller.state.collectAsStateWithLifecycle()
    val editedConfig by controller.editedConfig.collectAsStateWithLifecycle()
    val baselineConfig = controller.baselineConfig

    var sceneFailed by remember { mutableStateOf(false) }

    // Freeze the scene from the frame the user is looking at. The video is paused first so the
    // captured position is the one that is actually on screen, and resumed when the overlay closes
    // - but only if this overlay is what paused it.
    DisposableEffect(Unit) {
        val player = viewModel.playerOrNull
        val wasPlaying = player?.playWhenReady == true
        player?.pause()
        onDispose {
            if (wasPlaying) viewModel.playerOrNull?.play()
        }
    }

    LaunchedEffect(Unit) {
        val viewport = viewportSize()
        val spec = PlaybackSceneCapture.capture(
            player = viewModel.playerOrNull,
            mediaSource = viewModel.mediaSourceOrNull,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
        )
        if (spec == null) sceneFailed = true else controller.setScene(spec)
    }

    // Rendering a candidate is the only way to know whether the edited config still differs from the
    // saved one; comparing the two texts is cheap and does not need a run.
    val hasChanges = remember(editedConfig, baselineConfig) {
        MpvBenchConfig.changes(baselineConfig, editedConfig).isNotEmpty()
    }
    val allChangesLabel = stringResource(R.string.mpv_compare_all_changes)
    val compared = remember(state.compared, allChangesLabel) {
        state.compared.withDisplayLabels(allChangesLabel)
    }
    val baselineBitmap = remember(state.baseline) { state.baseline?.toImageBitmap() }

    var selectedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(compared.size) {
        selectedIndex = selectedIndex.coerceIn(0, (compared.size - 1).coerceAtLeast(0))
    }
    val selected = compared.getOrNull(selectedIndex)

    // Hoisted out of the frame pane: in landscape the options sit in a different column than the
    // image, and the two halves still have to agree on what is being shown.
    var displayMode by remember { mutableStateOf(CompareDisplayMode.SWIPE) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val onRun: () -> Unit = {
        val invalid = MpvConfigManager.findInvalidLine(editedConfig)
        when {
            invalid != null -> Toast.makeText(
                context,
                context.getString(R.string.mpv_config_editor_invalid_line, invalid),
                Toast.LENGTH_LONG,
            ).show()
            // The action stays enabled in this case on purpose: a dead grey button tells the user
            // nothing, a toast says exactly why nothing will be rendered.
            !hasChanges -> Toast.makeText(context, R.string.mpv_compare_no_changes, Toast.LENGTH_SHORT).show()
            else -> controller.run()
        }
    }

    // The first change that produced no picture at all, with the reason mpv gave. Shown in the red
    // line - "some changes failed" alone leaves the user guessing which option and why.
    val failedChange = remember(compared) { compared.firstOrNull { it.frame.failure != null } }
    val failedReason = remember(failedChange) { failedChange?.let(::describeFailure) }

    var applyAnyway by remember { mutableStateOf(false) }

    val commitConfig: () -> Unit = {
        compareViewModel.commit(editedConfig)
        Toast.makeText(context, R.string.mpv_compare_applied, Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    val onApply: () -> Unit = {
        // Applying a configuration that just failed to render would very likely leave normal
        // playback without a picture, so that decision gets its own confirmation.
        if (failedChange != null) applyAnyway = true else commitConfig()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ControlsScrim),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isLandscape) 16.dp else 12.dp,
                    vertical = if (isLandscape) 12.dp else 56.dp,
                ),
        ) {
            Column(modifier = Modifier.padding(if (isLandscape) 12.dp else 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.mpv_compare_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_white_24dp),
                            contentDescription = stringResource(R.string.mpv_compare_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                state.spec?.let { spec ->
                    Text(
                        text = stringResource(
                            R.string.mpv_compare_scene_info,
                            formatSeekOverlayTime(spec.positionMs),
                            spec.viewportWidth,
                            spec.viewportHeight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Image only: this column must never compete with controls for height.
                        FrameArea(
                            state = state,
                            sceneFailed = sceneFailed,
                            baseline = baselineBitmap,
                            selected = selected,
                            mode = displayMode,
                            zoom = zoom,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (selected != null) {
                                    CompareDetails(
                                        compared = compared,
                                        selectedIndex = selectedIndex,
                                        onSelectIndex = { selectedIndex = it },
                                        mode = displayMode,
                                        onModeChange = { displayMode = it },
                                        zoom = zoom,
                                        onZoomChange = { zoom = it },
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                CompareEditor(
                                    state = state,
                                    editedConfig = editedConfig,
                                    onEditedConfigChange = controller::setEditedConfig,
                                    hasChanges = hasChanges,
                                    failedReason = failedReason,
                                    editorHeight = 72.dp,
                                )
                            }
                            CompareActions(
                                state = state,
                                hasChanges = hasChanges,
                                onRun = onRun,
                                onCancel = controller::cancel,
                                onApply = onApply,
                            )
                        }
                    }
                } else {
                    FrameArea(
                        state = state,
                        sceneFailed = sceneFailed,
                        baseline = baselineBitmap,
                        selected = selected,
                        mode = displayMode,
                        zoom = zoom,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    )
                    if (selected != null) {
                        CompareDetails(
                            compared = compared,
                            selectedIndex = selectedIndex,
                            onSelectIndex = { selectedIndex = it },
                            mode = displayMode,
                            onModeChange = { displayMode = it },
                            zoom = zoom,
                            onZoomChange = { zoom = it },
                        )
                    }
                    CompareEditor(
                        state = state,
                        editedConfig = editedConfig,
                        onEditedConfigChange = controller::setEditedConfig,
                        hasChanges = hasChanges,
                        failedReason = failedReason,
                        editorHeight = 96.dp,
                    )
                    CompareActions(
                        state = state,
                        hasChanges = hasChanges,
                        onRun = onRun,
                        onCancel = controller::cancel,
                        onApply = onApply,
                    )
                }
            }
        }
    }

    if (applyAnyway) {
        AlertDialog(
            onDismissRequest = { applyAnyway = false },
            title = { Text(text = stringResource(R.string.mpv_compare_apply_failed_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.mpv_compare_apply_failed_message,
                        failedReason.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        applyAnyway = false
                        commitConfig()
                    },
                ) {
                    Text(text = stringResource(R.string.mpv_compare_apply_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { applyAnyway = false }) {
                    Text(text = stringResource(R.string.download_cancel))
                }
            },
        )
    }
}

/** The frame pane: the current run, its result, or the reason there is neither. */
@Composable
private fun FrameArea(
    state: MpvConfigCompareState,
    sceneFailed: Boolean,
    baseline: ImageBitmap?,
    selected: ComparedFrame?,
    mode: CompareDisplayMode,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    val candidate = selected?.frame?.image
    Box(modifier = modifier) {
        when {
            sceneFailed -> EmptyState(stringResource(R.string.mpv_compare_scene_required))
            state.spec == null -> EmptyState(stringResource(R.string.mpv_compare_capture_scene_in_progress))
            state.isRunning -> RunningState(state.completed, state.total)
            baseline != null && selected != null -> CompareFramePane(
                baseline = baseline,
                selected = selected,
                mode = mode,
                zoom = zoom,
                modifier = Modifier.fillMaxSize(),
            )
            // The baseline failed but this candidate did render. Showing the frame with a note
            // beats an error message alone: the render worked, it just has nothing to be compared
            // against.
            candidate != null -> Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.mpv_compare_baseline_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                SingleFrame(
                    frame = remember(candidate) { candidate.toImageBitmap() },
                    zoom = zoom,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            // A run that produced nothing still has a reason worth showing, and so has a
            // session that has not run yet.
            state.failure != null -> EmptyState(state.failure)
            state.error != null -> EmptyState(state.error)
            else -> EmptyState(stringResource(R.string.mpv_compare_no_result))
        }
    }
}

/** The image of one candidate against the baseline; nothing else. */
@Composable
private fun CompareFramePane(
    baseline: ImageBitmap,
    selected: ComparedFrame,
    mode: CompareDisplayMode,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    // Decoded once per frame: re-decoding on every recomposition would drop frames while dragging
    // the swipe handle.
    val candidateBitmap = remember(selected.frame.image) { selected.frame.image?.toImageBitmap() }
    val differenceBitmap = remember(selected.comparison) { selected.comparison?.difference?.toImageBitmap() }

    Box(modifier = modifier) {
        val frame = candidateBitmap
        if (frame == null) {
            // A candidate can legitimately fail to render (its options may be unsupported on this
            // device). Saying why beats an empty pane the user cannot interpret.
            EmptyState(selected.frame.failure ?: stringResource(R.string.mpv_compare_no_result))
            return@Box
        }
        when (mode) {
            CompareDisplayMode.SWIPE -> SwipeCompare(
                baseline = baseline,
                candidate = frame,
                difference = null,
                zoom = zoom,
            )
            CompareDisplayMode.DIFFERENCE -> SwipeCompare(
                baseline = baseline,
                candidate = frame,
                difference = differenceBitmap,
                zoom = zoom,
            )
            CompareDisplayMode.BLINK -> {
                var showBaseline by remember { mutableStateOf(true) }
                BlinkCompare(
                    baseline = baseline,
                    candidate = frame,
                    showBaseline = showBaseline,
                    zoom = zoom,
                    modifier = Modifier.fillMaxSize(),
                )
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(600)
                        showBaseline = !showBaseline
                    }
                }
            }
        }
    }
}

/**
 * Display mode, zoom, the delta summary and the change navigation.
 *
 * Kept separate from the image so it can be stacked under the frame in portrait and parked in its
 * own column in landscape.
 */
@Composable
private fun CompareDetails(
    compared: List<ComparedFrame>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    mode: CompareDisplayMode,
    onModeChange: (CompareDisplayMode) -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = compared.getOrNull(selectedIndex) ?: return

    Column(modifier = modifier.fillMaxWidth()) {
        // The display modes and the zoom steps each get their own row: on a phone-sized screen a
        // single row cannot fit six chips. Both scroll horizontally so a narrow column can never
        // clip a chip away.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompareDisplayMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { onModeChange(entry) },
                    label = { Text(stringResource(entry.labelRes)) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.mpv_compare_zoom),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            COMPARE_ZOOM_STEPS.forEach { step ->
                FilterChip(
                    selected = zoom == step,
                    onClick = { onZoomChange(step) },
                    label = { Text("${step.toInt()}x") },
                )
            }
        }

        ComparisonSummary(compared = selected, index = selectedIndex, total = compared.size)

        if (compared.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = selectedIndex > 0,
                    onClick = { onSelectIndex(selectedIndex - 1) },
                ) {
                    Text(text = stringResource(R.string.mpv_compare_previous_change))
                }
                Text(text = "${selectedIndex + 1} / ${compared.size}")
                TextButton(
                    enabled = selectedIndex < compared.lastIndex,
                    onClick = { onSelectIndex(selectedIndex + 1) },
                ) {
                    Text(text = stringResource(R.string.mpv_compare_next_change))
                }
            }
        }
    }
}

/** The configuration editor plus everything that explains it. */
@Composable
private fun CompareEditor(
    state: MpvConfigCompareState,
    editedConfig: String,
    onEditedConfigChange: (String) -> Unit,
    hasChanges: Boolean,
    failedReason: String?,
    editorHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ConfigEditor(
            value = editedConfig,
            onValueChange = onEditedConfigChange,
            enabled = !state.isRunning,
            height = editorHeight,
        )

        if (!hasChanges) {
            Text(
                text = stringResource(R.string.mpv_compare_unchanged),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Only for a partial failure: when nothing rendered, the frame pane already carries the
        // reason and repeating it here would just push the buttons around.
        if (failedReason != null && state.hasResult) {
            Text(
                text = stringResource(R.string.mpv_compare_some_failed_reason, failedReason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The action row.
 *
 * Pinned to the bottom of its column in landscape: the editor above it scrolls, so the primary
 * action must not be the thing that scrolls off.
 */
@Composable
private fun CompareActions(
    state: MpvConfigCompareState,
    hasChanges: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isRunning) {
            // The run can end up stuck in native code, so "stop" has to be reachable while it
            // is going: this used to be a disabled "compare" button, i.e. a dead end.
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.mpv_compare_cancel))
            }
        } else {
            TextButton(
                enabled = state.spec != null,
                onClick = onRun,
            ) {
                Text(
                    text = stringResource(
                        if (state.hasResult) R.string.mpv_compare_rerun else R.string.mpv_compare_run,
                    ),
                )
            }
        }
        Button(
            enabled = !state.isRunning && state.hasResult && hasChanges,
            onClick = onApply,
        ) {
            Text(text = stringResource(R.string.mpv_compare_apply))
        }
    }
}

/**
 * "vo=gpu1：该配置无法初始化视频输出（vo=gpu1）" - which change failed, and why.
 *
 * The option line is what the user edited, the reason is what mpv said when the frame never
 * appeared; both are needed to act on the failure.
 */
private fun describeFailure(entry: ComparedFrame): String {
    val line = entry.candidate.label.ifBlank { entry.candidate.id }
    val reason = entry.frame.failure.orEmpty()
    return if (reason.isEmpty()) line else "$line：$reason"
}

/** The "all changes" candidate carries no option line, so its label comes from resources. */
private fun List<ComparedFrame>.withDisplayLabels(allChangesLabel: String): List<ComparedFrame> =
    map { entry ->
        if (entry.candidate.id == MpvBenchConfig.ALL_ID) {
            entry.copy(candidate = entry.candidate.withLabel(allChangesLabel))
        } else {
            entry
        }
    }

private fun BenchCandidate.withLabel(label: String): BenchCandidate = copy(label = label)

/** How the two frames are presented. */
enum class CompareDisplayMode(val labelRes: Int) {
    SWIPE(R.string.mpv_compare_mode_swipe),
    BLINK(R.string.mpv_compare_mode_blink),
    DIFFERENCE(R.string.mpv_compare_mode_difference),
}

/** Configuration text editor used by every comparison entry point. */
@Composable
fun ConfigEditor(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(text = stringResource(R.string.mpv_config_editor_title)) },
        placeholder = { Text(text = stringResource(R.string.mpv_config_editor_hint)) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 4,
        modifier = modifier.fillMaxWidth().height(height).padding(top = 8.dp),
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunningState(completed: Int, total: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = if (total > 0 && completed > 0) {
                    stringResource(R.string.mpv_compare_progress, completed, total)
                } else {
                    stringResource(R.string.mpv_compare_running)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            // Says how to get out of a run that never reports back, instead of leaving the user
            // with a spinner and disabled buttons.
            Text(
                text = stringResource(R.string.mpv_compare_running_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
