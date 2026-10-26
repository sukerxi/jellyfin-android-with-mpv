package org.jellyfin.mobile.ui.screens.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadsViewModel
import org.jellyfin.mobile.ui.navigation.BackNavigationDispatcher
import org.jellyfin.mobile.ui.navigation.RegisterBackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel(),
    onBackPressed: () -> Unit = {},
    backDispatcher: BackNavigationDispatcher,
) {
    val downloads by viewModel.downloads.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val storageLocation by viewModel.storageLocation.collectAsState()
    val storageLocationAccessible by viewModel.storageLocationAccessible.collectAsState()
    val selection = remember { mutableStateSetOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val selectionMode = selection.isNotEmpty()

    val storageLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.changeStorageLocation(uri)
    }

    // 多选模式下的返回只退选择，不退出页面；交给宿主的返回链统一仲裁，
    // 这样"多选 > 导航栈弹出"的优先级不再依赖各自 BackHandler 的注册顺序。
    if (selectionMode) {
        backDispatcher.RegisterBackHandler {
            selection.clear()
        }
    }

    if (showDeleteConfirm) {
        val downloadsToRemove = remember(selection, downloads) {
            downloads
                .map { it.download }
                .filter { it.id in selection }
        }
        DownloadRemoveDialog(
            downloads = downloadsToRemove,
            onConfirm = { keepLocalFiles ->
                downloadsToRemove.forEach { download ->
                    viewModel.removeDownload(download, deleteFiles = !keepLocalFiles)
                }
                selection.clear()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = selectionMode,
                        transitionSpec = {
                            if (targetState) {
                                (slideInVertically { height -> height } + fadeIn()) togetherWith
                                    (slideOutVertically { height -> -height } + fadeOut())
                            } else {
                                (slideInVertically { height -> -height } + fadeIn()) togetherWith
                                    (slideOutVertically { height -> height } + fadeOut())
                            }
                        },
                        label = "TitleAnimation",
                    ) { isSelectionMode ->
                        if (isSelectionMode) {
                            Text(text = stringResource(R.string.selected_count, selection.size))
                        } else {
                            Text(text = stringResource(R.string.downloads))
                        }
                    }
                },
                navigationIcon = {
                    Crossfade(
                        targetState = selectionMode,
                        label = "NavIconAnimation",
                    ) { isSelectionMode ->
                        if (isSelectionMode) {
                            IconButton(
                                onClick = { selection.clear() },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.close),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { onBackPressed() },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row {
                        // 非多选态也给出「选择」入口：多选此前只能靠长按进入，而长按没有任何
                        // 可见提示，首次使用者基本发现不了。
                        AnimatedVisibility(
                            visible = !selectionMode && downloads.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            IconButton(
                                onClick = {
                                    downloads.firstOrNull()?.let { selection.add(it.download.id) }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_checklist_24dp),
                                    contentDescription = stringResource(R.string.download_select),
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = selectionMode,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Row {
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                    )
                                }

                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.MoreVert,
                                            contentDescription = stringResource(R.string.more_options),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                    ) {
                                        if (selection.size < downloads.size) {
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(R.string.select_all)) },
                                                onClick = {
                                                    selection.addAll(downloads.map { it.download.id })
                                                    showMenu = false
                                                },
                                            )
                                        }

                                        if (selection.isNotEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(R.string.deselect_all)) },
                                                onClick = {
                                                    selection.clear()
                                                    showMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (storageLocation == null) {
                    StorageLocationNotSetCard(
                        onFix = { storageLocationPicker.launch(null) },
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (!storageLocationAccessible) {
                    StorageLocationInaccessibleCard(
                        onFix = { storageLocationPicker.launch(null) },
                        modifier = Modifier.padding(16.dp),
                    )
                }

                if (downloads.isEmpty()) {
                    DownloadsEmpty(modifier = Modifier.weight(1f))
                } else {
                    DownloadsList(
                        downloads = downloads,
                        onOpen = { viewModel.openDownload(it) },
                        onDownload = { viewModel.download(it) },
                        onCancel = { viewModel.cancelDownload(it) },
                        selection = selection,
                        onToggleSelection = { download ->
                            if (selection.contains(download.id)) selection.remove(download.id)
                            else selection.add(download.id)
                        },
                        progress = downloadProgress,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
fun StorageLocationNotSetCard(
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
    ),
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.download_location_not_set),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.download_location_not_set_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onFix,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = stringResource(R.string.select_folder))
        }
    }
}

@Composable
fun StorageLocationInaccessibleCard(
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
    ),
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.download_location_inaccessible),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.download_location_inaccessible_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onFix,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = stringResource(R.string.select_folder))
        }
    }
}

@Composable
fun DownloadsEmpty(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
) = Box(
    modifier = modifier
        .fillMaxSize()
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.downloads_empty),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.downloads_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
