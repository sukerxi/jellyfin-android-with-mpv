# 易用性评审：jellyfin-android-with-mpv

- 评审时间：2026-10-26
- 评审对象：Compose UI 层（播放器 / 连接 / 设置 / 下载 / 更新 / mpv 配置编辑器）与错误处理链路
- 方法：静态阅读关键界面与状态流，按「交互流程 → 错误提示 → 可访问性 → 操作直观性」四类归纳；不涉及完整实现，只给方向与验收标准
- 说明：所有结论均可按 `文件:行号` 复核；优先级 P0 = 阻塞主路径或造成数据/上下文丢失，P1 = 明显摩擦，P2 = 打磨

---

## 0. 结论速览

| 优先级 | 问题 | 位置 | 用户感受到的后果 |
| --- | --- | --- | --- |
| P0 | 播放器底栏固定 10 个 42dp 图标，竖屏横向溢出 | `PlayerControls.kt:349-482` | 窄屏上「选集/全屏」等入口被压成 0 宽，直接消失且点不到 |
| P0 | 播放失败只有 Toast，随后立即关闭播放器 | `PlayerScreen.kt:381-392`、`434-444` | 一句话 2 秒后消失，无原因、无重试，人被踢回网页 |
| P0 | 网页主框架任何错误都清空服务器会话并退回选服页 | `JellyfinWebViewClient.kt:98,117`、`JellyfinNavHost.kt:138-143` | 一次瞬时断网或证书错误 = 丢失整个浏览现场，只能重连 |
| P1 | 控件 2.5s 自动隐藏，返回键直接退出播放器 | `Constants.kt:146`、`JellyfinNavHost.kt:199-213` | 来不及找按钮；误触返回就退出，且没有二次确认 |
| P1 | 当前倍速/清晰度/解码器状态在界面上不可见 | `PlayerControls.kt:402-442` | 必须逐个打开菜单才知道自己设了什么 |
| P1 | 移动网络确认弹窗「点外部」= 取消播放 | `PlayerScreen.kt:1176-1180` | 误触弹窗外区域，播放直接被中止 |
| P1 | 8 个可交互图标没有 `contentDescription`，锁屏按钮标签语义反了 | `PlayerControls.kt:169` 等 | TalkBack 只能读「按钮」；解锁键被读成「锁定屏幕」 |
| P2 | 选集面板加载态是空白、单元格 44dp | `EpisodePickerPanel.kt:177-199,213` | 看着像坏了；点按偏小 |
| P2 | 设置页无分组跳转/搜索，选择型条目无箭头提示 | `SettingsScreen.kt:606-621` | 长列表里找不到项；不知道哪行可点 |

---

## 1. 播放器：交互流程与操作直观性

### 1.1 底部操作栏溢出的确定性问题（P0）

`PlayerControls.kt:349-482` 的 `BottomBar` 是一个不可滚动的 `Row`，从左到右依次放：锁屏、音轨、字幕、倍速、清晰度、解码器、信息、对比、选集、全屏。

- 每个 `PlayerIconButton` 默认 `42.dp`（`PlayerControls.kt:493-516`），10 个即 420dp，加左右 8dp padding ≈ 436dp。
- 竖屏可用宽度常见为 360dp，`Modifier.size()` 会把固定尺寸收敛到剩余约束，超出部分被压成 0 宽 —— 表现不是「挤在一起」，而是**最后 1~2 个按钮彻底消失且无法点击**；开启「对比入口」（`mpvCompareEnabled`）或队列 >1 时更早触发。
- 同一屏的中央 5 键（`PlayerControls.kt:185-224`）在 320dp 宽设备上也会贴边：42+42+66+42+42 加 4×24dp 间距 ≈ 330dp。

**优化方向**

1. 底栏改为「常驻 4~5 个高频项 + 一个『更多』面板」：锁屏、音轨、字幕、倍速常驻，其余（清晰度/解码器/信息/对比/选集）收进底部抽屉。
2. 或使用 `LazyRow` + 两端渐隐，但需明确「还有内容」的可滚动暗示，且不能把关键操作藏到屏幕外。
3. 中央区把「上一集/下一集」与「上一章/下一章」分离：章节导航移到进度条上方的分段控件，避免 5 个圆形按钮里 4 个长得像。
4. 验收：320dp 宽 + 200% 字体缩放 + 刘海屏三种条件下，所有入口均可见可点。

### 1.2 状态不可见（P1）

倍速、清晰度、解码器切换后，图标本身不变、无徽标、无短暂提示（`PlayerControls.kt:402-442`）。用户下次想确认「我现在是不是 1.5 倍速」只能把菜单重新打开一遍。

