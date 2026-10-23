# Jellyfin Android — MPV 播放支持

这是官方 [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) 客户端的一个分支版本，增强了视频播放功能。当前分支（`refactor/jetpack-compose`）完成了界面层的 Jetpack Compose 重构，并在此基础上继续扩展了播放相关能力。

## 主要改进

- **基于 MPV 的视频播放器**：集成 MPV 播放引擎，与官方内置的 ExoPlayer 播放器并存，可在设置中切换；同时保留 Web 播放器与外置播放器选项，界面与操作体验保持原版 Jellyfin 的风格。
- **Jetpack Compose 界面重构**：单 Activity + Navigation Compose + Material 3，偏好设置迁移到 Preferences DataStore；播放器以全屏浮层覆盖在当前界面之上，退出播放后回到原页面，WebView 状态不丢失。
- **播放器同帧对比**：播放时可截取当前帧，用独立 mpv 实例渲染同一帧，对比不同 mpv 配置，或直接用 mpv 与 ExoPlayer 对比解码和画面；提供滑动、闪烁、差异图与缩放查看，入口可在设置中关闭。
- **MPV 自定义配置**：设置页内置 mpv.conf 编辑器，支持 `option=value`、`#` 注释与 `[profile]` 段，保存后在 mpv 初始化时生效（重启应用）。
- **播放信息面板**：分组展示播放方式、播放内核、音视频与字幕轨道、码率及缓冲状态，展开后可查看媒体技术细节与服务端返回的转码原因。
- **移动网络流量控制**：蜂窝网络下播放前提示，可选择低码率播放、继续原画质或取消；低码率档位可选 480p / 720p / 1080p，移动网络下的播放缓冲会自动收紧一档。
- **统一的播放缓冲区设置**：ExoPlayer 与 mpv 共用同一档位；mpv 通过 `cache-secs` 与 `demuxer-max-bytes` 双重限制预读，避免高码率下时长限制失效。
- **整季选集**：播放器内可直接切换当前季的其他剧集，播放队列覆盖整季。
- **播放失败回退**：按「直接播放 → 直接流 → 转码」逐级重试；mpv 打不开流时同样会触发回退，而不是停在加载状态。
- **应用内更新**：从 GitHub / CNB Releases 检查新版本、查看更新说明并下载安装。

## 关于架构

- **MpvCore**
  以单例封装 MPVLib（来自 [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android.git)）。所有 libmpv 调用（初始化、属性读写与命令）都排在专用单线程 IPC 执行器上，初始化在后台线程完成，不阻塞主线程。
- **MpvPlayer**
  依据 Google media3 开发说明实现 `Player` 接口以接入 media3 架构。换音轨、字幕轨切换因 media3 相应结构对基本切换而言过于繁琐，未走标准接口；除此之外具备完整的 media3 player 能力，播放状态按 ExoPlayer 的语义上报。
- **MpvConfigManager**
  把用户配置写成 mpv 能直接读取的 mpv.conf，并在初始化时再次下发给原生实例，确保自定义配置真正作用于视频输出。
- **播放器对比层（mpv-compare）**
  用独立 mpv 实例渲染指定帧并回读像素，与基线帧逐像素比较，输出差异值、差异图与缩放结果，不打断当前播放。
- **界面与偏好**
  全部界面由 Compose 承载，偏好设置使用 Preferences DataStore；播放器是覆盖在导航之上的全屏浮层，避免推入新路由时销毁 WebView。

由于 MpvPlayer 与官方使用的 ExoPlayer 实现了相同的 `Player` 接口，上层播放逻辑对两者基本透明。

## 下载

- [GitHub Releases](https://github.com/sukerxi/jellyfin-android-with-mpv/releases)
- [CNB Releases](https://cnb.cool/sukerxi/jellyfin-android-with-mpv/-/releases)

***

# Jellyfin Android — MPV Playback Support

This is a fork of the official [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) client with improved video playback. The current branch (`refactor/jetpack-compose`) rebuilds the UI in Jetpack Compose and extends the playback features on top of the MPV integration.

## Key Improvements

- **MPV-based video player**: Integrates the MPV playback engine alongside the official built-in ExoPlayer player; switch between them in settings. The Web player and external-player options are still available, and the UI keeps the look and feel of the original Jellyfin player.
- **Jetpack Compose UI**: A single Activity with Navigation Compose and Material 3; preferences use Preferences DataStore. The player is a full-screen overlay over the current screen, so leaving playback returns to the same page and keeps the WebView alive.
- **Same-frame player comparison**: Capture the current frame during playback and render it with independent mpv instances to compare mpv configurations, or compare mpv against ExoPlayer directly. Swipe, flicker, difference and zoom views are available; the entry can be disabled in settings.
- **MPV custom configuration**: A built-in mpv.conf editor in settings supports `option=value`, `#` comments and `[profile]` sections. Changes are applied when mpv initializes (restart the app).
- **Playback info panel**: Shows the play method, player engine, audio/video/subtitle tracks, bitrate and buffer state in groups, with expandable technical details and the transcode reasons reported by the server.
- **Mobile data controls**: Prompt before playback on cellular, with options to play at a lower bitrate, continue at the original quality or cancel. The lower-bitrate tier is selectable (480p / 720p / 1080p), and the playback buffer automatically drops one step on mobile networks.
- **Unified playback buffer setting**: ExoPlayer and mpv share the same tier; mpv caps read-ahead with both `cache-secs` and `demuxer-max-bytes`, so the time limit cannot be defeated by high-bitrate content.
- **Whole-season episode picker**: Switch to another episode of the current season from the player; the playback queue spans the whole season.
- **Playback fallback**: Failed playback retries step by step (direct play → direct stream → transcode); a stream mpv cannot open also triggers the fallback instead of leaving the player loading forever.
- **In-app updates**: Check GitHub / CNB releases for new versions, read the changelog, download and install.

## Architecture Overview

- **MpvCore**
  A singleton wrapper around MPVLib (from [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android.git)). Every libmpv call (initialization, property reads/writes and commands) is serialized on a dedicated single-thread IPC executor; initialization runs on a background thread and never blocks the main thread.
- **MpvPlayer**
  Implements Google's Media3 `Player` interface to integrate into the Media3 framework. Audio/subtitle track switching is implemented outside the standard Media3 interfaces, as the official structures were deemed overly complex for basic track selection. Aside from this, MpvPlayer supports the core Media3 player features and reports playback state with ExoPlayer semantics.
- **MpvConfigManager**
  Writes the user configuration to an mpv.conf that mpv reads directly, and reapplies it to the native instance during initialization, so custom options actually reach the video output.
- **mpv-compare layer**
  Renders a chosen frame with independent mpv instances, reads the pixels back and compares them against the baseline frame pixel by pixel, producing a difference score, difference image and zoomed results without interrupting ongoing playback.
- **UI and preferences**
  All screens are built with Compose, preferences live in Preferences DataStore, and the player is a full-screen overlay above the navigation graph so pushing a new route cannot destroy the WebView.

Since MpvPlayer implements the same `Player` interface as the ExoPlayer used in the official Jellyfin Android client, the playback logic above it is largely transparent to either engine.

## Downloads

- [GitHub Releases](https://github.com/sukerxi/jellyfin-android-with-mpv/releases)
- [CNB Releases](https://cnb.cool/sukerxi/jellyfin-android-with-mpv/-/releases)

