package org.jellyfin.mobile.player.mpv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PlaybackStallWatchdog] 的判定语义。
 *
 * 判错的两种代价都很直接：报早了会打断本来能恢复的播放（起播、转码冷启动、断流后重试），
 * 报晚了用户就一直盯着冻结的画面。这里用虚拟时钟逐条钉住边界，不真的等 15 秒。
 */
class PlaybackStallWatchdogTest {

    /** 虚拟时钟：推进时间时执行到期的动作（动作本身可能再排下一个）。 */
    private class FakeScheduler : Scheduler {
        var now = 0L
            private set

        private var dueAt: Long? = null
        private var action: Runnable? = null

        override fun schedule(delayMs: Long, action: Runnable) {
            dueAt = now + delayMs
            this.action = action
        }

        override fun cancel() {
            dueAt = null
            action = null
        }

        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val due = dueAt
                if (due == null || due > target) break
                now = due
                dueAt = null
                val pendingAction = action
                action = null
                pendingAction?.run()
            }
            now = target
        }
    }

    private class Harness {
        val scheduler = FakeScheduler()
        var positionMs = 0L
        var speed = 1.0
        var stalls = 0
            private set

        val watchdog = PlaybackStallWatchdog(
            scheduler = scheduler,
            checkIntervalMs = TICK_MS,
            maxStalledChecks = MAX_CHECKS,
            warmupChecks = WARMUP_CHECKS,
            positionMs = { positionMs },
            speed = { speed },
            onStall = { stalls++ },
        )

        /** 模拟 [ticks] 个周期的播放：每个周期前进 [perTickMs]。 */
        fun play(ticks: Int, perTickMs: Long = TICK_MS) {
            repeat(ticks) {
                positionMs += perTickMs
                scheduler.advanceBy(TICK_MS)
            }
        }

        /** 起播成功并稳定下来：一个基准样本 + 连续 [WARMUP_CHECKS] 个走满的周期。 */
        fun playSteadily() {
            watchdog.update(watching = true)
            play(ticks = WARMUP_CHECKS + 1)
        }
    }

    @Test
    fun `起播阶段位置不动不报`() {
        // 转码冷启动、断流后重试的最初十几秒：一帧数据都还没到，位置压根不动
        val harness = Harness()
        harness.watchdog.update(watching = true)

        harness.scheduler.advanceBy(TICK_MS * 100)

        assertEquals(0, harness.stalls)
    }

    @Test
    fun `起播阶段数据断续不报`() {
        // 冷启动的真实形态：先走一秒多，再停好几秒等转码追上来，如此反复
        val harness = Harness()
        harness.watchdog.update(watching = true)

        repeat(30) {
            harness.play(ticks = 2, perTickMs = 1_200L)
            harness.scheduler.advanceBy(TICK_MS * 4)
        }

        assertEquals(0, harness.stalls)
    }

    @Test
    fun `稳定播放后再断流要报`() {
        val harness = Harness()
        harness.playSteadily()

        harness.scheduler.advanceBy(TICK_MS * (MAX_CHECKS - 1))
        assertEquals(0, harness.stalls)

        harness.scheduler.advanceBy(TICK_MS)
        assertEquals(1, harness.stalls)
    }

    @Test
    fun `正常播放不报`() {
        val harness = Harness()
        harness.playSteadily()

        harness.play(ticks = 40)

        assertEquals(0, harness.stalls)
    }

    @Test
    fun `位置缓慢爬行也会被判死`() {
        // 实机断流后的形态：mpv 没有置 paused-for-cache，位置一分钟只爬 3 秒
        val harness = Harness()
        harness.playSteadily()

        harness.play(ticks = 20, perTickMs = 150L)

        assertEquals(1, harness.stalls)
    }

    @Test
    fun `单个周期掉队不会累计成误判`() {
        val harness = Harness()
        harness.playSteadily()

        repeat(10) {
            // 四个周期没走够，第五个周期补回来
            harness.play(ticks = MAX_CHECKS - 1, perTickMs = 0L)
            harness.play(ticks = 1, perTickMs = TICK_MS * MAX_CHECKS)
        }

        assertEquals(0, harness.stalls)
    }

    @Test
    fun `暂停期间不计时`() {
        val harness = Harness()
        harness.playSteadily()
        harness.scheduler.advanceBy(TICK_MS * 2)

        harness.watchdog.update(watching = false)
        harness.scheduler.advanceBy(TICK_MS * 100)

        assertEquals(0, harness.stalls)
    }

    @Test
    fun `恢复播放后重新计时`() {
        val harness = Harness()
        harness.playSteadily()
        harness.scheduler.advanceBy(TICK_MS * 4)

        harness.watchdog.update(watching = false)
        harness.watchdog.update(watching = true)
        // 恢复播放后位置重新走起来，之前的等待不该算数
        harness.play(ticks = 1)
        harness.scheduler.advanceBy(TICK_MS * (MAX_CHECKS - 1))
        assertEquals(0, harness.stalls)

        harness.scheduler.advanceBy(TICK_MS)
        assertEquals(1, harness.stalls)
    }

    @Test
    fun `seek 之后从新位置重新计时`() {
        val harness = Harness()
        harness.playSteadily()
        // 停滞了四个周期，再来一个周期就要报
        harness.scheduler.advanceBy(TICK_MS * (MAX_CHECKS - 1))

        // 用户拖动进度条：位置跳到新地方，之后的等待不该算在停滞里
        harness.positionMs = 600_000L
        harness.watchdog.restart()
        // 第一个样本只当基准，之后四个周期不够时才轮到第五个周期判死
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)
        assertEquals(0, harness.stalls)

        harness.scheduler.advanceBy(TICK_MS)
        assertEquals(1, harness.stalls)
    }

    @Test
    fun `同一次停滞只上报一次`() {
        val harness = Harness()
        harness.playSteadily()
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)
        assertEquals(1, harness.stalls)

        // stop 的 END_FILE 还没回来，状态仍是「该播放」，事件不断涌入
        repeat(10) {
            harness.watchdog.update(watching = true)
            harness.scheduler.advanceBy(TICK_MS)
        }

        assertEquals(1, harness.stalls)
    }

    @Test
    fun `离开播放状态后新的停滞可以再次上报`() {
        val harness = Harness()
        harness.playSteadily()
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)
        assertEquals(1, harness.stalls)

        harness.watchdog.update(watching = false)
        harness.playSteadily()
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)

        assertEquals(2, harness.stalls)
    }

    @Test
    fun `换片后重新要求先稳定下来`() {
        val harness = Harness()
        harness.playSteadily()

        harness.watchdog.reset()
        harness.positionMs = 0L
        harness.watchdog.update(watching = true)
        // 新片转码冷启动：位置不动，不能算断流
        harness.scheduler.advanceBy(TICK_MS * 100)
        assertEquals(0, harness.stalls)

        harness.play(ticks = WARMUP_CHECKS + 1)
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)

        assertEquals(1, harness.stalls)
    }

    @Test
    fun `倍速播放按倍速折算预期进度`() {
        val harness = Harness()
        harness.speed = 2.0
        harness.watchdog.update(watching = true)

        // 2 倍速下每周期前进 6 秒才算正常
        harness.play(ticks = 20, perTickMs = 6_000L)

        assertEquals(0, harness.stalls)

        val slow = Harness()
        slow.speed = 2.0
        slow.playSteadily() // 起播先按 1 倍速走稳
        // 用户切到 2 倍速，但位置每周期只走半秒，远远跟不上
        slow.play(ticks = MAX_CHECKS, perTickMs = 500L)

        assertEquals(1, slow.stalls)
    }

    @Test
    fun `模拟器上那种慢于实时的播放能建立基线`() {
        // 实测：稳态每 3000ms 只前进 1500ms 左右（模拟器的媒体时钟跟不上墙上时钟），
        // 冷启动更慢。这种「慢但一直在走」必须算健康，否则基线永远建立不起来，
        // 真断流时一声不吭 —— 这正是把阈值从 0.5 降到 0.15 的原因。
        val harness = Harness()
        harness.watchdog.update(watching = true)

        harness.play(ticks = 10, perTickMs = 1_500L)
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)

        assertEquals(1, harness.stalls)
    }

    @Test
    fun `转码冷启动那样断断续续也能建立基线`() {
        // 冷启动实测每周期只走 900–1266ms，中间还夹着完全不动的周期
        val harness = Harness()
        harness.watchdog.update(watching = true)

        repeat(8) {
            harness.play(ticks = 2, perTickMs = 1_000L)
            harness.play(ticks = 1, perTickMs = 0L)
        }
        // 攒够之后真断流
        harness.scheduler.advanceBy(TICK_MS * MAX_CHECKS)

        assertEquals(1, harness.stalls)
    }

    private companion object {
        const val TICK_MS = 3_000L
        const val MAX_CHECKS = 5
        const val WARMUP_CHECKS = 5
    }
}