**方向**：非默认值时在图标角上挂文字徽标（`1.5×` / `720p` / `SW`），或在底栏上方显示一行 12sp 的状态摘要（`1.5× · 720p · 硬件解码 · 简体字幕`）。状态摘要同时能缓解「菜单一关就失忆」的问题。

### 1.3 加载/缓冲没有解释（P1）

`PlayerScreen.kt:186-195` 只有一个 64dp 的转圈，没有文案、没有「已等待 N 秒」、没有超时兜底，也没有取消入口。移动网络下首次起播可能要十几秒，用户无法区分「在缓冲」和「卡死了」。

**方向**：超过 3s 追加「正在缓冲…」文案，超过 10s 追加「网络较慢」并提供「降低清晰度重试 / 退出」。服务端转码起播慢时，明确写「服务器正在转码」。转圈本身也可以带进度（缓冲百分比），比纯 indeterminate 更可信。

### 1.4 返回语义与误退出（P1）

- 系统返回：面板打开 → 关面板；否则**直接关闭播放器**（`JellyfinNavHost.kt:199-213`、`PlayerScreen.kt:830-841`）。
- 顶部返回箭头同样直接关闭（`PlayerControls.kt:166-173`）。
- 控件 2.5 秒后自动隐藏（`Constants.kt:146`），隐藏状态下按返回仍然退出。

这三点叠加的后果：想「收起控件看画面」的用户按一下返回，播放就结束了；而播放中误触的概率恰恰最高。

**方向**：返回分级为「关面板 → 收控件 → 退出（首次退出给可撤销的 Snackbar，如『已退出播放 · 继续观看』）」；或退出前用一次轻量确认（仅在转码/直播/未保存进度时）。自动隐藏时长建议 4~5s，并在**任何触摸交互后重置计时**，TalkBack / 开关控制开启时禁用自动隐藏（见 §8）。

### 1.5 手势与锁屏（P1/P2）

- 长按 2 倍速（`PlayerGestureHandler.kt:162-166`）没有任何屏上反馈：没有「2×」角标，也没有触感反馈，用户不确定是否生效。方向：长按期间在顶部显示 `2× 快进中`，配合 `HapticFeedbackType.LongPress`。
- 双击 seek 只有涟漪和图标，没有「+10s」文字（`GestureOverlay.kt:327-402`）。方向：在涟漪中心叠加 `+10s / -10s`，与横向滑动指示器（`GestureOverlay.kt:235-278`）保持一致的语言。
- 锁屏后控件消失、解锁按钮 2.5s 后也消失（`PlayerScreen.kt:286-301`），期间没有任何提示，用户容易以为应用卡死。方向：锁定时用一次性 Toast/短提示「已锁定，点击屏幕显示解锁按钮」。
- 解锁按钮的 `contentDescription` 用的是 `playback_lock_screen`（`PlayerScreen.kt:986-994`），语义反了。
- 亮度/音量/横向 seek 只能靠手势，没有可发现的替代路径（见 §8）。

### 1.6 「全屏」按钮语义不一致（P2）

`PlayerScreen.kt:1085-1099`：横屏视频在竖屏时点「全屏」实际是**旋转设备方向**；竖屏视频则切换沉浸式。同一个图标两种行为，用户预期是「画面铺满」。

**方向**：把「旋转」和「铺满」拆成两个动作，或按视频宽高比自动决定并在按钮上加方向提示（↻）。

### 1.7 信息面板：术语外泄（P1）

`PlaybackInfoPanel.kt:190` 直接 `mediaSource.playMethod.toString()`，界面会显示 `DIRECTPLAY` / `TRANSCODE` 这类枚举原文，而旁边其他行都是本地化中文。转码原因未知时也会回落到 `reason.serialName`（同文件 209 行）。

**方向**：所有枚举值走资源映射（直接播放 / 直接串流 / 转码）；每个技术行提供一句「这意味着什么」的副标题或问号气泡；面板底部加「复制诊断信息」按钮（现在只能长按选中，`PlaybackInfoPanel.kt:486-500` 的 `SelectionContainer` 反而让整块文本都参与选择，长按容易选到相邻行）。

### 1.8 选集面板（P2）

- 元数据未加载时只渲染一个空 `Spacer`（`EpisodePickerPanel.kt:177-180`），面板看起来是空的/坏的。方向：骨架格或「正在加载剧集…」占位。
- 单元格高 44dp、`clickable` 无 `role`/`stateDescription`（`EpisodePickerPanel.kt:213-231`），触控与无障碍双低。方向：≥48dp，补 `Role.Button` + 选中状态描述。
- 当前集只靠底色区分，且已看集是「降低透明度」（0.5 alpha），与「不可用」的视觉语言冲突。方向：当前集加左侧强调条或「播放中」角标；已看集改用对勾图标而非透明。

