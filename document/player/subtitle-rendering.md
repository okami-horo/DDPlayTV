# libass 字幕渲染维护说明

源码核对日期：2026-10-29。本文说明当前实现边界；旧变更的 11/11 勾选仅是历史任务记录，不作为本轮测试结果。字幕简繁转换属于[另一项待实现需求](subtitle-language-conversion.md)。

问题由来、修复状态和验证命令见 [libass 接入审查](libass-integration-audit.md)。本轮修复显示/轨道清理、错误回传、GLES 2 实现与重建问题，并通过模拟器 native 像素测试；MKV 附件字体、Media3 时间基数推断、目标 GLES 2 电视和复杂字幕矩阵仍未关闭。

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

## 显示、轨道和加载契约

- `SubtitleController.setUserSubtitleVisible()` 同时控制现有视图和 libass renderer。隐藏只提交透明帧，不删除轨道；恢复使用当前播放位置重绘。会话初始化继承控制器可见状态。
- 清除外挂先释放当前 native track 并清屏，再启用内嵌 sink、按内核能力重放格式/事件。内嵌 decoder 释放会清除其缓存格式；静音时不转发清轨，以免误删正在显示的外挂。sink 的释放结束当前轨道，后续格式可重新初始化。
- `loadExternalSubtitle()` 挂起等待渲染线程的 native Boolean 结果，不在任务入队时报告成功。加载取消/过期不再接管新请求；失败清除失败轨道并恢复内嵌输入，向用户报告文件加载错误。普通 SRT 等现有格式继续使用文字解析路径。
- native `flush` 提交透明帧并标记下一次必须重绘，不删除事件；`flushEmbeddedEvents` 才清空流式事件，`clearEmbeddedTrack` 则释放轨道。surface 重建也强制刷新相同时间点，避免 libass 的“图像未变化”判断留下空画面。
- surface 绑定带修订号，销毁/重新绑定后跳过旧队列任务；JNI 入口检查 Surface 有效性。销毁回调等待渲染线程解绑（最多 1.5 秒，超时记录警告），避免回调返回后继续使用旧窗口。尺寸变化只使用实际的 SurfaceHolder 回调，不在布局变化时提前或重复重建 EGL。
- 渲染状态和轨道命令按 FIFO 执行。多帧请求合并为一个待渲染任务，并移至已提交状态命令之后，避免暂停时重绘先于显示/seek/切轨操作。
- Media3 decoder 各自持有输入/输出缓冲池；sink 实例更换时重传 codec private，释放通知最后实际持有轨道的 sink。native 绘制时间保留 64 位毫秒。

## 降级与恢复

GL、GPU 或 overlay surface 故障后，应保留字幕会话上下文并暂停无效渲染。[AssGpuRenderer](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuRenderer.kt) 的失败标记和 Degraded 状态阻止继续提交帧；恢复由既有 [SubtitleRecoveryCoordinator](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitleRecoveryCoordinator.kt) 在受控边界发起，不应在每一帧重建 EGL／native／libass。

已接线的恢复边界包括：

- overlay surface 创建、重新绑定或尺寸变化；
- seek、字幕轨道变化和时间线跳变；
- 外挂字幕加载或清除。

恢复协调器要求管线处于降级状态、surface 与输出目标有效。attach 失败按无效 surface 或 GL 故障分类，不再一律归为不可恢复的 `UNSUPPORTED_GPU`。恢复尝试先标记 recovery pending，重新绑定和初始化成功后才回到 Active；正常 surface 绑定完成后也请求暂停重绘。取消旧初始化任务不报告初始化故障。

GLES 3 使用 GLSL 300 es 和 R8/RED 覆盖纹理；GLES 2 使用 attribute/varying shader、GL_ALPHA 纹理及紧密行上传。两者均要求 alpha 通道的 EGL 配置，以保留视频透出。输出检查 GL/swap 错误，不能将未提交的帧算作成功；目标 GLES 2 电视尚待实机验证。

[SubtitleFallbackDispatcher](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/SubtitleFallbackDispatcher.kt) 向页面通知故障，不切换渲染后端。[PlayerActivity](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 记录 degraded／recovery pending，并提示重新选择字幕或切换内核；[SubtitleFallbackReporter](../../core_log_component/src/main/java/com/xyoye/common_component/log/SubtitleFallbackReporter.kt) 的 `SUB-FALLBACK` 日志区分 `degraded`、`recovery_pending` 和 `recovery_result`，保留原因、surface、触发条件与结果。诊断入口见[日志系统](../monitoring/logging-system.md)。

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

本节列出维护时可用的测试入口；2026-10-29 的构建、全量 JVM 和 8 项字幕设备测试，以及旧审查的 23 项 JVM 记录见[验证记录](libass-integration-audit.md#已执行验证)。模拟器真实 native 像素测试已执行，物理电视和完整字幕矩阵仍待验收。

| 测试 | 实际覆盖范围 |
| --- | --- |
| [LocalSubtitlePipelineApiTest](../../player_component/src/test/java/com/xyoye/player/subtitle/gpu/LocalSubtitlePipelineApiTest.kt) | 降级原因保留、恢复中状态及 recoverable 标记 |
| [MpvAssFullSubtitleParserTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/mpv/MpvAssFullSubtitleParserTest.kt) | Dialogue 解析、时间线 reset 后重放、长播放裁剪和 512 项上限 |
| [LibassSsaStreamDecoderTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/LibassSsaStreamDecoderTest.kt) | 解码器初始化、时间基准与 Dialogue 归一化、codec private 向 sink 的格式传递 |
| [LibassGpuRenderingTest](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/LibassGpuRenderingTest.kt) | JNI/EGL/libass 实际像素：隐藏恢复、内外轨释放/切换、透明清屏、相同时间重绘、surface 重建、无效文件和大时间戳 |
| [Media3SubtitleCompatibilityTest](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3SubtitleCompatibilityTest.kt) | 内嵌 ASS 数据/seek、禁用后重启、普通 Cue 输出；此类自身不检查 native 像素 |

上述 JVM 用例不证明 EGL surface 恢复、native 坐标、跨内核画面或视觉裁剪正确。修改这些路径时，应在 Media3 和 mpv `mediacodec_embed` 上分别检查内嵌／外挂 ASS/SSA、播放中及暂停时的正负偏移、surface 重建、seek／换轨后的恢复，并记录实际日志和画面结果。

[MpvVideoPlayer](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt) 仍明确限制 `mediacodec_embed` 下的内嵌位图字幕显示；本轮整理与旧 libass 修复均未解决它，也不会自动切换视频输出。需要时由用户切换内核或在设置中选择 mpv `gpu`／`gpu-next`。
