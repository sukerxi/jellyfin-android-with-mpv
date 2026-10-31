package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R

/**
 * 播放失败卡片。
 *
 * 取代原先的「Toast 一句话 → 立刻 closePlayer()」：失败后播放器留在原位，
 * 用户能看到原因、原地重试（同样的播放参数），或者自己决定返回。
 * 卡片整体铺满并吃点击，避免失败态下误触到下面的控件。
 */
@Composable
fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // 吞掉所有点击：失败态下底下的控件栏还在，误触会把用户带到别的界面
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // 卡片压在视频上，深色主题的 surface 在浅色主题下会变成一块亮白板，
            // 与播放器其余浮层（玻璃卡片）完全脱节。这里固定用播放器的近黑色玻璃。
            color = Color(0xE61A1A1A),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, PlayerGlassBorderColor),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.player_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = PlayerGlassTextPrimary,
                )
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlayerGlassTextPrimary,
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = stringResource(R.string.player_error_detail_hint),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerGlassTextSecondary,
                )
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onExit) {
                        Text(text = stringResource(R.string.back))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}