### 1.9 移动网络确认弹窗（P1）

`PlayerScreen.kt:1174-1220`：

- `onDismissRequest` 直接取消并 `closePlayer()` —— 点弹窗外或按返回 = 放弃播放，属于破坏性默认。
- 三个按钮平铺在 `dismissButton` 的 `Row` 里，「继续原画质 / 取消」与外层「低码率播放」形成三层视觉权重，窄屏还可能换行挤压。
- 文案里只有码率数字，没有「大约消耗多少流量/分钟」这类决策依据。

**方向**：弹窗外点击/返回只关闭弹窗并保留播放器（回到未开始状态，可再次触发），取消做成显式按钮；主按钮「低码率播放（480p · 约 xx MB/分钟）」，次按钮「原画质」，「取消播放」用文字按钮下沉。理想情况再给「本次会话不再提示」。

---

## 2. 连接与网页宿主：错误处理链路

### 2.1 一次错误 = 丢整场会话（P0）

`JellyfinWebViewClient.kt` 对**主框架**的 HTTP 错误、网络错误、以及凭据解析失败都调用 `onErrorReceived()`（98、117、77 行），上层随即 `mainViewModel.resetServer()`（`JellyfinNavHost.kt:138-143`），WebView 被销毁，回到选服页。

后果：地铁里掉一次网、服务端短暂 502、甚至证书过期，都会让用户「退出登录现场」——SPA 当前页面、滚动位置、播放队列全部丢失，而界面只给一句「无法建立连接」。

**方向**

1. 区分错误类型：可重试（超时/5xx/断网）→ 停留在当前页，显示内嵌错误卡片 + 「重试」；不可恢复（证书不受信任、401）→ 才回选服页，且文案要指名原因。
2. 证书错误（`JellyfinWebViewClient.kt:120-123` 直接 `handler.cancel()`）必须单独提示「服务器证书无效」，否则用户会去反复检查地址和网络。
3. 保留「重试」的最长等待与自动重连（指数退避）——网络恢复后无需人工介入。

### 2.2 选服页的错误与空状态（P1）

- `ServerSelection.kt:252-268` 的错误文案出现了两次：输入框 `isError` + 支持文本，再加下方 `AnimatedErrorText`，同一句话重复两遍。
- 错误信息是单条兜底文案（`connection_error_cannot_connect`：「请检查主机名和网络连接」），不区分 DNS 失败 / 端口不通 / 协议不匹配 / 401。
- 自动发现页（`ServerSelection.kt:290-295`）的进度圈**永远在转**：即使已经发现了服务器、甚至没有任何结果，它都不会停；空列表也没有任何占位。
- 提交时「连接」和「选择服务器」两个按钮整体消失、换成转圈（`ServerSelection.kt:190-206`），布局跳动且无法取消。

**方向**：错误信息分层（地址格式 / 无法解析 / 拒绝连接 / 需要认证 / 协议不兼容），并给「改用 http」「检查端口」等针对性建议；发现页改为「扫描中（剩余 Ns）→ 已找到 N 台 / 未找到，可手动输入」，扫描结束停转；提交时按钮保留但置 loading 态，并给「取消」。

### 2.3 网页加载浮层（P2）

`WebViewHost.kt:294-313` 只有转圈 + 「使用其他服务器」，10 秒超时后（`Constants.kt:17`）直接踢回选服页。缺少「重试」和「服务器状态」信息。

**方向**：加载超过 3s 显示目标地址与「正在连接 <host>…」；超时后原地给「重试 / 换服务器」两个动作，而不是自动跳走。

---

## 3. 设置页

### 3.1 可点性不可见（P1）

`SingleSelectPreferenceRow`（`SettingsScreen.kt:606-621`）与 `ClickPreferenceRow` 都没有尾部的箭头/当前值强调，`PreferenceRow` 只是一行文字（`SettingsScreen.kt:526-562`）。用户无法区分「说明文字」和「可进入的条目」。

**方向**：选择型条目右侧加当前值 + `›`；开关型条目整行可点（已实现）+ `Role.Switch` 语义（见 §8）；分组标题下加一句分组说明。

### 3.2 禁用项没有解释（P1）

`SettingsScreen.kt:479-484`：「发布通道」被渲染成 `enabled = false` 且 `onClick = {}`，用户看到一行灰掉的文字，既不知道为什么不能改，也不知道怎么改。

**方向**：改成只读信息行（不带可点暗示），摘要里写清「通道由安装包决定（GitHub / CNB）」；或做成可切换并在切换时提示「下次检查更新生效」。

