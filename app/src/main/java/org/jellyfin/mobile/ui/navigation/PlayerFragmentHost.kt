package org.jellyfin.mobile.ui.navigation

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import org.jellyfin.mobile.player.interaction.PlaybackLauncher
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.utils.Constants
import org.koin.compose.koinInject

private const val PLAYER_FRAGMENT_TAG = "jellyfin_player_fragment"

/**
 * 阶段 4 的临时播放器宿主：PlayerFragment 尚未 Compose 化（阶段 5），
 * 这里在 player 路由内放一个 [FragmentContainerView] 承载它。
 *
 * 双向同步：
 * - 系统返回 / 上层路由弹出 -> onDispose 中把 Fragment 回退栈条目连同 Fragment 一起弹出；
 * - Fragment 自行关闭（播放结束、工具栏返回，内部走 parentFragmentManager.popBackStack()）
 *   -> OnBackStackChangedListener 监听到 Fragment 消失后回调 [onClose] 同步弹出 Compose 路由。
 */
@Composable
fun PlayerFragmentHost(
    onClose: () -> Unit,
    playbackLauncher: PlaybackLauncher = koinInject(),
) {
    val activity = LocalContext.current as FragmentActivity
    // 仅在首次进入组合时取一次播放参数
    val playbackRequest = remember { playbackLauncher.consume() }

    if (playbackRequest == null) {
        // 没有待播放请求（如进程重建后恢复到 player 路由），直接关闭
        DisposableEffect(Unit) {
            onClose()
            onDispose { }
        }
        return
    }

    val fragmentManager = activity.supportFragmentManager

    DisposableEffect(Unit) {
        var closedByFragment = false

        val backStackListener = FragmentManager.OnBackStackChangedListener {
            if (fragmentManager.findFragmentByTag(PLAYER_FRAGMENT_TAG) == null) {
                closedByFragment = true
                onClose()
            }
        }
        fragmentManager.addOnBackStackChangedListener(backStackListener)

        onDispose {
            fragmentManager.removeOnBackStackChangedListener(backStackListener)
            // 路由先于 Fragment 关闭（系统返回）：连同回退栈条目一起立即弹出
            if (
                !closedByFragment &&
                !fragmentManager.isStateSaved &&
                fragmentManager.findFragmentByTag(PLAYER_FRAGMENT_TAG) != null
            ) {
                fragmentManager.popBackStackImmediate(PLAYER_FRAGMENT_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }
    }

    AndroidView(
        factory = { context ->
            FragmentContainerView(context).apply {
                id = View.generateViewId()
                val fragment = PlayerFragment().apply {
                    arguments = bundleOf(
                        Constants.EXTRA_MEDIA_PLAY_OPTIONS to playbackRequest.playOptions,
                        Constants.EXTRA_WEB_PREFERENCES to playbackRequest.preferences,
                    )
                }
                fragmentManager.commit {
                    add(id, fragment, PLAYER_FRAGMENT_TAG)
                    addToBackStack(PLAYER_FRAGMENT_TAG)
                }
            }
        },
    )
}
