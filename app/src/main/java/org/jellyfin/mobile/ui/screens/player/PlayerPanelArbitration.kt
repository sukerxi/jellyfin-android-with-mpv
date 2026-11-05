package org.jellyfin.mobile.ui.screens.player

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable

/**
 * 播放器面板 / 菜单的互斥仲裁与返回键语义。
 *
 * 状态本身仍由 PlayerScreen 通过 remember 持有（本类只拿到 [MutableState] 引用），抽出来的是
 * 「谁该关、谁该开、返回键先处理谁」这组规则。抽之前它们散落在 handlePlayerBack、
 * PlayerControlsActions 的 onShowXxx / onMenuExpand 以及 PiP 回调里，新增面板时容易漏互斥。
 *
 * 语义与抽之前逐条对应，未新增或删减任何分支。
 */
@Stable
class PlayerPanelArbitration(
    private val infoVisible: MutableState<Boolean>,
    private val episodeVisible: MutableState<Boolean>,
    private val compareVisible: MutableState<Boolean>,
    private val menu: MutableState<PlayerMenuType?>,
) {
    /** 是否有任意面板 / 菜单正占用屏幕。 */
    val hasOpenPanel: Boolean
        get() = infoVisible.value || episodeVisible.value || compareVisible.value || menu.value != null

    fun closeAll() {
        menu.value = null
        infoVisible.value = false
        episodeVisible.value = false
        compareVisible.value = false
    }

    fun dismissInfo() {
        infoVisible.value = false
    }

    fun dismissCompare() {
        compareVisible.value = false
    }

    fun dismissEpisodes() {
        episodeVisible.value = false
    }

    /** 信息面板与选集 / 对比面板共用同一块区域，必须互斥。 */
    fun toggleInfo() {
        episodeVisible.value = false
        compareVisible.value = false
        infoVisible.value = !infoVisible.value
    }

    fun toggleCompare() {
        infoVisible.value = false
        episodeVisible.value = false
        compareVisible.value = !compareVisible.value
    }

    /**
     * 选集面板切换；打开时通过 [onFirstOpen] 触发一次队列元数据懒加载，关闭时不触发。
     *
     * @return 切换后选集面板是否可见。
     */
    fun toggleEpisodes(onFirstOpen: () -> Unit): Boolean {
        if (infoVisible.value) infoVisible.value = false
        return if (episodeVisible.value) {
            episodeVisible.value = false
            false
        } else {
            onFirstOpen()
            episodeVisible.value = true
            true
        }
    }

    /**
     * @return 调用前是否已有菜单展开（调用方用它决定 Android R 以下是否补一次全屏状态刷新）。
     */
    fun expandMenu(type: PlayerMenuType?): Boolean {
        val wasExpanded = menu.value != null
        menu.value = type
        return wasExpanded
    }

    /**
     * 系统返回与顶栏返回箭头共用：先关面板 / 菜单；锁屏时把解锁按钮叫出来；否则退出播放器。
     *
     * 「收起控件」不在这里 —— 点画面就能收起，让返回键兼职收控件会变成两套答案。
     */
    fun handleBack(locked: Boolean, onPeekUnlock: () -> Unit, onClose: () -> Unit) {
        when {
            hasOpenPanel -> closeAll()
            locked -> onPeekUnlock()
            else -> onClose()
        }
    }
}
