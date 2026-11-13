package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * 下载进度的只读视图：把 `State<Map<Long, Float>>` 包成一个可以按 id 取值的小对象。
 *
 * 存在的理由是**读取点**。原先 `DownloadsScreen` 用 `by ...collectAsState()` 在页面顶层
 * 读取进度 Map，再作为普通参数传给列表 —— 每一次进度发布都会重组整页，而 Map 在 Compose
 * 眼里是不稳定类型，`DownloadsList` 因此永远不可跳过，可见的每一行都跟着重组一次。
 *
 * 这个类本身是 [Stable] 的（引用不变），所以把它传下去不会让列表失效；真正的读取发生在
 * 调用 [get] 的那一层（下载行里 `supportingContent` 的 lambda），范围就只剩那一行的进度区。
 * 进度发布频率由上层的 [org.jellyfin.mobile.downloads.DownloadProgressTracker] 负责节流。
 */
@Stable
class DownloadProgressLookup(
    private val progress: State<Map<Long, Float>>,
) {
    /** @return 0f..1f；null 表示这条下载还没报过进度。 */
    operator fun get(downloadId: Long): Float? = progress.value[downloadId]

    companion object {
        /** 默认值：不在下载中的列表（如离线浏览）用这个，避免各处判空。 */
        val Empty: DownloadProgressLookup = DownloadProgressLookup(mutableStateOf(emptyMap()))
    }
}

/** 组合期获取一个稳定的 [DownloadProgressLookup]：State 引用不变，因此只会创建一次。 */
@Composable
fun rememberDownloadProgressLookup(progress: State<Map<Long, Float>>): DownloadProgressLookup =
    remember(progress) { DownloadProgressLookup(progress) }
