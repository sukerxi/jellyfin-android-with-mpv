# Jetpack Compose 重构实施计划（分支：refactor/jetpack-compose）

## 一、仓库调研结论

### 1.1 架构现状

* 单 `MainActivity`（AppCompatActivity）+ Fragment 事务导航（`activity_main.xml` 只有一个 `fragment_container`）

* 导航事件来源：WebView JS 桥 → `ActivityEvent` → `ActivityEventHandler`（`addFragment<PlayerFragment/DownloadsFragment/SettingsFragment>`）

* 已部分 Compose 化（**Material 2**）：

  * `setup/ConnectFragment.kt`（仅宿主，UI 在 `ui/screens/connect/`）

  * `downloads/DownloadsFragment.kt` + `ui/screens/downloads/`（3 个文件）

  * 公共：`ui/ComposeFragment.kt`、`ui/utils/AppTheme.kt`（M2 darkColors 硬编码品牌色）

* 仍为 View/XML 的界面：

  * `settings/SettingsFragment.kt`（383 行，**de.maxr1998:modernandroidpreferences 2.4.0-beta2** DSL：screen/checkBox/singleChoice/categoryHeader/pref）

  * `settings/MpvConfigEditorFragment.kt` + `fragment_mpv_config_editor.xml`（EditText + Toolbar）

  * `player/ui/PlayerFragment.kt`（429 行）+ `fragment_player.xml`（332 行）+ `exo_player_control_view.xml`（290 行）

  * `player/ui/PlayerMenus.kt`（782 行：5 个 `PopupMenu`、`GridView` 选集、`LinearLayout` 动态拼装信息面板）

  * `player/ui/PlayerGestureHelper.kt`（478 行）、`PlayerLockScreenHelper.kt`、`TrickplayHelper.kt`、`playermenuhelper/`（3 文件）

  * `webapp/WebViewFragment.kt`（238 行）+ `fragment_webview.xml`（WebView + Material `CircularProgressIndicator`）

  * 4 处 appcompat/platform `AlertDialog`：`MainActivity`（WebView 不支持）、`WebViewFragment`（错误）、`BluetoothPermissionHelper`、`update/UpdateManager`（3 个对话框：更新、进度、安装权限）

* 偏好存储：手写 `AppPreferences`（构造 Context），SP 文件名 = `<packageName>_preferences`（恰好是 `PreferenceManager.getDefaultSharedPreferencesName` 的默认名，DataStore 迁移可无缝对接）

* 播放器渲染特殊约束（**不能也不应 Compose 化的部分**）：

  * ExoPlayer 与 `MpvPlayer`（media3 `SimpleBasePlayer`）共用同一个 `androidx.media3.ui.PlayerView`；`MpvPlayer.kt:282` 直接取 PlayerView 内部 `SurfaceView` 的 `SurfaceHolder` attach native mpv surface

  * libass：`AssSubtitleView` 被 addView 进 PlayerView 的 subtitleView 容器（`PlayerFragment.kt:226-230`）

  * 结论：**PlayerView 本体通过** **`AndroidView`** **保留**，覆盖在其上方的控件栏/手势层/菜单/面板全部 Compose 重写；`PlayerFullscreenHelper`（窗口逻辑）保留

### 1.2 第三方库审计与处置

| 库                                                          | 用途                                       | 处置                                                |
| ---------------------------------------------------------- | ---------------------------------------- | ------------------------------------------------- |
| `de.maxr1998:modernandroidpreferences`                     | 设置页 DSL                                  | **移除**，自建 M3 偏好组件                                 |
| `com.google.android.material`（View 版）                      | WebView loading 圈、SystemUtils 的 Snackbar | **移除**（Snackbar→SnackbarHost；loading→M3 指示器）      |
| `androidx.constraintlayout`                                | 3 个 XML 布局                               | **移除**（XML 全部删除后无引用）                              |
| `androidx.appcompat`                                       | AppCompatActivity/Toolbar/AlertDialog    | **移除**（→ ComponentActivity + M3 TopAppBar/Dialog） |
| `androidx.fragment`（含 koin fragment 工厂）                    | Fragment 导航                              | **移除**（最后阶段）                                      |
| `androidx.media3:media3-ui`                                | PlayerView、DefaultTimeBar                | **保留**（AndroidView 互操作；TimeBar 可先保留后评估）           |
| `androidx.webkit`                                          | WebViewAssetLoader 等                     | **保留**（非 UI）                                      |
| `androidx.mediarouter` + playservices-cast                 | Chromecast                               | **保留**（非 Compose 范畴，UI 入口在 Web 端）                 |
| `io.github.peerless2012:ass-media`、`dev.jdtech.mpv:libmpv` | 字幕/渲染                                    | **保留**（native/View 互操作）                           |
| coil3（含 coil-compose）、koin 4.2.2                           | 图片/DI                                    | **保留**（已是 Compose 友好版）                            |

