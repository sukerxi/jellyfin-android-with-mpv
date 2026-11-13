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
 *
 * ## 发布节流
 *
 * 上游 `FileDownloader.save` 每读满一块 10KB 缓冲区就回调一次，1 MB/s 就是 100 次/秒；
 * 每次发布都要复制一份 Map 并唤醒所有订阅方，界面那边就是一次整页重组。这个频率远超
 * 「人眼能看出进度条变化」所需，因此本类在**发布侧**按 [publishIntervalMs] 合并：窗口内
 * 的中间值直接丢弃，只保留窗口结束后到达的最新值。
 *
 * 节流放在这里而不是各个订阅方，是因为发布节奏本身就是这个类的职责；通知栏那份百分比
 * 也是同一套思路（见 [DownloadNotificationManager]），两边一致才不会一个跳得飞快、
 * 一个却像卡住。完成态（1f）不受窗口限制，立即发布。
 *
 * @param publishIntervalMs 两次发布之间的最小间隔，默认 100ms（10Hz）
 * @param elapsedRealtimeMs 单调时钟，注入以便单测控制时间
 */
class DownloadProgressTracker(
    private val publishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val publishLock = Any()
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** downloadId -> 0f..1f。 */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    /** downloadId -> 上次真正发布的时刻，节流用。 */
    private val publishedAt = HashMap<Long, Long>()

    fun update(downloadId: Long, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        // 同一个值反复写会让读取方白白重组，先比一下（无锁快路径，绝大多数回调走这里）
        if (_progress.value[downloadId] == clamped) return

        synchronized(publishLock) {
            val now = elapsedRealtimeMs()
            val lastPublishedAt = publishedAt[downloadId]
            val isFirstPublish = lastPublishedAt == null
            val isComplete = clamped >= 1f
            if (!isFirstPublish && !isComplete && now - lastPublishedAt < publishIntervalMs) {
                // 落在窗口内：丢弃这次中间值，窗口结束后到达的下一个值会带着最新进度发布
                return
            }
            publishedAt[downloadId] = now
            _progress.value = _progress.value + (downloadId to clamped)
        }
    }

    fun clear(downloadId: Long) {
        synchronized(publishLock) {
            publishedAt.remove(downloadId)
            if (downloadId !in _progress.value) return
            _progress.value = _progress.value - downloadId
        }
    }

    companion object {
        /** 10Hz：进度条按这个节奏更新已经看不出台阶，而重组次数只有上游回调的 1/10。 */
        const val DEFAULT_PUBLISH_INTERVAL_MS = 100L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
