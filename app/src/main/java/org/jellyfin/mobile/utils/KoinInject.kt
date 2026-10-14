package org.jellyfin.mobile.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * [koinInject] 的 Koin 解析入口。
 */
object KoinInjector : KoinComponent

/**
 * 本地实现的 koinInject()，替代 org.koin.compose.koinInject()。
 *
 * koin-androidx-compose 4.x 基于 Compose Multiplatform，会传递引入
 * org.jetbrains.skiko —— 该桌面端运行时 Android 侧完全用不到，且其 Gradle
 * Module Metadata 会把 Android 消费者重定向到未发布的 skiko-android 变体，
 * 在部分镜像源上解析失败并直接中断构建（见 git 历史中此前的修复尝试）。
 *
 * 本项目所有注入点均为无 qualifier 的应用级单例（定义见 AppModule），
 * 用 koin-core 的 KoinComponent.get() 从根作用域解析即可，语义与
 * 原 koinInject() 一致：同一次组合期间记住同一实例。
 */
@Composable
inline fun <reified T> koinInject(): T = remember { KoinInjector.get<T>() }