### 3.3 检查更新是纯 Toast 流程（P2）

`SettingsScreen.kt:134-146`：点击 → Toast「正在检查」→ 结果再来一个 Toast。无 loading 态、无防重复点击、失败没有原因也没有重试。

**方向**：行内显示 spinner + 「正在检查…」，完成后原地展开结果（版本号、更新时间、失败原因 + 重试），与更新弹窗共用一套呈现。

### 3.4 设置项缺少搜索与「最近修改」（P2）

整页是一条 `verticalScroll`（`SettingsScreen.kt:164-168`），现在已有 4 个分组、20+ 条目，且随播放器类型动态增减。方向：顶部加搜索框（按标题/摘要过滤），并在顶部提供「播放内核」这类高频项的快捷入口。

---

## 4. 下载页

### 4.1 图标按钮全部没有标签（P1，无障碍）

`DownloadsScreen.kt:141-158`（关闭/返回）、`169-181`（删除/更多）四处 `contentDescription = null`。TalkBack 下是四个「按钮」。

### 4.2 多选只能靠长按（P2）

`DownloadsList.kt:96-107` 用 `combinedClickable` 的 `onLongClick` 进入多选，顶栏在多选态才出现删除/更多。首次使用者几乎不可能发现。

**方向**：顶栏在非多选态就放「选择」入口；或列表项右侧常驻 `⋮`，长按作为进阶快捷方式。

### 4.3 点击语义会「静默重下」（P1）

`DownloadsList.kt:96-107`：未通过校验的条目，单击直接触发 `onDownload()`，没有任何提示。用户以为要打开，结果开始重新下载。

**方向**：单击先给状态说明（「文件不完整，是否重新下载？」），确认后再下载；或把「重新下载」作为条目内的显式动作。

### 4.4 进度不可读、不可控（P1）

- 下载中/排队中只显示 indeterminate 进度条（`DownloadsList.kt:148-150`），没有百分比、速度、剩余时间。
- 没有单条「暂停/取消」。
- `download_incomplete` 用 `Color.Yellow`（`DownloadsList.kt:156-162`），浅色主题下在 surface 上对比不足（约 1.6:1），几乎读不清。

**方向**：接实体里的进度字段显示百分比 + 速度；条目内提供暂停/取消；状态色改用 `MaterialTheme.colorScheme.error`/`tertiary` 并配图标，不只靠颜色。

---

## 5. 更新流程

### 5.1 下载中不可退出、不可后台（P1）

`UpdateDialogs.kt:204-247` 的进度对话框 `onDismissRequest = {}`，唯一的出口是「取消下载」。用户在设置里点了「立即下载」后，必须守着这个模态框直到完成，无法先去做别的事。

**方向**：进度对话框提供「后台继续」（转为通知 + 设置页入口），只在「下载完成待安装」时才强制交互。

### 5.2 权限与下载交叠，缺少回归路径（P1）

`UpdateDialogs.kt:54-60`：点「立即下载」时如果还没有安装权限，会**同时**弹出权限引导并立刻开始下载。用户从系统设置返回后，可能错过「下载完成 → 触发安装」的时机，也没有「现在安装」的恢复入口。

**方向**：权限 → 下载 → 安装串成一条有状态的小流程：权限未授予时先下载但不自动安装，回来后检测权限并给「立即安装」按钮；或在下载完成的对话框里重新检查权限并引导。

### 5.3 「忽略此版本」是永久动作（P2）

`UpdateDialogs.kt:151-165` 把「忽略此版本」和「后台下载」并排放在 `dismissButton` 的 `Row` 里，层级混乱，且忽略后无撤销（`update_ignore`）。

**方向**：主按钮「立即下载」，次按钮「稍后」，第三项收进溢出菜单并说明「不再提示此版本（可在设置恢复）」；同时保留「本次不再提示」。

### 5.4 失败只有 Toast（P2）

`UpdateDialogs.kt:84-88`：失败就关掉进度框弹一句「下载失败」。而通知渠道里其实已经写了「点击重试」（`update_download_failed_notification_text`）。方向：对话框内失败态 + 原因 + 重试。

---

## 6. mpv 配置编辑器

`MpvConfigEditorScreen.kt`

- **重置无确认**（109-111 行）：`config = ""` 立即清空编辑区，配合「保存」就是不可逆覆盖。
- **无未保存提示**：返回箭头（70-76 行）直接 `onBack()`，一屏辛苦改的配置全丢。
- **校验只在保存时以 Toast 出现**（47-57 行）：提示了行号，但编辑器不会滚动到该行也不会高亮。
- **没有生效说明**：README 明确写着「保存后需重启应用生效」，编辑器里没提，用户会以为没生效。
- **没有语法辅助**：无高亮、无补全、无示例/片段插入；`mpv.conf` 的键名很难记。

