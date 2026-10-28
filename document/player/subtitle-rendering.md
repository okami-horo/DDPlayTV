# libass 字幕渲染维护说明

源码核对日期：2026-10-28。本文整合 `fix-libass-subtitle-review-issues` 的有效决策与 delta spec，说明当前实现边界；旧变更的 11/11 勾选仅是历史任务记录，不作为本轮测试结果。字幕简繁转换属于[另一项待实现需求](subtitle-language-conversion.md)。

## 渲染职责

选择 LIBASS 后端并满足内核条件时，Media3 内嵌 ASS/SSA、mpv `mediacodec_embed` 的 ASS 桥接以及外挂 ASS/SSA 共用以下渲染链：

```text
LibassRendererBackend
  -> LibassGpuSubtitleSession
  -> AssGpuRenderer
  -> AssGpuNativeBridge
  -> ass_gpu_bridge.cpp
```

[LibassRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt) 负责后端入口；[LibassGpuSubtitleSession](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) 管理独立 overlay、渲染线程、帧驱动和内嵌字幕 sink。外挂 ASS/SSA 直接交给 GPU renderer，加载期间关闭内嵌 sink，清除外挂后重新启用内嵌输入。普通文字／位图字幕仍有原控制器路径，不能据此声称它们都经过 libass。

当前没有真正接管自定义 libass 渲染的 CPU fallback。`SubtitlePipelineMode.FALLBACK_CPU` 是保留的状态字段名；它在这里表示 GPU 管线降级，不能解释为已有 CPU 渲染器在输出字幕。后续更名需核对状态消费者和兼容性，不是本次新增功能承诺。

## 降级与恢复

GL、GPU 或 overlay surface 故障后，应保留字幕会话上下文并暂停无效渲染。[AssGpuRenderer](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuRenderer.kt) 的失败标记和 Degraded 状态阻止继续提交帧；恢复由既有 [SubtitleRecoveryCoordinator](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitleRecoveryCoordinator.kt) 在受控边界发起，不应在每一帧重建 EGL／native／libass。

已接线的恢复边界包括：

- overlay surface 创建、重新绑定或尺寸变化；
- seek、字幕轨道变化和时间线跳变；
- 外挂字幕加载或清除。

恢复协调器要求管线处于降级状态、surface 与输出目标有效；`UNSUPPORTED_GPU` 被标记为不可恢复，不走该协调器的自动重试。恢复尝试先标记 recovery pending，重新绑定和初始化成功后才回到 Active；尝试失败仍需保留真实故障状态，不能保证任意设备故障均可恢复。

[SubtitleFallbackDispatcher](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/SubtitleFallbackDispatcher.kt) 是向页面通知故障的接口，不执行切换渲染后端。[PlayerActivity](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 当前只记录 degraded／recovery pending；[SubtitleFallbackReporter](../../core_log_component/src/main/java/com/xyoye/common_component/log/SubtitleFallbackReporter.kt) 的 `SUB-FALLBACK` 日志区分 `degraded`、`recovery_pending` 和 `recovery_result`，保留原因、surface、触发条件与结果。诊断入口见[日志系统](../monitoring/logging-system.md)。

## 运行时垂直偏移

会话启动时应用当前字幕垂直偏移，后续设置更新沿 `updateVerticalOffset()` 传到 [AssGpuNativeBridge](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuNativeBridge.kt) 和 [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp)。native 在图像坐标合成阶段按当前输出高度计算：

```text
offsetPx = outputHeight * offsetPercent / 100
adjustedY = subtitleImageY - offsetPx
```

因此正值上移、负值下移，方向与原字幕视图保持一致。native 将百分比限制在 -100～100；修改设置影响后续帧，暂停时会话请求一次重绘，无需重新开始播放。该路径同时覆盖上述内嵌和外挂 ASS/SSA，不应改用平移整个 overlay View，否则会改变 surface 裁剪边界。

## mpv 内嵌 ASS 去重

[MpvEmbeddedSubtitleBridge](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvEmbeddedSubtitleBridge.kt) 将 mpv `ass-full` 快照拆成带时间的事件注入共享 sink。[MpvAssFullSubtitleParser](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvAssFullSubtitleParser.kt) 抑制重复活动事件，并通过访问顺序淘汰、历史窗口和容量上限限制状态：

- 去重表最多保留 512 项；不能恢复为只增不减的 HashMap。
- 超过 60 秒历史窗口且连续超过 2 个快照未出现的条目可被裁剪；容量不足时淘汰最旧条目。
- seek、字幕轨道改变等时间线重置时清空去重状态并 flush 内嵌事件；重新绑定 sink 时可回放缓存快照。

这些限制约束的是 parser 去重表，不能据此断言整个 native 字幕会话或播放进程的内存均恒定。

## 验证与已知限制

本轮只核对源码和现存测试，没有运行 GPU／真机字幕验收。可继续使用的自动化入口：

| 测试 | 实际覆盖范围 |
| --- | --- |
| [LocalSubtitlePipelineApiTest](../../player_component/src/test/java/com/xyoye/player/subtitle/gpu/LocalSubtitlePipelineApiTest.kt) | 降级原因保留、恢复中状态及 recoverable 标记 |
| [MpvAssFullSubtitleParserTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/mpv/MpvAssFullSubtitleParserTest.kt) | Dialogue 解析、时间线 reset 后重放、长播放裁剪和 512 项上限 |
| [LibassSsaStreamDecoderTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/LibassSsaStreamDecoderTest.kt) | 解码器初始化、时间基准与 Dialogue 归一化、codec private 向 sink 的格式传递 |

上述 JVM 用例不证明 EGL surface 恢复、native 坐标、跨内核画面或视觉裁剪正确。修改这些路径时，应在 Media3 和 mpv `mediacodec_embed` 上分别检查内嵌／外挂 ASS/SSA、播放中及暂停时的正负偏移、surface 重建、seek／换轨后的恢复，并记录实际日志和画面结果。

[MpvVideoPlayer](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt) 仍明确限制 `mediacodec_embed` 下的内嵌位图字幕显示；本轮整理与旧 libass 修复均未解决它，也不会自动切换视频输出。需要时由用户切换内核或在设置中选择 mpv `gpu`／`gpu-next`。
