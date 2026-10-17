# mpv 配置画面对比：故障分析与重构说明

分支：`refactor/jetpack-compose`（本地重构分支 `fix/mpv-compare-rework`）

---

## 一、三个现象其实是一个根因

| 现象 | 直接原因 |
| --- | --- |
| 对比功能"完全无法工作"（面板空白、按钮置灰、要么一直转圈） | 单帧渲染的时序写反了，每个候选都等不到首帧事件而超时；即便出图，`apply-profile` 也无法回退，候选之间是累积关系 |
| 从视频里返回后黑屏（有声音） | 对比功能销毁并重建了**播放器正在使用的**那个 libmpv 实例，并把视频输出抢到一个离屏纹理上，之后没有还回去 |
| 竖屏时画面被竖向拉长 | 离屏 surface 和 `android-surface-size` 用的是 **dp**（约 411×891），而真实 surface 是 1080×2400；mpv 按 411×891 渲染，平台再拉伸到全屏，纵向被拉长约 22% |

换句话说：**旧实现把「对比」做成了对播放实例的一次入侵**——改它的配置文件、`apply-profile`、`reinitialize()`、抢它的 surface、改它的 `android-surface-size`，而且没有任何一条路径把它们还回去。三个 bug 都是这次入侵的副作用。

### 1.1 黑屏：播放实例被销毁/抢占

旧链路：

```
InPlayerCompareOverlay → controller.run()
  → ConfigRepository.install() → MpvConfigSwapper.reset()
      → MpvCore.reinitialize()          // 播放中销毁并重建 libmpv 实例
      → apply-profile __ab-baseline     // 在新实例上改选项
  → MpvSceneRendererImpl.render()
      → ensureSurface() → MpvCore.attachSurface(SurfaceTexture)   // 抢走视频输出
```

`MpvCore.reinitialize()` 会 `previous.mpv?.destroy()` 再 `MpvCore(app)`，而 `MpvPlayer` 完全不知道实例被换掉了：

- `MpvPlayer.attachedSurface` 仍指向 SurfaceView 的 `Surface`，于是 `attachSurface()` 的 `attachedSurface === surface` 早退，**永远不会重新 attach**；
- 新实例的 `vid` 也被 `detachSurface()`/`apply-profile` 改过；
- 结果：音频还在放（同一个 mpv 实例还在解码），画面却渲染进一个没人看的 `SurfaceTexture`。

`releaseSurface()` 定义了但**从未被调用**，`MpvConfigSwapper.uninstall()` 也只把 `mpv.conf` 写回去、不重建实例、不取回输出——所以关掉对比面板后不会自愈。

### 1.2 拉伸：`android-surface-size` 用 dp 且只在 `surfaceChanged` 更新

```kotlin
// 旧：InPlayerCompareOverlay
PlaybackSceneCapture.capture(
    viewportWidth = configuration.screenWidthDp,   // dp！
    viewportHeight = configuration.screenHeightDp, // dp！
)
```

mpv 的 `android-surface-size` 语义是"输出 surface 的像素尺寸"，mpv 会**按这个尺寸渲染**，再由平台把 buffer 拉伸到真实 surface。411×891 与 1080×2400 的宽高比不同（0.461 vs 0.450 → 实际是 0.461 vs 0.5625 的竖屏裁剪差异），因此画面被纵向拉长。

更麻烦的是它只在 `SurfaceHolder.surfaceChanged` 里更新：surface 重新 attach 但尺寸没变时不会回调，残留的 dp 尺寸就一直生效——所以是"有时候"。

### 1.3 完全不可用：首帧事件永远等不到

```kotlin
// 旧：MpvSceneRendererImpl.renderOne()
applySceneOptions()               // 里面 setProperty("pause", true)
loadfile ... replace 0 start=+pos
apply-profile <candidate>
awaitEvent<PlaybackRestart>("渲染首帧") { MpvCore.setProperty("pause", true) }
```

`pause=yes` 在 `loadfile` **之前**就设了，文件以暂停状态加载，mpv 不会"重新开始播放"，`MPV_EVENT_PLAYBACK_RESTART` 不会来（每个候选都要空等 5s 超时）。所有候选都失败 → `baseline == null` → UI 三个分支都不命中（`state.error` 也是 null，因为失败被吞进 `SceneFrame.failure`）→ 面板一片空白。

即便事件来了，`apply-profile` 也解决不了"对照"这件事：

- `__ab-baseline` 依赖 `profile=all-defaults`，mpv **没有**这个内置 profile，重置不会发生；
- `apply-profile` 只能向前叠加，candidate N 实际带着 1..N 全部改动，标签写的却是单项；
- 对已经开始解码的文件，`hwdec` 这类非实时选项根本不生效——看起来"改了没变化"。