**方向**：脏数据返回拦截（「放弃修改？」）；重置二次确认或提供撤销；校验改为行内错误标记 + 自动滚动；保存后明确提示「已保存，重启应用后生效」并提供「立即重启」；加一个「常用选项」抽屉，点击插入模板行。

---

## 7. 可访问性专项

| 类别 | 现状 | 位置 | 建议 |
| --- | --- | --- | --- |
| 图标无标签 | 8 个可交互控件 `contentDescription = null`（播放器返回、设置返回、编辑器返回、发现页返回、下载页关闭/返回/删除/更多） | `PlayerControls.kt:169`、`SettingsScreen.kt:157`、`MpvConfigEditorScreen.kt:74`、`ServerSelection.kt:282`、`DownloadsScreen.kt:146,156,172,180` | 全部补标签；装饰性图片（logo、缩略图、手势图标）保持 null 是对的 |
| 标签语义错误 | 解锁按钮描述为「Lock screen」 | `PlayerScreen.kt:986-994` | 改为「解锁」并加 `stateDescription` |
| 触控目标 | 42dp 图标、44dp 选集格、约 34dp 的「跳过」文字按钮 | `PlayerControls.kt:499`、`EpisodePickerPanel.kt:215`、`PlayerScreen.kt:962-969` | 统一 ≥48dp（可用 `minimumInteractiveComponentSize` 或扩大 padding，视觉尺寸可不变） |
| 自定义可点击元素无角色 | 选集格、「跳过」纯文本 `clickable`、设置行 | 同上 | 补 `Modifier.semantics { role = Role.Button }`，选择型补 `selected` 状态 |
| 自动隐藏 | 2.5s 无条件隐藏，TalkBack/开关控制下同样生效 | `Constants.kt:146`、`PlayerScreen.kt:579-584` | 检测无障碍服务开启时禁用或延长到 10s；任何交互重置计时 |
| 字号缩放 | 底栏固定 42dp/44dp 容器 + 固定 sp 文本，200% 字体下会裁切 | `PlayerControls.kt`、`EpisodePickerPanel.kt` | 容器用 `heightIn(min=)` 而非固定 `height`，文本用主题 typography |
| 对比度 | 控件层遮罩仅 37.5% 黑，纯白图标压在亮画面上可能低于 3:1 | `PlayerControls.kt:54` | 改为上下渐变压暗（顶部/底部 70~80%），中间保持通透 |
| 手势独占 | 亮度/音量/横向 seek 只有手势路径，无按钮替代 | `PlayerGestureHandler.kt` | 音量交给系统音量键（已可用），亮度在播放设置面板里加滑杆 |
| 状态播报 | 缓冲、锁定、倍速变化均无 `LiveRegion` 播报 | `PlayerScreen.kt` | 关键状态变化用 `semantics { liveRegion = LiveRegionMode.Polite }` |

---

## 8. 错误提示体系（跨页面，建议统一）

现在全应用有约 40 处 `Toast`（`PlayerScreen`、`ExternalPlayer`、`UpdateManager`、`SettingsScreen`、`MpvConfigEditorScreen`、`InPlayerCompareOverlay`），而后台只有一个 `SnackbarHost`，且只挂在 WebView 宿主里（`WebViewHost.kt:315-320`）。由此产生的问题：

1. **不可回溯**：Toast 2 秒消失，用户来不及读；无法复制，报障时说不清。
2. **不可操作**：无法承载「重试 / 切换内核 / 查看详情」这类动作。
3. **不可分层**：网络失败、内容不支持、内部异常都压缩成一句话（`strings.xml:70-72`），既不指出原因，也不给下一步。
4. **不可访问**：Toast 不进入可聚焦的无障碍树，无法被重新读取。

**建议的最小统一方案**

- 引入全局 `SnackbarHost`（放在根 `Box` 顶部，与 `AppDialogHost` 同级），Toast 逐步迁移过去。
- 错误按三级处理：
  - 轻量、瞬时（切换成功、已保存）→ Snackbar，2~4s，无动作。
  - 需要动作（播放失败、下载失败、配置非法）→ Snackbar + action（重试 / 查看详情），或页面内错误卡片。
  - 阻塞主路径（无法连接、证书无效）→ 全屏状态页，含原因、建议、重试。
- 错误对象统一携带 `{ 用户可读标题, 技术细节(可复制), 重试动作? }`，避免每条路径自己拼文案。
- 播放失败至少要能「留在播放器里重试 / 换内核重试 / 降低清晰度重试」，而不是关闭浮层。

