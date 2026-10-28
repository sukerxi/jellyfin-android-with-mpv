package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放器浮层统一的「玻璃」视觉：深色渐变底 + 1dp 半透明描边 + 圆角 + 投影。
 *
 * 手势 HUD、跳过分段、解锁按钮此前各画各的（纯黑方块、不同圆角与底色），放在一起看
 * 不成体系。这里抽出同一组颜色与绘制顺序，浮层只需选一个 shape 和阴影，其余保持一致。
 */
internal val PlayerGlassBackground = Brush.verticalGradient(
    listOf(Color(0xF7010101), Color(0xDB010101)),
)
internal val PlayerGlassBorderColor = Color.White.copy(alpha = 0.10f)
internal val PlayerGlassTrackColor = Color.White.copy(alpha = 0.16f)
internal val PlayerGlassTextPrimary = Color.White.copy(alpha = 0.94f)
internal val PlayerGlassTextSecondary = Color.White.copy(alpha = 0.62f)
internal val PlayerGlassIconTint = Color.White.copy(alpha = 0.94f)

/** 玻璃卡片背景（不含内容与内边距）。 */
internal fun Modifier.playerGlass(
    shape: Shape,
    shadowElevation: Dp = 8.dp,
): Modifier = this
    .shadow(shadowElevation, shape, clip = false)
    .background(PlayerGlassBackground, shape)
    .border(1.dp, PlayerGlassBorderColor, shape)