其余：`FrameCompareViewModel.swapper()` 每次 new 一个新 swapper，`appliedChunks` 状态直接丢失；`MpvConfigCompareState.compared` 在 composition 里对整帧逐像素重算差异（卡顿）；配置按行切块，多行/分段配置被拆散。

---

## 二、重构后的结构

核心原则：**对比只读播放器，不改播放器。**

| 文件 | 角色 |
| --- | --- |
| `bench/MpvBenchInstance.kt`（新） | 一次性的独立 libmpv 实例：自己的 config-dir、自己的离屏 surface、自己的事件队列；每次只渲染一帧，`close()` 里销毁实例、释放 surface、删掉临时目录 |
| `bench/MpvBenchConfig.kt`（新） | 把"当前保存的配置"和"编辑中的配置"做行级 diff，生成基线 + 每项改动（新增/改值/删除）+ "全部改动合计"，每个候选都是**完整的一份 mpv.conf** |
| `bench/MpvSceneRendererImpl.kt`（重写） | 每个候选一个实例，渲染失败只影响该候选并带上原因；带进度回调 |
| `bench/MpvConfigCompareController.kt`（重写） | 只负责候选生成、调度、状态发布；唯一会落盘的操作是用户点"采用这份配置" |
| `bench/MpvConfigCompareState.kt`（重写） | 差异在渲染完成时算一次（后台线程），UI 只读快照 |
| `bench/PlaybackSceneCapture.kt` | 取 URL/位置/视口，视口是**像素**尺寸（来自 `PlayerView.videoSurfaceView`） |
| `ui/screens/mpvcompare/InPlayerCompareOverlay.kt`（重写） | 打开时暂停播放并冻结当前帧，关闭时恢复；显示渲染进度、失败原因、"配置未修改"提示 |
| `player/mpv/MpvCore.kt` | 删掉 `reinitialize()`（它唯一的用途就是这个有问题的对比），把基础选项抽成 `baseOptions()` 给播放器和对比实例共用，避免两份清单漂移 |
| `player/mpv/MpvPlayer.kt` | surface (重新) attach 时同步一次像素尺寸，不再只依赖 `surfaceChanged` |

删除的旧实现：`MpvConfigProfiles.kt`、`MpvConfigSwapper.kt`、`ConfigRepository.kt`、`MpvConfigChunks.kt`、`MpvFrameStore`。

### 为什么是"每个候选一个实例"

mpv 只有在初始化时才读 `mpv.conf`。任何"运行时换配置"的方案都必须回答"怎么把上一项的选项撤掉"，而 `apply-profile` 没有这个能力。为每个候选起一个只活几秒的实例，代价是每个候选多一次解码启动，换来的是：

- 候选之间零污染，顺序无关；
- 基线帧也用同一条路径渲染，和播放器的选项集完全一致（共用 `baseOptions`）；
- 播放器实例、`mpv.conf`、`android-surface-size` 全程不被触碰——黑屏和拉伸在结构上不可能再发生。

### 一次对比的时序（`MpvBenchInstance`）

1. 把候选配置写进 `<cache>/mpv-bench/run-N/config/mpv.conf`；
2. 创建实例，套用 `baseOptions(configDir = 该目录)`，`init()`；
3. 强制 `ao=null`、`aid=no`、`sid=no`、`osd-level=0`、`keep-open=yes`（都不影响视频渲染）；
4. `android-surface-size = WxH`（像素）→ attach 离屏 `SurfaceTexture` 的 surface；
5. `loadfile url replace 0 start=+pos`；
6. 等 `FILE_LOADED` → 等 `PLAYBACK_RESTART`（此刻首帧已在屏上）→ `pause=yes` 冻结；
7. 把 `SurfaceTexture` 里的帧用自建 EGL 上下文画进 FBO，再 `glReadPixels` 读回像素（详见下一节）；
8. `stop` → `detach` → `destroy` → 释放 surface / 纹理 / EGL 上下文 → 删除临时目录。

### 取帧：三层方案，前两层都不可用

**这是"对比完全无法工作"的真正根因，和时序无关。**

**第一层：mpv 的 `screenshot`。** 它走 libavcodec：先 GPU 读回拿到帧，再用 `png`/`mjpeg` **编码器**编码、由 **muxer** 写盘。而这个 app 打包的 libmpv，其 FFmpeg 是按

```
--disable-{muxers,encoders,devices,filters}
```

编译的（libmpv-android `buildscripts/scripts/ffmpeg.sh`）。编码器和 muxer 都被关掉，所以**任何格式、任何模式的截图都写不出文件**，mpv 只在自己的日志里留一行失败，界面上就只剩"mpv 没有生成截图"。`screenshot-sw=yes` 也救不了——它换的是取帧方式，编码器仍然缺。