现成正面样例：对比面板对失败的处理（`InPlayerCompareOverlay.kt:178-195`）会显示具体原因、并在「应用会导致黑屏」时二次确认 —— 这个模式值得复用到播放失败、更新失败、配置非法三处。

---

## 9. 建议的落地顺序

**第一批（1~2 天，收益/成本最高）**

1. 底栏溢出：常驻 5 项 + 「更多」抽屉。
2. 8 处 `contentDescription` 补齐 + 解锁标签修正。
3. 控件自动隐藏 2500ms → 4000ms，交互重置，无障碍服务开启时禁用。
4. 「跳过」/选集格触控目标 ≥48dp。
5. 移动网络弹窗：点外部不再取消播放。
6. 信息面板 `playMethod` 枚举本地化。

**第二批（1 周，改结构）**

7. 全局 Snackbar + 错误三级模型；播放失败改为「原地重试」不再关闭浮层。
8. 网页主框架错误分级：可重试 / 不可恢复，证书错误单独提示。
9. 更新下载支持后台 + 完成后再处理权限。
10. 选服页发现列表的终止态与空状态；错误文案去重与分层。

**第三批（持续）**

11. 状态徽标（倍速/清晰度/解码器/字幕）与手势反馈（2× 角标、±10s 文案）。
12. mpv 编辑器的脏数据拦截、行内校验、重启提示。
13. 设置页搜索 / 当前值 / 箭头；下载条目进度与暂停。
14. 字体缩放与对比度的系统性回归。

**可验证的验收项**

- Accessbility Scanner / TalkBack 扫描：无标签的可交互控件 = 0。
- 320dp 宽、200% 字体、横竖屏：播放器全部入口可见可点。
- 断网 3s 后恢复：不需要重新选服务器即可继续。
- 播放失败：错误文案含原因，可在播放器内重试，退出前进度不丢。
- 下载/更新：任一进度界面可退到后台且能从设置页回到进度。

---

## 10. 第一批落地记录（2026-10-26）

第一批 6 项已实现并通过 `:app:compileLibreDebugKotlin` 编译验证（无 cts/UI 测试覆盖，行为需真机回归）。

| # | 改动 | 主要文件 |
| --- | --- | --- |
| 1 | 底栏改为等分权重布局：常驻音轨 / 字幕 / 倍速 / 选集（有队列时）/ 全屏 / 更多，其余收进「更多」菜单；入口数最多 6 个，320dp 竖屏仍有 ≥52dp 槽宽 | `PlayerControls.kt`、`PlayerMenus.kt`、新增 `ic_more_vert_white_24dp.xml` |
| 2 | 「更多」菜单把当前值写进标题（清晰度 · 自动、解码器 · 硬件解码），部分缓解「状态不可见」 | `PlayerMenus.kt:226-295` |
| 3 | 8 处可交互图标补 `contentDescription`；解锁按钮改为「解锁屏幕」 | `PlayerControls.kt`、`SettingsScreen.kt`、`MpvConfigEditorScreen.kt`、`ServerSelection.kt`、`DownloadsScreen.kt` |
| 4 | 控件自动隐藏 2500 → 4000ms，任意触摸重置计时；读屏/开关控制开启时不自动隐藏（控件栏与解锁入口都不隐藏） | `Constants.kt:146`、`PlayerControls.kt:162-177`、`PlayerScreen.kt:244-254,296-303,587-596` |
| 5 | 「跳过」与选集格触控目标 48dp；补 `Role.Button` 与选中语义 | `PlayerScreen.kt:971-990`、`EpisodePickerPanel.kt:215-223` |
| 6 | 移动网络确认弹窗：点外部/返回不再取消播放，只能走显式选项 | `PlayerScreen.kt:1196-1200` |
| 7 | 播放信息面板的播放方式改为本地化文案（直接播放 / 直接串流 / 转码） | `PlaybackInfoPanel.kt:194-204` |

未纳入第一批、仍在待办：错误提示体系（全局 Snackbar + 错误分级）、网页主框架错误分级、更新下载后台化、选服页发现态、设置页可点暗示、mpv 编辑器脏数据拦截。

---

## 第一批验证结果（模拟器实测）

**环境**：AVD `Medium_Phone_2`（API 30 / x86_64，1080×2400 @420dpi），Jellyfin `chaos.lan:8096`（账号 `zhou`）。为规避模拟器掉帧，交互测试把分辨率降到 540×1200 @210dpi（同 dp 尺寸），另用 420×1200 模拟 320dp 窄屏；测毕 `wm size reset` / `wm density reset` 复原。

