package org.jellyfin.mobile.perf

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadProgressTracker
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.ui.screens.downloads.DownloadsList
import org.jellyfin.mobile.ui.screens.downloads.rememberDownloadProgressLookup
import org.jellyfin.mobile.ui.screens.settings.SettingsScreen
import org.jellyfin.mobile.utils.koinInject
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * 调试构建专用：下载页在「下载进行中」时的帧压力复现页。
 *
 * 线上链路是 [DownloadProgressTracker] 接收 `FileDownloader` 每读一块 10KB 缓冲区就回调
 * 一次的进度（1MB/s ≈ 100 次/秒），列表订阅同一个 StateFlow 渲染百分比。这里用同样的
 * 组件、同样的频率驱动一批假条目，把那条链路原样跑起来，好让
 *
 *   adb shell dumpsys gfxinfo org.jellyfin.mobile.debug
 *
 * 的 jank 比例与每帧耗时在改动前后可比。日志里每秒打印一次列表的组合次数
 * （`PerfHarness: listCompositionsPerSecond=...`），它是重组规模最直接的度量。
 *
 * 只编译进 debug 变体，release 不包含。
 */
class PerfHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloadListStress()
                }
            }
        }
    }
}

/** 活动下载条目数：与 DownloadQueue 一致（队列串行，同一时刻只有一条在真正传输）。 */
private const val ACTIVE_DOWNLOADS = 1

/** 进度回调频率：1MB/s + 10KB 读缓冲 ≈ 100 次/秒。 */
private const val UPDATE_INTERVAL_MS = 10L

/** 列表行数：模拟真实用户的下载列表规模。 */
private const val ITEM_COUNT = 30

/**
 * 调试构建专用：设置页冒烟页。
 *
 * 设置页在把 `Column + verticalScroll` 换成 `LazyColumn`（按块拆 item）之后，需要确认
 * 所有条目仍然完整、顺序不变、可滚动。没有服务器也能打开，因此可以直接截图核对。
 */
class SettingsHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen(
                    onBack = {},
                    onOpenMpvConfigEditor = {},
                )
            }
        }
    }
}

@Composable
private fun DownloadListStress() {
    val tracker: DownloadProgressTracker = koinInject()
    val downloads = remember { fakeDownloads(ITEM_COUNT) }
    val activeIds = remember(downloads) { downloads.take(ACTIVE_DOWNLOADS).map { it.download.id } }

    // 模拟下载线程：与真实链路一样，按 10ms 的节奏往外推进度。
    LaunchedEffect(tracker) {
        var step = 0
        while (true) {
            val fraction = (step % 1000) / 1000f
            activeIds.forEach { id -> tracker.update(id, fraction) }
            step++
            delay(UPDATE_INTERVAL_MS)
        }
    }

    // 组合次数：不改状态（否则自身会触发重组），用普通数组计数，由下面的循环每秒打印。
    val compositionCounter = remember { IntArray(1) }
    LaunchedEffect(Unit) {
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val count = compositionCounter[0]
            compositionCounter[0] = 0
            Log.i("PerfHarness", "listCompositionsPerSecond=$count over ${now - lastAt}ms")
            lastAt = now
        }
    }

    val progress = rememberDownloadProgressLookup(tracker.progress.collectAsState())
    compositionCounter[0]++

    DownloadsList(
        downloads = downloads,
        onOpen = {},
        onDownload = {},
        progress = progress,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun fakeDownloads(count: Int): List<DownloadFiles> = List(count) { index ->
    val downloadId = index + 1L
    DownloadFiles(
        download = DownloadEntity(
            id = downloadId,
            serverId = 1L,
            userId = 1L,
            itemId = UUID.randomUUID(),
            path = "/downloads/item-$index",
            item = BaseItemDto(
                id = UUID.randomUUID(),
                name = "Performance Sample $index",
                type = BaseItemKind.MOVIE,
                runTimeTicks = 36_000_000_000L,
                productionYear = 2026,
            ),
            // 前 ACTIVE_DOWNLOADS 条处于下载中：只有它们会渲染进度行。
            status = if (index < ACTIVE_DOWNLOADS) DownloadStatus.DOWNLOADING else DownloadStatus.QUEUED,
        ),
        files = listOf(
            DownloadFileEntity(
                downloadId = downloadId,
                type = DownloadFileType.ITEM,
                size = 1_000_000_000L,
                fileName = "item-$index.mp4",
                uri = Uri.EMPTY,
                // 非 DOWNLOADED：StorageManager.verify 会在第一项直接返回 false，
                // 不触发 SAF 查询，测量到的就只剩列表本身的组合与绘制开销。
                status = DownloadStatus.QUEUED,
            ),
        ),
    )
}
