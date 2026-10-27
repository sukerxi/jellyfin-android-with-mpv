package org.jellyfin.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val JellyfinDarkColorScheme = darkColorScheme(
    primary = JellyfinPrimary,
    secondary = JellyfinPrimary,
    background = JellyfinBackground,
    surface = JellyfinSurface,
    surfaceVariant = JellyfinSurfaceVariant,
    error = JellyfinError,
    onPrimary = JellyfinOnPrimary,
    onSecondary = JellyfinOnPrimary,
    onBackground = JellyfinOnSurface,
    onSurface = JellyfinOnSurface,
    onError = JellyfinOnSurface,
    // Snackbar 用的是「反色」容器，M3 默认值在深色方案里是近白色，
    // 在纯深色界面上像一块贴纸；这里一并压成深色。
    inverseSurface = JellyfinSnackbar,
    inverseOnSurface = JellyfinOnSurface,
    // 动作文字（重试/确定等）沿用品牌青；在 #303030 上对比度约 4.6:1，满足 AA。
    inversePrimary = JellyfinPrimary,
)

private val JellyfinShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(0.dp),
)

/**
 * App-wide Material 3 theme. The app only ships a dark palette to match the legacy UI.
 */
@Composable
fun JellyfinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JellyfinDarkColorScheme,
        shapes = JellyfinShapes,
        content = content,
    )
}
