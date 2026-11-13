package org.jellyfin.mobile.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DownloadProgressTracker] 的发布节流行为。
 *
 * 上游 `FileDownloader` 按 10KB 一块回调，1 MB/s 就是 100 次/秒；这里验证这些回调
 * 不会再原样变成 100 次状态发布 —— 每一次发布都是界面的一次重组。
 */
class DownloadProgressTrackerTest {

    /** 可控时钟：按真实耗时推进，而不是让测试真的去 sleep。 */
    private class FakeClock : () -> Long {
        var now: Long = 0L
        override fun invoke(): Long = now
    }

    /**
     * 收集发布次数。
     *
     * StateFlow 会合并「来不及收」的中间值，所以用 Unconfined 让收集方在赋值线程上
     * 立刻恢复执行：这样每次 `_progress.value = ...` 都恰好对应一次收集。
     */
    private fun collectInto(tracker: DownloadProgressTracker, sink: MutableList<Float>): Job {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return scope.launch {
            tracker.progress.collect { map -> map[1L]?.let(sink::add) }
        }
    }

    @Test
    fun `窗口内的回调被合并成一次发布`() {
        val clock = FakeClock()
        val tracker = DownloadProgressTracker(publishIntervalMs = 100L, elapsedRealtimeMs = clock)
        val published = mutableListOf<Float>()
        val job = collectInto(tracker, published)

        // 1 秒内 100 次回调，每次前进 10ms —— 与 1MB/s 的下载节奏一致
        repeat(100) { step ->
            tracker.update(downloadId = 1L, fraction = step / 100f)
            clock.now += 10L
        }

        job.cancel()

        // t=0 立即发布，之后 t=100,200,...,900 各一次 —— 1000ms 内共 10 次，而不是 100 次
        assertEquals(10, published.size)
        assertEquals(0f, published.first())
        assertEquals(0.9f, published.last())
    }

    @Test
    fun `完成态立即发布`() {
        val clock = FakeClock()
        val tracker = DownloadProgressTracker(publishIntervalMs = 100L, elapsedRealtimeMs = clock)
        val published = mutableListOf<Float>()
        val job = collectInto(tracker, published)

        tracker.update(1L, 0.5f)
        assertEquals(1, published.size)

        // 仍在节流窗口内，但已经下完：不能压着，否则进度条会停在 50%
        clock.now += 10L
        tracker.update(1L, 1f)

        job.cancel()
        assertEquals(1f, tracker.progress.value[1L])
        assertEquals(listOf(0.5f, 1f), published)
    }

    @Test
    fun `重复值不发布`() {
        val clock = FakeClock()
        val tracker = DownloadProgressTracker(publishIntervalMs = 100L, elapsedRealtimeMs = clock)
        val published = mutableListOf<Float>()
        val job = collectInto(tracker, published)

        tracker.update(1L, 0.2f)
        repeat(50) {
            clock.now += 10L
            tracker.update(1L, 0.2f)
        }

        job.cancel()
        assertEquals(listOf(0.2f), published)
    }

    @Test
    fun `清空后重新下载立刻恢复发布`() {
        val clock = FakeClock()
        val tracker = DownloadProgressTracker(publishIntervalMs = 100L, elapsedRealtimeMs = clock)
        val published = mutableListOf<Float>()
        val job = collectInto(tracker, published)

        tracker.update(1L, 0.6f)
        tracker.clear(1L)
        assertTrue(tracker.progress.value.isEmpty())

        // 清空必须同时重置节流窗口：同一项重新排队时，头一秒也要有进度
        clock.now += 10L
        tracker.update(1L, 0.02f)

        job.cancel()
        assertEquals(0.02f, tracker.progress.value[1L])
    }
}
