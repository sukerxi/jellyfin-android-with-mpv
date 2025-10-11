![structure](https://github.com/user-attachments/assets/0fc59a3b-8f43-4c40-a8e9-d68159424d80)



# Jellyfin Android — MPV 播放支持

这是官方 [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) 客户端的一个分支版本，增强了视频播放功能。

## 主要改进
- **基于 MPV 的视频播放器**：集成 MPV 播放器。
- **一致的用户界面**：在无缝集成原生播放功能的同时，保留了原版 Jellyfin 内置播放器的外观与操作体验（当然也保留了官方内置播放器）。
- **水平滑动快进/快退**：在视频画面上向左或向右滑动即可实现快退或快进——与许多主流视频应用的操作方式一致。

## 关于架构

- MpvCore

  作为Mpv单例封装 MPVLib (from [libmpv-android]( https://github.com/jarnedemeulemeester/libmpv-android.git) ) 

- MpvPlayer

  ​依据谷歌media3开发说明，实现了其提供的Player接口以接入media3架构。另外，目前换音轨、字幕轨切换功能，由于media3 相应结构对于基本切换有些过渡，权衡之后，舍弃了相应接口规范的实现。除此之外，MpvPlayer 已经具备基本的media3 player 功能，

最后，由于MpvPlayer 与 jellfin android官方使用的media3 内置的ExoPlayer 实现了相同的接口，官方代码改造非常小。



------

# Jellyfin Android — MPV Playback Support

This is a fork of the official [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) client, enhanced with improved video playback capabilities.

## Key Improvements

- **MPV-based video player**: Integrates the MPV playback engine.
- **Consistent user interface**: Maintains the look and feel of the original Jellyfin built-in player while seamlessly incorporating native playback features. The original ExoPlayer-based player remains available as an option.
- **Horizontal swipe for seeking**: Swipe left or right on the video surface to rewind or fast-forward—matching the gesture behavior found in many mainstream video apps.

## Architecture Overview

- **MpvCore**  
  A singleton wrapper around MPVLib (from [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android.git)).

- **MpvPlayer**  
  Implements Google’s Media3 `Player` interface to integrate into the Media3 framework. For now, track (audio/subtitle) switching functionality has been implemented outside the standard Media3 interfaces, as the official structures for basic track selection were deemed overly complex for current needs. Aside from this, MpvPlayer supports core Media3 player features.

Since MpvPlayer implements the same interface as the ExoPlayer used in the official Jellyfin Android client, only minimal modifications to the original codebase were required.