## 二、关键决策（用户已确认）

1. **设计系统：Material 3**——现有 Connect/Downloads 两屏同步从 M2 改造为 M3
2. **偏好存储：Preferences DataStore**——带 `SharedPreferencesMigration` 一次性迁移，保留同名 SP 文件映射
3. **导航：单 Activity（ComponentActivity）+ navigation-compose**——彻底移除 Fragment/appcompat/constraintlayout 与全部 XML 布局
4. **播放器控制层纳入重构，放最后阶段**——PlayerView 保留 AndroidView，覆盖层全部 Compose

## 三、新增依赖（libs.versions.toml）

* `androidx.datastore:datastore-preferences` 1.2.1

* `androidx.navigation:navigation-compose` 2.10.2

* `androidx.activity:activity-compose`（当前 `androidx.activity` 是基础包，需显式加 compose 工件，版本对齐 1.13.0）

* `androidx.lifecycle:lifecycle-viewmodel-compose`、`lifecycle-runtime-compose`（2.11.0，collectAsStateWithLifecycle）

* `androidx.compose.material3:material3` 1.4.0（由 BOM 管理）

* `io.insert-koin:koin-androidx-compose-navigation`（4.2.2，Koin 4 的 NavHost 注入工件）——**阶段 4 才引入**：腾讯镜像有其 pom/module 但缺 aar（POM 锁定仓库不回落），引入时需把阿里云 public 镜像调到腾讯镜像之前

* 依赖对齐策略：引入 Compose BOM 2026.09.00 统一 runtime/ui/foundation/animation/material3 版本（BOM 中上述工件恰为 1.12.1，与现状一致，material-icons 仍显式 1.7.8）

* 镜像可用：aliyun google 镜像与腾讯云镜像均含上述 Google 工件；首次拉取不能加 `--offline`，之后可离线构建

## 四、分阶段实施（每阶段结束都必须可编译可装机）

### 阶段 0 —— 基础设施：依赖与 M3 主题

* `libs.versions.toml`/`app/build.gradle.kts`：引入 Compose BOM 2026.09.00（runtime/ui/foundation/animation 仍解析为 1.12.1，icons 1.7.8）、material3 1.4.0、activity-compose 1.13.0、navigation-compose 2.10.2、lifecycle-viewmodel-compose/runtime-compose 2.11.0、datastore-preferences 1.2.1、koin-androidx-compose-navigation 4.2.2

* 新建 `ui/theme/`：`Color.kt`（0xFF00A4DC 品牌色 + 暗色板）、`Theme.kt`（M3 `darkColorScheme`，暂不启用动态取色，保持品牌一致）；本阶段不接线，旧 M2 AppTheme 继续工作，阶段 1 随屏幕迁移切换

* **DataStore 切换后移到阶段 2a**：modernandroidpreferences 直接持久化到旧 SP 文件，若先切 DataStore，设置页写入与新存储会分叉（播放器类型/手势开关立即失效）。DataStore 必须与"设置页 Compose 化"在同一切换点落地

* 验证：编译通过；模拟器安装基线包可启动（本阶段无行为变化）

### 阶段 1 —— 现有 Compose 屏 M2 → M3

* 改造文件：`ui/screens/connect/ConnectScreen.kt`、`ServerSelection.kt`、`ServerSuggestion.kt`、`ui/screens/downloads/DownloadsScreen.kt`、`DownloadsList.kt`、`DownloadRemoveDialog.kt`、`ui/utils/DownloadSettingsDialog.kt`、`CenterRow.kt`

* 主要 API 差异：`material`→`material3` 导入；`TopAppBar`（M3 新签名 + `TopAppBarDefaults`）、`Scaffold` contentWindowInsets/padding 处理、`AlertDialog` 按钮槽、`Card`  elevation 参数、`CircularProgressIndicator`（M3 strokeWidth API）、`Switch`/颜色、`Button` contentColor、`Surface` color 参数

* 行为零变化，纯视觉/API 迁移

* 验证：编译 + 模拟器走查连接页、选服、下载列表/删除对话框/下载设置对话框

### 阶段 2 —— DataStore 迁移 + 设置页 + mpv 配置编辑器 Compose 化