| # | 验证项 | 实测结果 |
| --- | --- | --- |
| 1 | 底栏不再溢出 | 411dp 宽下 5 个入口各 106×63px ≈ **81×48dp**；320dp 宽下 5 个入口（音频流 / 播放速度 / 选集 / 全屏 / 更多）全部在屏内且高度 ≥48dp；无字幕轨、队列为 1 时只出现 4 个 |
| 2 | 「更多」显示当前值 | 展开后为「清晰度 · 自动」「解码器 · 硬件解码」「播放信息」「锁定屏幕」，文本未被截断 |
| 3 | 无障碍标签 | 无障碍树中可读到 Back / Previous / Previous chapter / Pause / Next chapter / Next / Audio streams / Playback speed / Episodes / Fullscreen / More / Unlock screen |
| 4 | 控件不早于 4s 消失 | 约 3.0s 时控件仍在，4.8s 时已隐藏（与 4000ms 一致）；任意触摸会重置计时 |
| 5 | 选中语义 / 触控目标 | 选集面板格子 ≥48dp，选中项带 selected 语义；跳过按钮可稳定点中 |
| 6 | 移动网络弹窗不可被外部点击取消 | 点弹窗外部、按返回键，画面不变、播放不被取消，只有显式选项生效 |
| 7 | 播放方式本地化 | 播放信息面板显示 **Play method: Direct play**（不再是 `DIRECTPLAY`） |
| 补充 | 返回键语义 | 播放器内返回箭头与系统返回键都关闭播放器并回到网页详情页，行为一致 |
| 补充 | 设置页可点暗示 | Video player type / Playback buffer size / Mobile data playback quality / Customize subtitle style / Allowed Network Types / Download location / MPV custom configuration 均出现右侧 `>` |
| 补充 | mpv 编辑器 | 第 2 行写非法值后输入框自身标红并提示「Invalid syntax on line 2…」；返回键与返回箭头都弹「Discard changes?（Keep editing / Discard）」；点 Reset 弹「Clear the configuration?（Cancel / Reset）」 |

验证中排除的两个假阴性：控件 4s 后自动隐藏导致"点了没反应"；模拟器偶发不再向应用派发输入（仅通知栏响应），用 HOME 键重进 Activity 即恢复。

---

## 第二批落地记录

| # | 改动 | 主要文件 |
| --- | --- | --- |
| 1 | 全局 Snackbar 通道（`SnackbarController` + `AppSnackbarHost` 挂在根布局），设置页 / mpv 保存 / 更新下载不再用 Toast | 新增 `ui/components/AppSnackbar.kt`、`app/AppModule.kt`、`MainActivity.kt` |
| 2 | 播放失败改为**可重试的错误卡片**（标题 / 原因 / 重试 / 返回），不再是"Toast 一句就关页面" | 新增 `ui/screens/player/PlayerErrorCard.kt`、`PlayerScreen.kt` |
| 3 | 播放器运行期错误改走 Snackbar（长时长、可手动关闭），不再一闪而过 | `PlayerScreen.kt` |
| 4 | 网页主框架加载失败分级：`WebLoadFailure{NETWORK/HTTP/SSL/TIMEOUT}`，只有可重试的（网络 / 超时 / 5xx）给「Retry」，证书类直接说明不可重试；Retry 就地重载而非踢回选服页 | `webapp/JellyfinWebViewClient.kt`、`ui/screens/web/WebViewHost.kt` |
| 5 | 选服页发现态：扫描中显示「Searching for servers…」+ 转圈，扫描结束后变成可点的刷新按钮；空结果给出原因与「Search again」，不再"永远转圈 + 空白" | `ui/screens/connect/ServerSelection.kt` |
| 6 | mpv 编辑器脏数据拦截：返回键/返回箭头统一拦截，重置需二次确认，非法行原地标红，保存成功走 Snackbar 并说明重启生效 | `ui/screens/settings/MpvConfigEditorScreen.kt` |
| 7 | 设置页可点行补右侧 `>`；更新流程把"下载完成→安装权限→返回应用继续安装"串成一条链，下载进度弹窗可关闭 | `ui/screens/settings/SettingsScreen.kt`、`ui/components/UpdateDialogs.kt`、`update/UpdateDialogStateHolder.kt` |
| 8 | 断流不再静默退出（验证中新发现，见下） | `player/mpv/MpvPlayer.kt`、`player/PlayerViewModel.kt`、`strings.xml` |

