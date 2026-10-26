package org.jellyfin.mobile.ui.components

import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jellyfin.mobile.utils.koinInject

/**
 * 全应用统一的轻量提示通道。
 *
 * 背景：原先错误与结果提示大量使用 [android.widget.Toast]，各自为政 —— 不可回溯、
 * 不可复制、不承载动作，且弹在屏幕下方与播放器控件重叠。Toast 只保留给"离开当前界面
 * 后仍需要知道"的场景（例如权限设置跳转），界面内的提示一律走这里。
 *
 * 用法：`snackbarController.show("已保存", actionLabel = "撤销") { ... }`。
 * 消息进缓冲队列，依次展示；同一时间屏幕上只有一条。
 */
class SnackbarController {

    class Message(
        val text: String,
        val actionLabel: String?,
        val duration: SnackbarDuration,
        val action: (() -> Unit)?,
    )

    // 缓冲区防止连续提示（例如批量删除失败）时丢消息；超出后丢最旧的，保证最近的可见。
    internal val messages = MutableSharedFlow<Message>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun show(
        text: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        action: (() -> Unit)? = null,
    ) {
        messages.tryEmit(Message(text, actionLabel, duration, action))
    }

    fun showLong(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        show(text, actionLabel, SnackbarDuration.Long, action)
    }
}

/**
 * 挂在根组合上的唯一 Snackbar 宿主。
 *
 * 位置在 NavHost 之上、对话框之下：设置页/下载页/播放器浮层里的提示都能显示，
 * 又不会盖住 AlertDialog 的按钮。
 */
@Composable
fun AppSnackbarHost(
    controller: SnackbarController = koinInject(),
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(controller) {
        controller.messages.collect { message ->
            val result = hostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel == null,
                duration = message.duration,
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.action?.invoke()
            }
        }
    }

    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .systemBarsPadding(),
    )
}
