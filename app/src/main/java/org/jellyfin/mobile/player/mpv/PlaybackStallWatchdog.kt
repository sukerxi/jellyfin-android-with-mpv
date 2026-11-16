package org.jellyfin.mobile.player.mpv

import android.os.Handler
import timber.log.Timber

/**
 * 「在播放、却几乎不往前走」的看门狗。
 *
 * mpv 在流被掐断时不会自己失败：HLS 分片拉不下来，它就按固定节奏重试（日志里是
 * `Failed to open segment` → `http: Timeout was reached`），既不产生 END_FILE 也不退出。
 * 更麻烦的是它**不一定**把 `paused-for-cache` 置真 —— 实测断流后画面冻住、音频一直
 * underrun，播放位置却仍以每秒几毫秒的速度往前爬，播放页既不转圈也不报错，用户看到
 * 的就是彻底卡死。所以判据既不能是「有没有在缓冲」，也不能是「位置有没有变」。
 *
 * 这里用的是**前进得够不够快**：每个检查周期，位置至少要前进
 * `周期 × 播放倍速 × [MIN_PROGRESS_FRACTION]`。连续 [maxStalledChecks] 个周期达不到就
 * 回调一次 [onStall]。
 *
 * 起播阶段要单独对待：转码冷启动时数据是一阵一阵来的，位置前进得比实时慢很多。那不是
 * 断流，所以要先攒够 [warmupChecks] 个健康周期（[established]），后面的停滞才开始计数。
 * 攒的过程允许掉队（掉一次只退一格，不清零），否则一次抖动就要从头再数，永远建立不起来。
 *
 * 它不认识 mpv、不认识 media3：位置与倍速由调用方以 lambda 提供（都是内存缓存读，
 * 不阻塞主线程），「怎么处理」也由调用方决定。开销是播放期间每个 [checkIntervalMs]
 * 一次函数调用，没有额外的线程，也没有轮询循环。
 */