#### 2a. 偏好存储切换（先行）

* 新建 `app/preferences/`：

  * `PreferencesDataStore.kt`：`preferencesDataStore(name = "settings")` + `SharedPreferencesMigration(context)`（默认 SP 名即 `<pkg>_preferences`，自动迁移全部旧键）

  * `AppPreferences` 重写为 DataStore 支撑的仓库：

    * 每个属性暴露 `Flow<T>`（Compose 用 `collectAsStateWithLifecycle()`）

    * **同步读路径保留**：播放器/DownloadWorker/MpvConfigManager 等非 UI 调用点需要同步 getter。方案 = 应用启动时（`JellyfinApplication.onCreate`）一次性 `runBlocking { dataStore.data.first() }` 预热内存快照，之后同步 getter 读快照、写操作走 DataStore（同步更新快照）。属性名与类型保持不变，调用点尽量零改动

    * `MpvCore` 自己的 `PREFS_NAME` 独立 SP **不动**

* 验证：覆盖安装旧版后各偏好值保留（重点验证开关、下载方式 int、存储位置 string、mpv 配置文本）

#### 2b. 设置界面

* 新建 `ui/screens/settings/`：

  * `SettingsScreen.kt`（M3 Scaffold + TopAppBar 返回键 + `LazyColumn`）

  * 偏好组件：`SettingsCategoryHeader`、`SwitchPreferenceRow`、`ClickPreferenceRow`、`SingleSelectPreferenceRow`（点击弹 M3 `AlertDialog` 单选，替代 `singleChoice` 子屏）

  * 分区逻辑（音乐/视频/下载/更新）与 `SettingsFragment.buildSettingsScreen()` 一一对应；**播放器类型联动的"重建整屏"改为响应式条件渲染**（DataStore flow 驱动，无需 rebuild/post/scroll hack）

  * `singleChoice` 的二次进入子屏 → 就地弹窗选择（交互更现代；含网络缓冲、外部播放器、下载方式三组选择项；外部播放器列表继续按已安装包过滤）

  * SAF 存储位置：`rememberLauncherForActivityResult(OpenDocumentTree)`；字幕样式跳系统设置；"检查更新"按钮的 Toast → Snackbar 并复用更新对话框控制器（阶段 3 产物，先接接口）

* 新建 `ui/screens/settings/MpvConfigEditorScreen.kt`：M3 TopAppBar + `TextField`（monospace、multiline、noSuggestions）+ 重置/保存按钮；校验继续用 `MpvConfigManager.findInvalidLine`，保存后写 DataStore + `syncConfigFile`；摘要随 DataStore 流自动刷新（**删除 FragmentResult 监听机制**）

* 宿主暂保留为 Fragment（薄壳，同 ConnectFragment 模式），阶段 4 随导航迁移删除

* 验证：逐项对照旧设置；切换 4 种播放器类型确认显隐项正确；mpv 编辑器保存/非法行提示/摘要刷新

### 阶段 3 —— 更新对话框 Compose 化

* `UpdateManager` 职责拆分：

  * 保留纯逻辑：检查入口、`UpdateDownloadWorker.enqueue/cancel`、`launchInstall`、安装权限判断

  * 新增 `UpdateDialogStateHolder`（Koin single）：保存当前待展示的 `UpdateInfo`/进度状态事件

  * 新增 `ui/components/UpdateDialogs.kt`：`UpdateAvailableDialog`（M3 AlertDialog，正文用可滚动 Text + AnnotatedString，替代 HtmlCompat 拼装）、`UpdateProgressDialog`（M3 `LinearProgressIndicator` + 文案，collect `UpdateDownloadWorker.observeProgress`）、`InstallPermissionDialog`

  * 在根 Compose 宿主挂 `UpdateDialogHost`，供 MainActivity 启动自动检查与设置页"立即检查"共用——**解决非 Compose 上下文弹对话框问题**

* `MainActivity` 的 WebView 不支持对话框、`BluetoothPermissionHelper`、`WebViewFragment` 错误对话框同步改 Compose/M3（BluetoothPermissionHelper 改为在 Compose 层观察状态弹对话框）

* 验证：自动检查弹窗、三按钮、前台进度对话框、后台静默、安装权限引导

### 阶段 4 —— 单 Activity + navigation-compose（除播放器外的 Fragment 全删）

* `MainActivity`：`AppCompatActivity` → `ComponentActivity`；删除 `setContentView(R.layout.activity_main)` 与 Fragment 事务逻辑，`setContent { AppTheme { JellyfinNavHost() } }`；保留 splash、edgeToEdge、服务绑定、方向监听、权限回调（改 `rememberLauncherForActivityResult`）、Chromecast 初始化、自动更新检查

