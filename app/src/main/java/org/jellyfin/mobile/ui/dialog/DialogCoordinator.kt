package org.jellyfin.mobile.ui.dialog

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 全应用对话框窗口仲裁。
 *
 * Compose 的 `Dialog` / `AlertDialog` 并不是当前界面里的一层，而是由
 * `WindowManager.addView` 挂上的独立窗口（TYPE_APPLICATION_PANEL）：
 *
 * - 窗口一添加，`FLAG_DIM_BEHIND` 立即把整屏压暗（用户看到的「屏幕整体变暗」），
 *   dim 与窗口内容是否已经绘制出来无关；
 * - 窗口按模态规则接管触摸，多个窗口叠加时后添加的窗口会盖住先添加的窗口；
 * - 两个窗口的 dim 会累积，压暗更明显，上层窗口还会把下层窗口的内容一起压暗。
 *
 * 本项目有两个互不感知的对话框宿主：设置页 `SettingsScreen` 的 `SingleSelectDialog`
 * （播放器类型等单选框），以及挂在 Activity 根树上的 [org.jellyfin.mobile.ui.components.AppDialogHost]
 * （更新提示 / 下载进度 / 安装权限 / 蓝牙权限 / WebView 提示，状态来自跨路由不清空的
 * Koin 单例）。后台自动检查更新、尚未处理的提示随时可能在设置页之上再挂一个窗口，
 * 于是出现「点播放器类型 → 屏幕变暗、选择框看不见、点哪儿都没反应」：
 * scrim 是窗口级的，两个窗口同时存在时它既压暗了屏幕，也挡住了选择框的交互。
 *
 * 约定：**用户主动点开的页面级对话框优先**。它显示期间根宿主不挂载自己的窗口，
 * 因此全应用同一时刻只有一个带 dim 的对话框窗口，scrim 永远与选择框属于同一个窗口，
 * 不会再出现「遮罩盖住选择框」。全局状态仍保留在各自的 holder 里，页面对话框关闭后
 * 根宿主自然恢复，不丢任何提示。
 */
class DialogCoordinator {
    private val _pageDialogActive = mutableStateOf(false)

    /** 当前是否有页面级对话框正在显示。 */
    val pageDialogActive: State<Boolean> get() = _pageDialogActive

    fun onPageDialogShown() {
        _pageDialogActive.value = true
    }

    fun onPageDialogDismissed() {
        _pageDialogActive.value = false
    }
}
