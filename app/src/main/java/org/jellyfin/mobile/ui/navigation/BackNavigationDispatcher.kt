package org.jellyfin.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState

/**
 * 返回键的唯一仲裁点。
 *
 * 整个应用只注册一个 `BackHandler`（在 [JellyfinNavHost] 里），需要比导航栈更早处理
 * 返回的界面不自己注册 BackHandler（那样优先级取决于注册顺序，散落在各处无从推断），
 * 而是把处理函数登记到这条链上：
 *
 * - 播放器浮层：先关面板/菜单，锁屏时吞掉按键，否则关闭播放器；
 * - 页面内的临时状态（下载页多选）：清空选择。
 *
 * 登记顺序即优先级，后登记的先处理：浮层比它下面的页面晚组合，因此天然优先。
 * 链上没人处理时，宿主按固定顺序回落 ——
 * `web` 路由把返回交给 webapp 的 `NavigationHelper.goBack()`，
 * 其余路由交给 `NavController` 弹出或系统处理。
 */
@Stable
class BackNavigationDispatcher {
    private val handlers = mutableStateListOf<() -> Unit>()

    /** 是否有界面登记了返回处理；宿主据此决定 BackHandler 是否启用。 */
    val hasHandlers: Boolean
        get() = handlers.isNotEmpty()

    /** 把返回交给最上层的登记者；没有登记者时返回 false（未消费）。 */
    fun dispatchBack(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        handler()
        return true
    }

    internal fun push(handler: () -> Unit) {
        handlers.add(handler)
    }

    internal fun remove(handler: () -> Unit) {
        handlers.remove(handler)
    }
}

/**
 * 把 [handler] 登记到返回键仲裁链上，离开组合时自动撤销。
 *
 * [handler] 允许随重组变化（内部通过 [rememberUpdatedState] 取最新闭包），
 * 因此可以直接捕获界面状态，不必自己处理失效问题。
 */
@Composable
fun BackNavigationDispatcher.RegisterBackHandler(handler: () -> Unit) {
    val currentHandler = rememberUpdatedState(handler)
    DisposableEffect(this) {
        val wrapper = { currentHandler.value() }
        push(wrapper)
        onDispose { remove(wrapper) }
    }
}