* 新建 `ui/navigation/JellyfinNavHost.kt`，路由：

  * `connect`、`web`、`downloads`、`settings`、`mpv_config_editor` 为纯 composable 目的地

  * `player` 临时用 `FragmentHostView`（AndroidView 包 `FragmentContainerView`）承载现有 `PlayerFragment`，阶段 5 删除

  * `serverState`（MainViewModel）驱动 connect↔web 的 replace 式跳转（popUpTo(0)）

  * `ActivityEventHandler`：OpenSettings/OpenDownloads/LaunchNativePlayer 改为向 navController 发导航事件（在根 composable 用 LaunchedEffect collect）；PlayOptions Parcelable 用共享的 `PlaybackLauncher`（Koin single，存一次待播放参数）传入，避免自定义 NavType 复杂性

* 新建 `ui/screens/web/WebViewScreen.kt`：`AndroidView(factory = { WebView })`；`JellyfinWebViewClient`/WebChromeClient/AssetLoader 逻辑原样保留；loading 覆盖层 Compose 化；`BackHandler { if (webView.canGoBack()) ... }`；file chooser 用 `rememberLauncherForActivityResult`

* 删除：`ConnectFragment`、`WebViewFragment`、`DownloadsFragment`、`SettingsFragment`、`MpvConfigEditorFragment`、`ComposeFragment`、`fragment_*.xml`（settings/webview/mpv/compose/activity\_main）、`BackPressInterceptor` 及 FragmentManager 扩展；Koin 的 `fragment { }` 注册与 `setupKoinFragmentFactory()` 移除（koin-androidx-fragment 传递依赖随主依赖一起消失）

* `styles.xml` 中 AppTheme 父主题从 AppCompat 改为平台 `android:Theme.Material.NoActionBar`（仅作 splash 后兜底，窗口背景色保留）

* 验证：全量真机走查：冷启动选服→Web 首页→JS 桥打开设置/下载/播放器（临时宿主）→返回栈与各 BackHandler；旋转（configChanges 已声明，Activity 不重建）

### 阶段 5 —— 播放器控制层 Compose 化（最重阶段）

* 新建 `ui/screens/player/`：

  * `PlayerScreen.kt`：`AndroidView { PlayerView }` 全屏渲染面（**factory 只创建一次**，update 回调里绑定 player；确保 recomposition 不重建 surface）+ Compose 覆盖层

  * `PlayerControls.kt`（替代 exo\_player\_control\_view\.xml 290 行）：播放/暂停/上下集/上下章/锁屏/音轨/字幕/倍速/清晰度/解码器/信息/选集按钮、进度区

  * 5 个 `PopupMenu` → Compose 菜单（`DropdownMenu` 或 `ModalBottomSheet`）：音轨、字幕、倍速、清晰度、解码器

  * `PlaybackInfoPanel.kt`：替代 LinearLayout 动态 inflate，Compose 卡片 + LazyColumn 分段展示（沿用此前重构过的"只看有效轨道/紧凑卡片"规则）

  * `EpisodePickerPanel.kt`：GridView → `LazyVerticalGrid`（自适应宽度、看过状态、当前高亮、SP 标记）

  * `GestureOverlay.kt`：亮度/音量/seek/双击播放/长按倍速用 `pointerInput { detectTapGestures/detectDragGestures }` + 状态驱动覆盖物（替代 PlayerGestureHelper 直接操作 View）

  * `LockScreenOverlay.kt`、`TrickplayPreview.kt`（缩略图用 coil `Image`/AndroidView ImageView，章节名/时间文本 Compose 化；TrickplayHelper 的取图逻辑保留、View 操作改写为状态）

  * `SkipSegmentButton` 用 Compose AnimatedVisibility

  * 进度条：初期在 Compose 控件栏中以小 `AndroidView` 保留 `DefaultTimeBar`（保 trickplay scrubbing 行为零回归），验证稳定后再评估是否换 Compose Slider

* 平台逻辑保留并改造：

  * `PlayerFullscreenHelper`（窗口沉浸/insets）原样保留；insets 用 `WindowInsets`/配合 PlayerView padding

  * PiP：`onUserLeaveHint` 路径改由 Koin 单例 `PlaybackController`（PlayerScreen 注册/注销）桥接 MainActivity；PiP 源矩形基于 PlayerView 测量

  * MpvPlayer attach SurfaceView 逻辑不变（同一个 PlayerView 实例）；AssSubtitleView addView 逻辑不变

