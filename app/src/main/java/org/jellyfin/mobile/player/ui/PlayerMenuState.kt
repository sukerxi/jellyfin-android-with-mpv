package org.jellyfin.mobile.player.ui

/**
 * 阶段 5：播放器控制层 Compose 化后的共享 UI 状态类型。
 */

/**
 * 进度条上的单个章节标记。
 *
 * @param bias 在进度条上的相对位置（0f..1f）
 * @param watched 是否已播放过（决定着色）
 */
data class ChapterMarkingState(
    val bias: Float,
    val watched: Boolean,
)
