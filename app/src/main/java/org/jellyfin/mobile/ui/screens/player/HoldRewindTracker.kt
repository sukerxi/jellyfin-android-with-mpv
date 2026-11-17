package org.jellyfin.mobile.ui.screens.player

import org.jellyfin.mobile.utils.Constants

/**
 * 长按快退的位置模型：把「按住了多久」换算成「退到哪」。
 *
 * 只做算术，不读时钟、不碰播放器 —— [PlayerGestureHandler] 负责把真实时钟和 seek 接进来。
 * 片头、非零基准点、超出片长的位置这些边界因此可以在单元测试里逐条钉死，不需要真的按住
 * 屏幕等上几秒。
 *
 * @param anchorPositionMs 按下瞬间的播放位置，整段手势的基准点
 * @param durationMs 媒体总时长；调用方保证 > 0（直播、时长未知的流不进这个手势）
 * @param ratePerSecond 每按住 1 秒回退的媒体秒数
 */
internal class HoldRewindTracker(
    anchorPositionMs: Long,
    val durationMs: Long,
    private val ratePerSecond: Float = Constants.HOLD_REWIND_RATE,
) {
    /**
     * 夹进 `[0, duration]` 的基准点。
     *
     * 按住时读到的位置可能来自一个尚未落地的 seek（mpv 在网络源上尤其明显），
     * 夹一次可以保证后面的减法永远落在有效区间里。
     */
    val anchorPositionMs: Long = anchorPositionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))

    /**
     * 按住 [heldMs] 后的目标位置，已夹在 `[0, 基准点]` 之间。
     *
     * 速率是线性的：按住 1 秒退 3 秒（见 [Constants.HOLD_REWIND_RATE]）。不做加速曲线是
     * 刻意的——快退的落点必须是可预测的，用户松手前就能从 HUD 上读出「会在哪一帧停下」。
     */
    fun targetPositionMs(heldMs: Long): Long {
        val travelledMs = (heldMs.coerceAtLeast(0L) * ratePerSecond.toDouble()).toLong()
        return (anchorPositionMs - travelledMs).coerceAtLeast(0L)
    }

    /** 相对基准点的位移（`<= 0`），用于 HUD 上的 `-mm:ss`。 */
    fun offsetMs(heldMs: Long): Long = targetPositionMs(heldMs) - anchorPositionMs

    /** 目标位置在整片中的比例，用于 HUD 进度条。 */
    fun progress(heldMs: Long): Float =
        if (durationMs > 0) targetPositionMs(heldMs).toFloat() / durationMs else 0f
}
