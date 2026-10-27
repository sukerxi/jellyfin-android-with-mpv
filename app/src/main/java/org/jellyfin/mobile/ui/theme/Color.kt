package org.jellyfin.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors, kept identical to the legacy M2 AppTheme
val JellyfinPrimary = Color(0xFF00A4DC)
val JellyfinPrimaryVariant = Color(0xFF202020)
val JellyfinBackground = Color(0xFF101010)
val JellyfinSurface = Color(0xFF363636)
val JellyfinSurfaceVariant = Color(0xFF4A4A4A)
val JellyfinError = Color(0xFFCF6679)

val JellyfinOnPrimary = Color.White
val JellyfinOnSurface = Color.White

/**
 * Snackbar 容器色（对应 M3 的 inverseSurface）。
 *
 * M3 深色方案默认把 inverseSurface 设成近白色（#E6E1E5），本应用是纯深色界面，
 * 底部弹出这样一条白底提示很突兀。改用比 surface（#363636）略深的灰，
 * 与整体深色风格一致。
 */
val JellyfinSnackbar = Color(0xFF303030)
