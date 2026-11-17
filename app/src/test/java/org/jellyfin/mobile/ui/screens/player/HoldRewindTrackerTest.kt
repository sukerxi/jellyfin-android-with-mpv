package org.jellyfin.mobile.ui.screens.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [HoldRewindTracker] 的换算语义。
 *
 * 这个模型决定「按住多久、退到哪里」，也是 HUD 上 `-mm:ss` 与进度条的唯一数据来源。
 * 算错不会被崩溃发现，只会让松手后的落点和用户看到的数字对不上，所以边界要逐条钉住。
 */
class HoldRewindTrackerTest {

    private fun tracker(
        anchorSeconds: Long,
        durationSeconds: Long,
        rate: Float = 3f,
    ) = HoldRewindTracker(
        anchorPositionMs = anchorSeconds * 1_000,
        durationMs = durationSeconds * 1_000,
        ratePerSecond = rate,
    )

    @Test
    fun `按住时长按速率折算成退的距离`() {
        val tracker = tracker(anchorSeconds = 100, durationSeconds = 600)

        assertEquals(100_000, tracker.targetPositionMs(0))
        assertEquals(97_000, tracker.targetPositionMs(1_000))
        assertEquals(91_000, tracker.targetPositionMs(3_000))
        // 不足一个 seek 间隔的按住时长也要精确折算：HUD 的数字和最终落点必须一致。
        assertEquals(98_500, tracker.targetPositionMs(500))
    }

    @Test
    fun `退到片头就停住`() {
        val tracker = tracker(anchorSeconds = 10, durationSeconds = 600)

        assertEquals(0, tracker.targetPositionMs(4_000))
        assertEquals(0, tracker.targetPositionMs(60_000))
        assertTrue(tracker.targetPositionMs(60_000) >= 0)
    }

    @Test
    fun `从未超过基准点`() {
        val tracker = tracker(anchorSeconds = 300, durationSeconds = 600)

        // 位移永远是非正数：快退不能反向变成快进。
        assertTrue(tracker.offsetMs(0) == 0L)
        assertTrue(tracker.offsetMs(1_000) < 0)
        assertEquals(-3_000, tracker.offsetMs(1_000))
    }

    @Test
    fun `进度按整片时长换算`() {
        val tracker = tracker(anchorSeconds = 300, durationSeconds = 600)

        assertEquals(0.5f, tracker.progress(0), 0.0001f)
        assertEquals(0.45f, tracker.progress(10_000), 0.0001f)
        assertEquals(0f, tracker.progress(300_000), 0.0001f)
    }

    @Test
    fun `基准点超出片长时夹到片尾`() {
        // 按住瞬间读到的位置可能来自一个尚未落地的 seek，不能让它把进度条算到 1 以上。
        val tracker = HoldRewindTracker(anchorPositionMs = 700_000, durationMs = 600_000)

        assertEquals(600_000, tracker.anchorPositionMs)
        assertEquals(1f, tracker.progress(0), 0.0001f)
    }

    @Test
    fun `负的按住时长按 0 处理`() {
        val tracker = tracker(anchorSeconds = 100, durationSeconds = 600)

        // 系统时钟回拨或测试注入负值时不至于把位置推到基准点之后。
        assertEquals(100_000, tracker.targetPositionMs(-1_000))
    }

    @Test
    fun `速率决定同样的按住时长退多远`() {
        val slow = tracker(anchorSeconds = 100, durationSeconds = 600, rate = 1f)
        val fast = tracker(anchorSeconds = 100, durationSeconds = 600, rate = 6f)

        assertEquals(99_000, slow.targetPositionMs(1_000))
        assertEquals(94_000, fast.targetPositionMs(1_000))
    }
}
