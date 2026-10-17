package org.jellyfin.mobile.ui.screens.mpvcompare

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ControlsScrim),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 56.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        sceneFailed -> EmptyState(stringResource(R.string.mpv_compare_scene_required))
                        state.spec == null ->
                            EmptyState(stringResource(R.string.mpv_compare_capture_scene_in_progress))
                        state.isRunning -> RunningState(state.completed, state.total)
                        baselineBitmap != null && compared.isNotEmpty() -> CompareBody(
                            baseline = baselineBitmap,
                            compared = compared,
                            selectedIndex = selectedIndex,
                            onSelectIndex = { selectedIndex = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // A run that produced nothing still has a reason worth showing, and so has a
                        // session that has not run yet.
                        state.failure != null -> EmptyState(state.failure!!)
                        state.error != null -> EmptyState(state.error!!)
                        else -> EmptyState(stringResource(R.string.mpv_compare_no_result))
                    }
                }

                ConfigEditor(
                    value = editedConfig,
                    onValueChange = controller::setEditedConfig,
                    enabled = !state.isRunning,
                )

                if (!hasChanges) {
                    Text(
                        text = stringResource(R.string.mpv_compare_unchanged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.compared.any { it.frame.failure != null }) {
                        Text(
                            text = stringResource(R.string.mpv_compare_some_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = !state.isRunning && state.spec != null && hasChanges,
                        onClick = {
                            val invalid = MpvConfigManager.findInvalidLine(editedConfig)
                            if (invalid != null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.mpv_config_editor_invalid_line, invalid),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                controller.run()
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (state.hasResult) R.string.mpv_compare_rerun else R.string.mpv_compare_run,
                            ),
                        )
                    }
                    Button(
                        enabled = !state.isRunning && state.hasResult && hasChanges,
                        onClick = {
                            compareViewModel.commit(editedConfig)
                            Toast.makeText(context, R.string.mpv_compare_applied, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                    ) {
                        Text(text = stringResource(R.string.mpv_compare_apply))
                    }
                }
            }
        }
    }
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

/** Frame display plus per-change navigation, shared by every comparison entry point. */
@Composable
fun CompareBody(
    baseline: ImageBitmap,
    compared: List<ComparedFrame>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = compared.getOrNull(selectedIndex) ?: return
    // Decoded once per frame: re-decoding on every recomposition would drop frames while dragging
    // the swipe handle.
    val candidateBitmap = remember(selected.frame.image) { selected.frame.image?.toImageBitmap() }
    val differenceBitmap = remember(selected.comparison) { selected.comparison?.difference?.toImageBitmap() }

    Column(modifier = modifier.fillMaxWidth()) {
        var mode by remember { mutableStateOf(CompareDisplayMode.SWIPE) }
        var zoom by remember { mutableStateOf(1f) }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val frame = candidateBitmap
            if (frame == null) {
                // A candidate can legitimately fail to render (its options may be unsupported on
                // this device). Saying why beats an empty pane the user cannot interpret.
                EmptyState(selected.frame.failure ?: stringResource(R.string.mpv_compare_no_result))
            } else {
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompareDisplayMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    label = { Text(stringResource(entry.labelRes)) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            COMPARE_ZOOM_STEPS.forEach { step ->
                FilterChip(
                    selected = zoom == step,
                    onClick = { zoom = step },
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(text = stringResource(R.string.mpv_config_editor_title)) },
        placeholder = { Text(text = stringResource(R.string.mpv_config_editor_hint)) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 4,
        modifier = modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp),
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
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
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
