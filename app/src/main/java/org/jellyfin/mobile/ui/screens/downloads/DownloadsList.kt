package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.utils.koinInject

@Composable
fun DownloadsList(
    downloads: List<DownloadFiles>,
    onOpen: (DownloadEntity) -> Unit,
    onDownload: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
    onToggleSelection: (DownloadEntity) -> Unit = {},
    progress: Map<Long, Float> = emptyMap(),
    onCancel: (DownloadEntity) -> Unit = {},
) {
    val selectionMode = selection.isNotEmpty()

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(
            downloads,
            key = { it.download.id },
        ) { downloadFiles ->
            DownloadItem(
                downloadFiles = downloadFiles,
                onOpen = { onOpen(downloadFiles.download) },
                onDownload = { onDownload(downloadFiles.download) },
                onToggleSelection = { onToggleSelection(downloadFiles.download) },
                onCancel = { onCancel(downloadFiles.download) },
                isSelected = selection.contains(downloadFiles.download.id),
                selectionMode = selectionMode,
                progress = progress[downloadFiles.download.id],
            )
        }
    }
}

@Composable
fun DownloadItem(
    downloadFiles: DownloadFiles,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    /** 0f..1f；null 表示还没拿到进度。 */
    progress: Float? = null,
    onCancel: () -> Unit = {},
) {
    val (download, files) = downloadFiles
    val context = LocalContext.current
    val storageManager: StorageManager = koinInject()

    val isVerified by produceState(initialValue = false, downloadFiles) {
        value = withContext(Dispatchers.IO) {
            storageManager.verify(downloadFiles)
        }
    }

    // 未通过校验的条目原先单击就直接开始重新下载：用户以为要打开，结果流量被悄悄用掉了。
    // 现在先问一句，重新下载成为显式选择。
    var confirmRedownload by remember { mutableStateOf(false) }

    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            title = { Text(text = stringResource(R.string.download_incomplete)) },
            text = { Text(text = stringResource(R.string.download_incomplete_redownload_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRedownload = false
                        onDownload()
                    },
                ) {
                    Text(text = stringResource(R.string.download_redownload))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRedownload = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    ListItem(
        modifier = modifier
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        !isVerified -> confirmRedownload = true
                        else -> onOpen()
                    }
                },
                onLongClick = { onToggleSelection() },
            ),
        headlineContent = {
            val name = remember(download, context) { download.getDisplayName(context).orEmpty() }
            Text(
                text = name,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                maxLines = 2,
            )
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }

                val uri = remember(files) {
                    files.find { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri
                }

                AsyncImage(
                    model = uri,
                    placeholder = painterResource(R.drawable.ic_local_movies_white_64),
                    fallback = painterResource(R.drawable.ic_local_movies_white_64),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(
                        width = 64.dp,
                        height = 64.dp,
                    ),
                )
            }
        },
        supportingContent = {
            val inProgress = download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.QUEUED
            if (inProgress) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (progress != null) {
                        // 有百分比时用确定进度条并写出来：只有一条来回滚动的 indeterminate
                        // 时，用户无法判断是在下载还是卡住了。
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.download_progress, (progress * 100).roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.download_queued),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            } else if (isVerified) {
                Text(
                    text = Formatter.formatShortFileSize(context, files.sumOf { it.size }),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            } else {
                // 之前用 Color.Yellow：浅色主题下压在 surface 上的对比度约 1.6:1，几乎读不清，
                // 而且纯颜色还要用户自己记住「黄色 = 有问题」。改成主题的错误色 + 图标。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.download_incomplete),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        trailingContent = if (download.status == DownloadStatus.DOWNLOADING) {
            {
                IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.download_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        },
    )
}
