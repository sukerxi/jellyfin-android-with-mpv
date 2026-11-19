package org.jellyfin.mobile.perf

import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.ui.screens.player.GestureIndicatorsOverlay
import org.jellyfin.mobile.ui.screens.player.GestureOverlay
import org.jellyfin.mobile.ui.screens.player.rememberPlayerGestureHandler
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.koinInject
import java.io.File

private const val TAG = "GestureLab"

/** 推送位置：`adb shell run-as org.jellyfin.mobile.debug cp /data/local/tmp/lab.mp4 files/gesture_lab.mp4`。 */
private const val VIDEO_FILE_NAME = "gesture_lab.mp4"

/** 与播放器控件上的快进/快退按钮同一步长，双击跳转文案才对得上。 */
private const val SEEK_STEP_MS = 10_000L

/**
 * 调试构建专用：长按快进/长按快退手势的实机验证页。
 *
 * 生产链路里播放页只能由服务端下发的 [org.jellyfin.mobile.player.interaction.PlayOptions]
 * 拉起（Web 端调用 `NativePlayer.loadPlayer`），没有 Jellyfin 服务器就没有画面，
 * 手势也就无从验证。这里把**同一套** [GestureOverlay] / [GestureIndicatorsOverlay] /
 * [rememberPlayerGestureHandler] 挂在一个真实 [ExoPlayer] 上，用本地视频跑通
 * 「按下 → 长按判定 → 倍速/倒带 → 松手还原」的完整路径。
 *
 * 与生产页的差异只有播放器接线：生产页走 `PlayerViewModel`（`setPressSpeedUp` /
 * `pause` / `play` / `seekTo`），这里直接下单到 ExoPlayer。手势层本身没有任何分支。
 *
 * 每条状态变化都以 `GestureLab:` 前缀写 logcat，配合截图可核对：
 *
 * ```
 * adb shell input motionevent DOWN 800 1200   # 右半屏长按
 * adb logcat -s GestureLab
 * ```
 *
 * 视频不进仓库（2MB 二进制只为一次验证不值当），用时自己生成一段 60 秒测试片并推入私有目录：
 *
 * ```
 * ffmpeg -f lavfi -i testsrc2=size=480x270:rate=24 -f lavfi -i sine=frequency=440 \
 *     -t 60 -c:v libx264 -profile:v baseline -crf 32 -pix_fmt yuv420p -c:a aac -b:a 48k test.mp4
 * adb push test.mp4 /data/local/tmp/lab.mp4
 * adb shell run-as org.jellyfin.mobile.debug cp /data/local/tmp/lab.mp4 files/gesture_lab.mp4
 * ```
 *
 * 只编译进 debug 变体，release 不包含。
 */
class PlayerGestureHarnessActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        player = exoPlayer

        val video = File(filesDir, VIDEO_FILE_NAME)
        if (video.exists()) {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            // 循环播放：验证一次手势要花几十秒，片子放完停住会让下一轮长按没有可观察的效果。
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            Log.w(TAG, "missing video: ${video.absolutePath}")
        }

        setContent {
            GestureHarnessContent(player = exoPlayer, window = window)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

@Composable
private fun GestureHarnessContent(player: ExoPlayer, window: Window) {
    val appPreferences: AppPreferences = koinInject()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    // 播放器与手势层共用同一个尺寸：手势的左右分区、边缘排除带都按它算。
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    // 缓冲态：seek 之后卡片要不要留任、转圈画在卡片里，都取决于它（与真实播放页同一条语义）。
    // 这里直接跟播放器状态，不做滞回——实验页要看到的就是状态本身。
    var buffering by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        buffering = player.playbackState == Player.STATE_BUFFERING
        onDispose { player.removeListener(listener) }
    }

    val exclusionSizeVerticalPx = with(density) {
        Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL.dp.roundToPx()
    }
    val exclusionSizeHorizontalPx = with(density) {
        Constants.SWIPE_GESTURE_EXCLUSION_SIZE_HORIZONTAL.dp.roundToPx()
    }

    val handler = rememberPlayerGestureHandler(
        appPreferences = appPreferences,
        audioManager = audioManager,
        window = window,
        sizeProvider = { surfaceSize },
        volumeTextFormat = stringResource(R.string.gesture_overlay_volume),
        brightnessTextFormat = stringResource(R.string.gesture_overlay_brightness),
        isLandscape = { configuration.orientation == Configuration.ORIENTATION_LANDSCAPE },
        isLocked = { false },
        getPlayer = { player },
        // 与真实播放页同一条语义：seek 之后卡片要留到「首帧回来了」为止，
        // 转圈就画在卡片里。本地文件几乎瞬间就绪，但留任逻辑本身能被走到。
        isWaitingForPlayback = { buffering },
        onToggleControls = {},
        onPeekUnlockButton = {},
        onTogglePlayPause = {
            if (player.isPlaying) player.pause() else player.play()
        },
        onSeekTo = { position ->
            Log.i(TAG, "seekTo($position)")
            player.seekTo(position)
        },
        onFastForward = { player.seekTo(player.clampedSeekTarget(SEEK_STEP_MS)) },
        onRewind = { player.seekTo(player.clampedSeekTarget(-SEEK_STEP_MS)) },
        onPressSpeedUp = { pressing ->
            Log.i(TAG, "pressSpeedUp($pressing)")
            player.playbackParameters = PlaybackParameters(
                if (pressing) Constants.HOLD_SPEEDUP_MULTIPLIER else 1f,
            )
        },
        onZoomChanged = {},
        onHideControls = {},
        onSecondTapStarted = {},
        seekStepLabel = { forward -> if (forward) "+10s" else "-10s" },
        exclusionSizeVerticalPx = exclusionSizeVerticalPx,
        exclusionSizeHorizontalPx = exclusionSizeHorizontalPx,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        GestureOverlay(
            handler = handler,
            onSurfaceSizeChanged = { size -> surfaceSize = size },
            modifier = Modifier.matchParentSize(),
        )

        GestureIndicatorsOverlay(
            handler = handler,
            waitingForPlayback = buffering,
            modifier = Modifier.matchParentSize(),
        )

        HarnessReadout(
            player = player,
            speedBoostActive = handler.speedBoostActive,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
    }

    // 状态每秒两次写日志：adb logcat 侧的数值比截图更精确地证明「按住时在退 / 在加速」。
    LaunchedEffect(player) {
        while (true) {
            Log.i(
                TAG,
                "pos=${player.currentPosition} diff=${player.playbackParameters.speed} " +
                    "playing=${player.isPlaying} boost=${handler.speedBoostActive} " +
                    "hudFwd=${handler.seekIndicator?.forward}",
            )
            delay(500)
        }
    }
}

/** 画面左上角的状态读数：没有它，截图只能看到 HUD，看不到位置到底退了多少。 */
@Composable
private fun HarnessReadout(
    player: ExoPlayer,
    speedBoostActive: Boolean,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            delay(250)
        }
    }

    Text(
        text = "pos ${position / 1000.0}s  speed ${player.playbackParameters.speed}x  " +
            if (player.isPlaying) "playing" else "paused",
        color = if (speedBoostActive) Color(0xFF6FD7F7) else Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

/**
 * 按步长跳转，并夹在可 seek 区间内。
 *
 * 时长未知（直播/尚未 ready）时 [ExoPlayer.getDuration] 返回 `C.TIME_UNSET`（负数），
 * 直接 `coerceAtMost` 会算出负目标；这种情况只夹下界，交给播放器自己忽略越界 seek。
 */
private fun ExoPlayer.clampedSeekTarget(deltaMs: Long): Long {
    val target = currentPosition + deltaMs
    val upperBound = if (duration > 0) duration else Long.MAX_VALUE
    return target.coerceIn(0L, upperBound)
}
