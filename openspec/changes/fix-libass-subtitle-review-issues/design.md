## Context

当前字幕后端已经统一收敛到自定义 libass GPU 管线：

- **Media3** 通过 `LibassAwareRenderersFactory` + `LibassSubtitleDecoderFactory` + `LibassSsaStreamDecoder` 把嵌入式 ASS/SSA 样本送入 `EmbeddedSubtitleSink`。
- **mpv** 在 `vo=mediacodec_embed` 下通过 `sub-text/ass-full`、`sub-ass-extradata`、`sid` 属性把 ASS 状态重新组装后送入同一条 GPU 管线。
- **共享渲染链** 为 `LibassRendererBackend -> LibassGpuSubtitleSession -> AssGpuRenderer -> AssGpuNativeBridge -> ass_gpu_bridge.cpp`。

问题在于，这条链路的“默认后端”地位已经与其真实恢复能力不匹配：逻辑中仍保留了“fallback 到 CPU”的状态语义，但应用层已经不再切到真实可工作的 CPU/legacy 渲染器；同时，libass 模式下的垂直偏移只保存在配置层，没有进入渲染层；mpv 的全量 ASS 去重缓存也缺少长期运行边界。

约束如下：

- 不恢复历史 CPU libass 路径，`player.subtitle.libass.LibassBridge` 已明确废弃。
- 不改变 `mediacodec_embed` 下 mpv 无法显示**内嵌位图字幕**这一现有限制。
- 修复需要同时覆盖 Media3 与 mpv 共享链路，避免只修一侧导致行为继续分叉。

## Goals / Non-Goals

**Goals:**

- 让自定义 libass GPU 管线在渲染失败、surface 重建、seek、切轨等场景下具备一致且可恢复的行为。
- 消除“进入 fallback 状态后没有真实替代渲染器可工作”的死锁式状态。
- 让 libass 模式下的垂直偏移配置真正作用于最终字幕绘制结果。
- 让 mpv 的 `ass-full` 解析状态在长时间播放中保持有界，不因持续去重记录导致无界增长。
- 保持遥测与日志可反映真实运行状态，避免状态名与实际行为长期背离。

**Non-Goals:**

- 不重新启用旧 CPU libass 渲染实现。
- 不为 mpv `mediacodec_embed` 增加内嵌位图字幕支持。
- 不重构整个字幕设置 UI，只修复与 libass 管线直接相关的配置生效链路。
- 不引入新的第三方字幕渲染库或播放器内核。

## Decisions

### 1. 以“GPU 降级并可恢复”替代“名义 CPU fallback”

**Decision**

保留“渲染失败后进入降级态”的概念，但不再依赖一个并不存在的 CPU 渲染落点。实现上以 **GPU suspended / degraded** 为目标语义：失败后暂停当前 GPU 输出、记录原因、阻止继续无效渲染；当 surface、轨道或时间线进入可安全重建的边界时，主动触发恢复流程重新绑定 GPU 管线。

**Why**

当前 `PlayerActivity.onSubtitleBackendFallback()` 只记录日志，不切换可工作的替代后端；而 `AssGpuRenderer` 在 `FALLBACK_CPU` 状态下会停止渲染，导致字幕长期消失。真正修复问题的关键不是“再打一层日志”，而是让状态机与现存能力一致。

**Implementation direction**

- 将应用层 `SubtitleFallbackDispatcher` 明确为“通知/上报接口”，不再承担切换后端职责。
- 在 `LibassGpuSubtitleSession` 内把恢复机制接成真实路径：
  - surface recreated / resized
  - seek
  - subtitle track changed / external track load / clear
  - timeline jump
- 把现有 `SubtitleRecoveryCoordinator` 从“已定义未接线”状态改为实际可调用的恢复编排器。
- 渲染失败后只允许在受控恢复点重建 native/libass/EGL 资源，避免每帧都盲目重试。

**Alternatives considered**

- **恢复 legacy/canvas 作为真实 fallback**：放弃。Media3 的 SSA/ASS 已走自定义旁路，mpv `mediacodec_embed` 也没有等价替代路径，强行切回会丢失 ASS 语义并造成两内核行为不一致。
- **保持现状，仅增强日志**：放弃。不能解决字幕变空的问题。

### 2. 垂直偏移在 libass 路径中下沉到 native 渲染坐标层

**Decision**

为 libass GPU 渲染增加专门的垂直偏移参数，并在 native 绘制坐标换算阶段应用，而不是继续只在 legacy view 层保留该设置。

**Why**

当前 libass 路径直接在独立 overlay surface 上用 OpenGL 绘制 ASS image，legacy `BaseSubtitleView` 的偏移逻辑不会参与；如果只移动外层 View，会带来裁剪、命中区域和尺寸同步问题。最稳定的做法是在 native 坐标计算中统一处理偏移。