**第二层：`ImageReader`。** 绕开编码器直接读 buffer，听起来可行，但它要求生产端交出的 buffer 格式**和创建时指定的完全一致**。真机上 EGL/gralloc 未必配合：

```
The producer output buffer format 0x2b doesn't match the
ImageReader's configured buffer format 0x1
```

`0x1` 是我们申请的 `RGBA_8888`，`0x2b` 是这台设备的 GPU 实际决定输出的格式（10-bit/厂商自定义一类）。换 `RGBX_8888` 也一样是猜——没有哪个格式能被保证匹配，这条路在设备上不可靠。

**最终方案：`SurfaceTexture` + 自建 EGL 读回**（`GlFrameReader`）。`SurfaceTexture` 不挑格式，生产端给什么就收什么，然后把它当作 external texture 由我们自己读：

1. 建独立 EGL 上下文（`EGL14`，1x1 pbuffer 仅用于 `makeCurrent`）与 `GL_TEXTURE_EXTERNAL_OES` 纹理，`SurfaceTexture(texName)` 包成 `Surface` 交给 mpv；
2. 等 `onFrameAvailable` 回调 → `updateTexImage()` 把最新帧贴到纹理上（暂停后继续 latch，直到没有更新的帧，取到的就是 mpv 认为"当前"的那一帧）；
3. 把 external texture 用最简 shader（带 `getTransformMatrix`）画进一张 `RGBA_8888` 的 FBO；
4. `glReadPixels` 读回，按行镜像（GL 原点在左下、位图在左上）、alpha 强转不透明，生成位图。

这样：

- 不依赖 FFmpeg 编码器，也不依赖 gralloc 的格式协商；
- 拿到的是 GPU 真实输出，正是渲染配置对比要比较的东西；
- 全流程无编码、无临时文件。

附带处理：

- **渲染尺寸封顶**：`MpvSceneRendererImpl` 把视口按最长边 1080 等比缩小渲染（1440x3040 → 512x1080 一类）。原始视口每帧约 4.4MP，25 个候选仅像素就 400MB+，且 diff 会明显变慢；等比缩放不影响几何比例，也不影响任何渲染选项的效果。
- **失败要有解释**：取不到帧时会读 `vo-configured`，为 `no` 就报"该配置无法初始化视频输出（vo=…）"——这是对比的合法结论（某个 `vo`/GPU 选项在本机跑不起来），而不是取帧 bug。

---

## 三、行为变化（需要注意）

- **对比更慢但可信**：一项改动 = 一次解码。面板上会显示"正在渲染第 N / M 帧"。
- **不再需要重启播放器**：对比过程不碰播放实例，关掉面板就恢复原来的播放（且会自动继续播放原来在放的内容）。
- **"采用这份配置"仍然需要重启应用生效**（沿用原有语义：mpv 只在初始化时读配置）。
- 编辑框默认就是当前保存的配置，改一行就产生一项对比；没改动时按钮置灰并提示。

---

## 四、验证方式（本机无法编译）

这台机器上没有 Android SDK，JDK 也只有 1.8（工程要 AGP 9.4 / JDK 17+），所以**没有编译过**。请在 Android Studio 里：

1. `gradlew :app:assembleDebug`；
2. 播放一部片（mpv 引擎），打开控制条上的「对比」；
3. 编辑框末尾加一行 `scale=ewa_lanczossharp` → 开始对比，应出现"当前配置 / scale=…"两项，可滑动/闪烁/差异图；
4. 关掉面板：画面应立即恢复、音频继续；
5. 旋转到竖屏、退出播放器再进入：画面比例正常（这是原来的拉伸 bug）；
6. 点「采用这份配置」→ 重启应用后生效。

如果第 3 步仍然全部"渲染失败"，看它报的是哪一类：

- **"该配置无法初始化视频输出（vo=…）"** —— 该 `vo` 在本机跑不起来，属于对比结论，换一个选项继续测；
- **"渲染目标不可用（framebuffer 状态 0x…）" / "…GL 错误 0x…"** —— 该设备的 GL 驱动拒绝了 FBO 读回，把 logcat 里 `mpv bench:` 的几行发我；
- **"mpv 没有输出画面（vo-configured=…）"** —— 解码出画了但渲染目标没收到 buffer，说明这台设备的离屏 surface 需要换一种承载方式（届时把 logcat 里 `mpv bench: no frame` 那几行发我）。

已知的待确认点：`glReadPixels` 的行序（GL 原点在左下）在 `GlFrameReader.readPixels` 里按"镜像一次"处理。如果画面上下颠倒，说明这台设备的 producer 带了翻转，把那里 `(height - 1 - y)` 改成 `y` 即可（一行）。
