package org.jellyfin.mobile.ui.screens.player

import org.jellyfin.mobile.player.ui.DecoderType
import java.util.Locale

/**
 * 阶段 5：播放器控制层 Compose 化。从原 PlayerMenus / PlayerGestureHelper 搬运的纯格式化逻辑，
 * 保持行为一致。带本地化文案的参数由调用方（Composable）通过 stringResource 提供。
 */

/** 媒体3 的时间显示格式：>=1h 用 h:mm:ss，否则 m:ss（分钟不补零）。 */
fun formatTime(timeMs: Long): String {
    val totalSeconds = if (timeMs < 0) 0 else timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** 手势 seek 浮层用的 mm:ss（分钟补零，与旧实现一致）。 */
fun formatSeekOverlayTime(timeMs: Long): String {
    val totalSeconds = Math.abs(timeMs / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/** 码率格式化：bps / kbps / Mbps，去掉多余的尾零。 */
fun formatBitrate(bitrate: Double): String {
    val (value, unit) = when {
        bitrate > BITRATE_MEGA_BIT -> bitrate / BITRATE_MEGA_BIT to " Mbps"
        bitrate > BITRATE_KILO_BIT -> bitrate / BITRATE_KILO_BIT to " kbps"
        else -> bitrate to " bps"
    }
    // Remove unnecessary trailing zeros
    val formatted = "%.2f".format(Locale.getDefault(), value).removeSuffix(".00")
    return formatted + unit
}

/** 文件大小格式化：B / KB / MB / GB / TB。 */
fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val formatted = if (unitIndex == 0) value.toInt().toString() else "%.2f".format(Locale.getDefault(), value)
    return "$formatted ${units[unitIndex]}"
}

/** 音轨/字幕菜单项标题（与旧 buildMenuItems 一致）。 */
fun formatStreamTitle(displayTitle: String?, language: String?, codec: String?): String =
    displayTitle ?: "$language ($codec)"

/** 清晰度菜单项标题；[autoLabel] 为"自动"的本地化文案。 */
fun formatQualityTitle(maxHeight: Int, bitrate: Int, autoLabel: String): String =
    if (bitrate == 0) autoLabel else "${maxHeight}p - ${formatBitrate(bitrate.toDouble())}"

/** 倍速菜单项标签，如 "1.0x"。 */
fun formatSpeedLabel(speed: Float): String = "${speed}x"

/** 解码器菜单文案。 */
fun decoderLabel(decoder: DecoderType, hardwareLabel: String, softwareLabel: String): String =
    when (decoder) {
        DecoderType.HARDWARE -> hardwareLabel
        DecoderType.SOFTWARE -> softwareLabel
    }

/** 播放信息面板中音轨/字幕流的兜底标题（与旧 buildStreamFallbackTitle 一致）。 */
fun formatStreamFallbackTitle(codec: String?, language: String?): String =
    listOfNotNull(language, codec?.uppercase(Locale.ROOT)).joinToString(" - ")

/** 引擎信息：mpv / ExoPlayer（+ 解码器）。 */
fun formatEngineInfo(engine: String, decoder: String?): String =
    listOfNotNull(engine, decoder).joinToString(" · ")

/** 倍速菜单步进（与旧 SPEED_MENU_STEP_* 一致）：0.5x .. 2.0x。 */
val SPEED_MENU_STEPS: List<Float> = (SPEED_MENU_STEP_MIN..SPEED_MENU_STEP_MAX).map { it * SPEED_MENU_STEP_SIZE }

private const val BITRATE_MEGA_BIT = 1_000_000
private const val BITRATE_KILO_BIT = 1_000
private const val SPEED_MENU_STEP_SIZE = 0.25f
private const val SPEED_MENU_STEP_MIN = 2 // → 0.5x
private const val SPEED_MENU_STEP_MAX = 8 // → 2x