**Implementation direction**

- 在 `SubtitleRenderer` / `LibassRendererBackend` / `LibassGpuSubtitleSession` 增加垂直偏移更新接口。
- 设置页更新垂直偏移时，同步通知当前 renderer，而不是只刷新 legacy controller。
- 在 `AssGpuNativeBridge` / `ass_gpu_bridge.cpp` 中新增可更新的垂直偏移参数。
- 偏移值以“配置百分比”为来源，在 native 侧基于当前输出高度换算为像素偏移并参与 `dst_y` 到顶点坐标的转换。
- 保持与 legacy 行为一致：正值整体上移，负值整体下移。

**Alternatives considered**

- **仅移动 overlay SurfaceView**：放弃。会把整张透明 surface 一起平移，存在裁剪与对齐风险。
- **在 Kotlin 层生成像素偏移并直接重布局**：放弃。对 surface 尺寸变化不够稳健，且仍需要 native/渲染侧配合。

### 3. mpv ASS 去重缓存采用“时间线重置 + 有界缓存”双策略

**Decision**

`MpvAssFullSubtitleParser` 的去重状态改为有界结构，并在时间线跳变时继续显式重置；同时结合样本结束时间或最近活动窗口做逐步清理，避免长时间单会话增长无上限。

**Why**

mpv 的 `sub-text/ass-full` 会重复给出当前可见 ASS 事件快照，需要去重才能避免重复注入 libass；但现有 `readOrderMap` 只增不减，长时间播放会不断累积历史事件。完全移除去重会造成重复事件重新入轨，完全不清理则会持续占用内存。

**Implementation direction**

- 用可裁剪的记录结构替代无限增长的 `HashMap<String, Int>`。
- 记录每个事件的结束时间或最近出现时间。
- 在每次解析后清理“已远离当前活动窗口”的记录，并设置上限保护，防止异常流持续推高占用。
- 保持 seek、sid 变化、显式 timeline reset 时的全量重置逻辑。

**Alternatives considered**

- **简单按固定数量 LRU 裁剪**：可作为兜底，但单独使用会把“仍可能重复出现的活动事件”与“已过去很久的历史事件”混在一起，语义较弱。
- **完全依赖 `sid` / seek 重置，不做长期清理**：放弃。无法覆盖超长连续播放。

### 4. 遥测与状态说明按真实能力校准

**Decision**

本次修复同步校准日志与遥测，明确“当前项目没有真实 CPU libass fallback”，避免继续让观测面误导实现判断。

**Why**

review 发现的问题之一正是状态命名与真实执行路径脱节。即便内部暂时保留兼容字段，也需要通过日志、设计说明与后续任务明确其真实含义，否则维护者会继续以为存在可切换的 CPU 渲染分支。

**Implementation direction**

- 将 UI/日志中的 fallback 处理文案从“ignored”调整为“degraded / recovery pending”语义。
- 保留必要的现有 telemetry 字段兼容性，但补充能说明恢复原因、恢复触发点与最终恢复结果的事件。
- 为恢复成功/失败增加成对观测点。

## Risks / Trade-offs

- **[风险] 恢复触发过于频繁导致重复重建 EGL/libass 资源** → **Mitigation**：只在 seek、切轨、surface 生命周期等离散边界触发恢复，并在会话内做防抖/状态判断。  
- **[风险] 垂直偏移与 legacy 方向语义不一致** → **Mitigation**：以 legacy `BaseSubtitleView` 的正负方向为基准，补充单元测试/截图验证。  
- **[风险] mpv parser 清理过激导致重复 ASS 事件重新注入** → **Mitigation**：使用“活动窗口 + 上限保护”的组合策略，并保留 timeline reset 测试覆盖。  
- **[风险] 遥测状态调整影响既有分析口径** → **Mitigation**：保留兼容字段，新增更准确的恢复事件，避免直接删除已有维度。  
- **[风险] Media3 与 mpv 恢复触发点不一致导致行为继续分叉** → **Mitigation**：将恢复入口收敛到共享 session / coordinator 层，内核侧只提供触发信号。  

## Migration Plan

1. 先在共享 libass session / renderer / native bridge 中引入可恢复状态与垂直偏移能力。
2. 再分别接通 Media3 与 mpv 的恢复触发点，确保两条输入链都能驱动同一恢复逻辑。
3. 最后调整 parser 清理策略、日志与测试。
4. 不涉及持久化数据迁移；若实现回滚，只需回退相关播放器与 native 改动即可。

## Open Questions

- 是否需要把现有 `SubtitlePipelineMode.FALLBACK_CPU` 在后续版本中正式重命名，以彻底消除语义歧义？本次先以行为修复优先，命名调整可作为后续兼容性整理项。
