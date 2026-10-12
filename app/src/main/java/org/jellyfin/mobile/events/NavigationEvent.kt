package org.jellyfin.mobile.events

/**
 * 需要导航到具体 Compose 路由的事件，由根 NavHost collect。
 */
sealed interface NavigationEvent {
    data object OpenSettings : NavigationEvent
    data object OpenDownloads : NavigationEvent
    data object LaunchPlayer : NavigationEvent
}
