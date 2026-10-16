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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.mpv.MpvConfigManager
import org.jellyfin.mobile.player.mpv.bench.FrameCompareViewModel
import org.jellyfin.mobile.player.mpv.bench.PlaybackSceneCapture
import org.jellyfin.mobile.ui.screens.player.ControlsScrim
import org.jellyfin.mobile.ui.screens.player.formatSeekOverlayTime

/**
 * The comparison overlay of the in-player variant.
 *
 * Opened from the player controls, it sits above the video the same way the info panel does, so the
 * user never leaves the item they are watching. Everything it does goes through the shared
 * [FrameCompareViewModel]; the overlay itself only edits the configuration, starts runs and shows
 * the resulting frames.
 */
@Composable
fun InPlayerCompareOverlay(
    viewModel: PlayerViewModel,
    compareViewModel: FrameCompareViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    val state by compareViewModel.controller.state.collectAsStateWithLifecycle()
    val editedConfig by compareViewModel.controller.editedConfig.collectAsStateWithLifecycle()
    val controller = compareViewModel.controller

    // The scene is captured the first time the overlay appears: the user got here from the frame
    // they were looking at, so that frame is the scene.
    var captureFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val spec = PlaybackSceneCapture.capture(
            player = viewModel.playerOrNull,
            mediaSource = viewModel.mediaSourceOrNull,
            viewportWidth = configuration.screenWidthDp,
            viewportHeight = configuration.screenHeightDp,
        )
        if (spec == null) captureFailed = true else controller.setScene(spec)
    }

    // Leaving the overlay must not leave the generated profiles behind.
    DisposableEffect(Unit) {
        onDispose { controller.release(compareViewModel.swapper()) }
    }

    val baseline = state.baseline?.image?.toImageBitmap()
    val compared = state.compared
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

                when {
                    captureFailed -> EmptyState(stringResource(R.string.mpv_compare_scene_required))
                    state.spec == null -> EmptyState(stringResource(R.string.mpv_compare_capture_scene_in_progress))
                    state.isRunning -> LoadingState()
                    baseline != null && compared.isNotEmpty() -> {
                        CompareBody(
                            baseline = baseline,
                            compared = compared,
                            selectedIndex = selectedIndex,
                            onSelectIndex = { selectedIndex = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    state.error != null -> EmptyState(state.error!!)
                }

                ConfigEditor(
                    value = editedConfig,
                    onValueChange = controller::setEditedConfig,
                    enabled = !state.isRunning,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = !state.isRunning,
                        onClick = {
                            val invalid = MpvConfigManager.findInvalidLine(editedConfig)
                            if (invalid != null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.mpv_config_editor_invalid_line, invalid),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                controller.run(compareViewModel.rendererInstance(), compareViewModel.swapper())
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (state.capture == null) R.string.mpv_compare_run else R.string.mpv_compare_rerun,
                            ),
                        )
                    }
                    Button(
                        enabled = state.hasVisibleDifference,
                        onClick = {
                            compareViewModel.commit(editedConfig)
                            Toast.makeText(context, R.string.mpv_compare_applied, Toast.LENGTH_SHORT).show()
                            scope.launch { onDismiss() }
                        },
                    ) {
                        Text(text = stringResource(R.string.mpv_compare_apply))
                    }
                }
            }
        }
    }
}

/** Frame display plus per-change navigation, shared by the in-player overlay and the workbench. */
@Composable
fun CompareBody(
    baseline: ImageBitmap,
    compared: List<org.jellyfin.mobile.player.mpv.bench.ComparedFrame>,
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
            when (mode) {
                CompareDisplayMode.SWIPE -> if (candidateBitmap != null) {
                    SwipeCompare(
                        baseline = baseline,
                        candidate = candidateBitmap,
                        difference = null,
                        zoom = zoom,
                    )
                }
                CompareDisplayMode.DIFFERENCE -> if (candidateBitmap != null) {
                    SwipeCompare(
                        baseline = baseline,
                        candidate = candidateBitmap,
                        difference = differenceBitmap,
                        zoom = zoom,
                    )
                }
                CompareDisplayMode.BLINK -> if (candidateBitmap != null) {
                    var showBaseline by remember { mutableStateOf(true) }
                    BlinkCompare(
                        baseline = baseline,
                        candidate = candidateBitmap,
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
    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.mpv_compare_running),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