* 删除：`PlayerFragment`、临时 FragmentHost、`fragment_player.xml`、`exo_player_control_view.xml`、`item_episode_picker.xml`、`PlayerMenus.kt`、旧 Helper 中被替代的 View 代码、`androidx.fragment`/`appcompat`/`constraintlayout`/`google-material`/`modernandroidpreferences` 依赖、`viewBinding = true` 开关

* 验证（真机，重点回归区）：ExoPlayer 与 mpv 双内核各播一集；手势（亮度/音量/seek/双击/长按）、锁屏、横竖屏切换/全屏、PiP 进出、字幕开关/外挂/ASS 直放、音轨/清晰度/解码器菜单、倍速、章节、选集面板、跳过片头片尾按钮、trickplay 缩略图、上一集/下一集队列逻辑

## 五、涉及文件总览

* 构建：`gradle/libs.versions.toml`、`app/build.gradle.kts`（依赖与 buildFeatures）、`res/values/styles.xml`

* 新增：`ui/theme/*`、`app/preferences/*`、`ui/navigation/*`、`ui/components/UpdateDialogs.kt`、`ui/screens/settings/*`、`ui/screens/web/WebViewScreen.kt`、`ui/screens/player/*`

* 重写：`MainActivity.kt`、`events/ActivityEventHandler.kt`、`app/AppPreferences.kt`（或迁移到 preferences 包）、`update/UpdateManager.kt`、`utils/BluetoothPermissionHelper.kt`

* 删除：6 个 Fragment、8 个 XML 布局、`ComposeFragment.kt`、`BackPressInterceptor`、Fragment 扩展

* 不动：播放内核/数据源/队列/下载/WorkManager/数据库/JS bridge/sessionbrowser/AndroidAuto 服务等非 UI 层

## 六、风险与对策

1. **DataStore 同步读**（播放器/Worker 主线程外路径）：Application 启动预热快照 + 快照读取；禁止在这些路径引入 suspend 改造扩散
2. **PlayerView 在 Compose 中的生命周期**：factory 单次创建、不在 composition 中重建；播放器释放只在 onDispose/ViewModel 层；configChanges 已声明旋转不重建 Activity
3. **导航 Parcelable 参数**：用 PlaybackLauncher 暂存而非自定义 NavType，降低复杂度
4. **新依赖拉取**：本机 SSL 中间人拦截，走已配置的国内镜像；首次同步不加 `--offline`
5. **构建工具**：用本机已缓存的 Gradle 9.6.1 发行版替代 wrapper（`C:\Users\Admin\.gradle\wrapper\dists\gradle-9.6.1-bin\...\gradle.bat`）；编译任务 `:app:assembleProprietaryDebug`，flavor 默认 proprietary
6. **M3 视觉差异**：以"行为零变化"为准绳，每屏在模拟器对照旧屏走查；品牌色保持 0xFF00A4DC
7. **阶段可回滚**：每阶段一个提交，播放器阶段独立大提交；任一阶段失败不影响前序成果

## 七、验证方式

* 每阶段：`gradle.bat :app:assembleProprietaryDebug`（必要时先在线同步一次）+ detekt/lint

* 每阶段冒烟平台：**安卓模拟器（不用真机）**

  * emulator 程序：`D:\AndroidSDK\emulator\emulator.exe`（36.2.12，可执行；沙箱禁止向 D:\AndroidSDK 写入）

  * 系统镜像：经 cmdline-tools 安装 `system-images;android-34;default;x86_64`（AOSP 镜像 rev 4，无 Play 服务，API 34 / Android 14）

  * 由于沙箱拦截对 `D:\AndroidSDK` 的写操作，附加 SDK 根放在 `C:\Users\Admin\.local\share\android-sdk`，cmdline-tools 在 `C:\Users\Admin\.local\share\android-cmdline-tools\latest`；启动模拟器时以 `ANDROID_SDK_ROOT` 指向附加根（emulator 二进制仍从 D 盘 SDK 运行）

  * 旧的 API 30 TestAVD 与 emulator 36.2.12 不兼容（卡 offline），弃用；新建 API 34 AVD

  * 每阶段：`adb install -r` debug APK（applicationId `org.jellyfin.mobile.debug`），走查核心路径

* 阶段 0 额外验证：全新安装 + 用 adb 向 `<pkg>_preferences` 预置旧偏好后启动，验证 DataStore 迁移结果

* 阶段 5 按"双内核 × 手势/菜单/PiP/字幕"矩阵回归