**第二批实测**：
- 断网后重启应用 → 网页区显示「Cannot reach the server / The page could not be loaded. / net::ERR_INTERNET_DISCONNECTED」，下方 **Retry** 与 **Use a different server**；恢复网络点 Retry **就地回到首页**（不再跳选服页）。
- 点「Use a different server」→ 选服页保留原地址；进「Choose server」：扫描中为「Searching for servers…」+ 转圈，结束后为空态「No servers found yet」+ 说明 + **Search again**，顶栏刷新图标带 `Search again` 无障碍标签。
- mpv 编辑器保存后弹出底部 Snackbar「Saved. Restart the app to apply the changes.」并带关闭 X，且能跨页面存活；返回键在键盘弹出时只收键盘、不误触"放弃修改"。
- 设置页 `>` 指示与第一批结论一致复现。

**验证中发现并修复的问题（第二批补充项 #8）**：播放中途断网时，mpv 重连 1s→3s→7s 后放弃（日志 `http: Stream ends prematurely` → `event: end-file` → `event: idle`），播放器**静默关闭退回详情页**，用户完全看不到原因。

定位过程：最初的假设是 `END_FILE` 的分支判错了，加上日志后发现 `reportPlaybackStop` 比 mpv 的 `end-file` **早 57ms** —— 真正提前触发的是 `eof-reached`。mpv 在"流被截断"时同样会把 `eof-reached` 置真，旧代码据此判为"播完"（`STATE_ENDED`），播放页于是走正常的结束流程关闭。

修复：
- `MpvPlayer` 的 `eof-reached` 与 `END_FILE` 两处判定都加上位置校验 `isEndOfFileNearEnd()`：结束位置距片尾 >5s 视为**被打断**，不再当成播完（时长未知的直播/HLS 仍按原来的"已到结尾"处理，避免误报）；
- 被打断时上报 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`；
- `PlayerViewModel.onPlayerError` 对网络类错误**先提示再回退**（回退本身也要走网络，若等它超时失败才提示，用户面对的只是一个停住的黑屏），文案为新增的「播放中断：与服务器的连接已断开」。

实测（1080×2400 模拟器，播放中 `svc wifi disable` + `svc data disable`）：约 30s 后底部弹出 **"Playback interrupted: lost the connection to the server"**（带关闭 X），播放器**停在原位置不再退出**，控件仍可操作。恢复网络后不会自动续播，需手动点播放或拖动进度。

遗留可做的下一步：给这条提示加一个「重试」动作（或直接复用第二批的 `PlayerErrorCard`），断流后可一键续播。

---

## 附：证据索引（快速复核）

| 主题 | 位置 |
| --- | --- |
| 底栏 10 图标 / 无滚动 | `ui/screens/player/PlayerControls.kt:349-482` |
| 图标 42dp / 触摸目标 | `ui/screens/player/PlayerControls.kt:493-516` |
| 中央 5 键 | `ui/screens/player/PlayerControls.kt:185-224` |
| 返回键直接退出播放器 | `ui/navigation/JellyfinNavHost.kt:199-213`、`ui/screens/player/PlayerScreen.kt:830-841` |
| 控件 2.5s 自动隐藏 | `utils/Constants.kt:146`、`ui/screens/player/PlayerScreen.kt:579-584` |
| 播放错误 Toast + 退出 | `ui/screens/player/PlayerScreen.kt:381-392`、`434-444` |
| 移动网络弹窗可被外部点击取消 | `ui/screens/player/PlayerScreen.kt:1174-1220` |
| 跳过按钮 / 解锁按钮 | `ui/screens/player/PlayerScreen.kt:948-996` |
| 全屏即旋转 | `ui/screens/player/PlayerScreen.kt:1085-1099` |
| 信息面板枚举外泄 | `ui/screens/player/PlaybackInfoPanel.kt:190`、`209` |
| 选集面板空加载态 | `ui/screens/player/EpisodePickerPanel.kt:177-231` |
| 长按倍速无反馈 | `ui/screens/player/PlayerGestureHandler.kt:162-166` |
| 网页错误 → 重置服务器 | `webapp/JellyfinWebViewClient.kt:98,117,120-123`、`ui/navigation/JellyfinNavHost.kt:138-143` |
| 选服页错误重复 / 常转圈 | `ui/screens/connect/ServerSelection.kt:190-206,252-295` |
| 设置行无可点暗示 / 禁用通道 | `ui/screens/settings/SettingsScreen.kt:479-484,526-562,606-621` |
| 下载页图标无标签 | `ui/screens/downloads/DownloadsScreen.kt:141-181` |
| 下载条目点击/进度 | `ui/screens/downloads/DownloadsList.kt:96-163` |
| 更新弹窗阻塞 / 权限交叠 | `ui/components/UpdateDialogs.kt:50-89,204-247` |
| mpv 编辑器重置/校验 | `ui/screens/settings/MpvConfigEditorScreen.kt:47-63,109-111` |
