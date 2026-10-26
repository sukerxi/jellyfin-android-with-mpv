package org.jellyfin.mobile.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载进度的进程内广播。
 *
 * 进度原本只有通知栏在用（[DownloadNotificationManager] 自己算百分比），界面拿不到，
 * 于是列表里只能显示一条 indeterminate 进度条 —— 用户看不出下到哪儿了，也判断不了
 * 「卡住了没有」。这里把同一份百分比也放到一个可观察的状态里，供列表展示。
 *
 * 只放在内存：进度是瞬时信息，进程重启后下载会重新排队并自己再报一次，不需要持久化。
 */
class DownloadProgressTracker {

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** downloadId -> 0f..1f。 */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    fun update(downloadId: Long, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        // 同一个值反复写会让读取方白白重组，先比一下
        if (_progress.value[downloadId] == clamped) return
        _progress.value = _progress.value + (downloadId to clamped)
    }

    fun clear(downloadId: Long) {
        if (downloadId !in _progress.value) return
        _progress.value = _progress.value - downloadId
    }
}