internal class PlaybackStallWatchdog(
    private val scheduler: Scheduler,
    private val checkIntervalMs: Long,
    private val maxStalledChecks: Int,
    private val warmupChecks: Int,
    private val positionMs: () -> Long,
    private val speed: () -> Double,
    private val onStall: () -> Unit,
) {
    /** 是否正在看着（调用方说「此刻本该在播放」）。 */
    private var watching = false

    /** 本次观看期间是否已经报过：一次停滞只报一次。 */
    private var reported = false

    /**
     * 播放是否已经「稳定」：连续走满过 [warmupChecks] 个周期。
     *
     * 同一个 item 内保持不变（暂停再继续、seek 都不该重新等一遍），换片时由 [reset] 清掉。
     */
    private var established = false

    /** 连续走满的周期数（只在 [established] 之前计数）。 */
    private var healthyChecks = 0

    private var stalledChecks = 0
    private var lastPositionMs = 0L

    /** 是否已经拿到本轮的第一个位置样本（见 [checkProgress]）。 */
    private var hasBaseline = false

    private val check = Runnable { checkProgress() }

    /**
     * 喂入「此刻是否本该在播放」。重复调用是幂等的：计时只在 false → true 的那一次开始，
     * 不会因为后续事件被重置。
     */
    fun update(watching: Boolean) {
        if (!watching) {
            // 注意不能只判断 watching：上报时它已经被置回 false，但「本次观看已上报」的
            // 闩锁还在 —— 漏掉这里的话，之后再也无法重新开始监视。
            if (!this.watching && !reported) return
            Timber.d(
                "stall watchdog: stop watching (reported=%b established=%b pos=%d)",
                reported,
                established,
                positionMs(),
            )
            this.watching = false
            reported = false
            stalledChecks = 0
            hasBaseline = false
            scheduler.cancel()
            return
        }
        if (this.watching || reported) return
        Timber.d("stall watchdog: start watching (established=%b pos=%d)", established, positionMs())
        this.watching = true
        stalledChecks = 0
        hasBaseline = false
        scheduler.schedule(checkIntervalMs, check)
    }

    /** 换了一个媒体文件：连「播放已经稳定」的结论一起作废。 */
    fun reset() {
        watching = false
        reported = false
        established = false
        healthyChecks = 0
        stalledChecks = 0
        hasBaseline = false
        scheduler.cancel()
    }

    /**
     * 位置刚发生过一次预期内的跳跃（用户 seek）：以新位置为起点重新计时。
     *
     * 不重置的话，一次耗时十几秒的网络 seek 会被当成停滞 —— 而 seek 期间位置本来就不动。
     * 「已经稳定」的结论保留：seek 不代表播放回到了起播状态。
     */
    fun restart() {
        if (!watching) return
        stalledChecks = 0
        hasBaseline = false
        scheduler.schedule(checkIntervalMs, check)
    }

    private fun checkProgress() {
        if (!watching) return
        val position = positionMs()
        // 第一个样本只用来当基准：它必须和后面的样本同源，不能拿「挂起时的旧值」去比。
        if (!hasBaseline) {
            hasBaseline = true
            lastPositionMs = position
            scheduler.schedule(checkIntervalMs, check)
            return
        }
        val advanced = position - lastPositionMs
        val healthy = advanced >= checkIntervalMs * speed() * MIN_PROGRESS_FRACTION
        lastPositionMs = position

        if (!established) {
            // 起播 / 冷启动 / 断流后重试：数据还在一阵一阵地来。掉队只退一格、不清零，
            // 这样零星抖动不会让计数永远攒不满。
            healthyChecks = if (healthy) healthyChecks + 1 else (healthyChecks - 1).coerceAtLeast(0)
            if (healthyChecks >= warmupChecks) {
                established = true
                healthyChecks = 0
                stalledChecks = 0
                Timber.d("stall watchdog: playback established at %d", position)
            }
            scheduler.schedule(checkIntervalMs, check)
            return
        }

        if (healthy) {
            stalledChecks = 0
        } else {
            stalledChecks++
            Timber.d(
                "stall watchdog: stalled check %d/%d (advanced %d ms of %d ms)",
                stalledChecks,
                maxStalledChecks,
                advanced,
                checkIntervalMs,
            )
        }
        if (stalledChecks >= maxStalledChecks) {
            watching = false
            reported = true
            established = false
            stalledChecks = 0
            hasBaseline = false
            onStall()
            return
        }
        scheduler.schedule(checkIntervalMs, check)
    }

    private companion object {
        /**
         * 一个周期里至少要走出「预期进度」的多少倍才算活着。
         *
         * 实测（模拟器 perf34，HLS 转码 720p）：稳态播放时位置每 3000ms 前进 1533–2000ms
         * （约 51%–67%，模拟器本身的媒体时钟就跟不上墙上时钟），转码冷启动时只有
         * 900–1266ms（30%–42%），而流真的断了以后只剩每秒几毫秒。
         *
         * 取 0.15 是为了两头都留足余量：比最慢的健康周期（30%）低一半，比断流（<1%）
         * 高一个数量级。早前用过 0.5，结果稳态 51% 贴着阈值，冷启动更是永远达不到 ——
         * 看门狗始终建立不起基线，真断流时反倒一声不吭。
         */
        const val MIN_PROGRESS_FRACTION = 0.15
    }
}

/**
 * 一次性延迟执行。
 *
 * 抽成接口只为让看门狗的判断逻辑能脱离 Android 的 Handler 做单元测试（测试里用虚拟时钟）；
 * 真正的实现见 [HandlerScheduler]，没有别的实现。
 */
internal interface Scheduler {
    /** [delayMs] 之后执行 [action]；此前挂着未执行的动作作废（同一时刻至多一个待执行动作）。 */
    fun schedule(delayMs: Long, action: Runnable)

    /** 撤掉尚未执行的动作。 */
    fun cancel()
}

/** [Scheduler] 的 Handler 实现：消息落在播放器自己的 looper 上，不引入额外线程。 */
internal class HandlerScheduler(private val handler: Handler) : Scheduler {
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, action: Runnable) {
        cancel()
        pending = action
        handler.postDelayed(action, delayMs)
    }

    override fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}
