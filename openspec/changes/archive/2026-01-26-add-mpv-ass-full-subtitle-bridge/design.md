## Context

- mpv 的 `vo=mediacodec_embed`（Android embed）属于“系统硬件 Surface 直出”的 VO：mpv 自身不会在该 VO 上合成 OSD/字幕，因此需要应用侧叠加渲染字幕。
- 项目已经实现 GPU libass + overlay 的字幕渲染管线：
  - `LibassGpuSubtitleSession` 负责创建透明 `SurfaceView` overlay 并驱动渲染。
  - `LibassEmbeddedSubtitleSink` 作为“内封字幕样本”输入接口，将事件喂给 `AssGpuRenderer`（JNI→libass）。
  - Media3 已通过自定义 decoder 将 Matroska ASS/SSA 样本转发给该 sink。
- 目前 mpv 内核 `MpvVideoPlayer` 虽实现 `SubtitleKernelBridge`，但 `canStartGpuSubtitlePipeline()` 固定返回 `false`，且 `mpv_bridge.cpp` 只监听了 `paused-for-cache`，没有字幕数据桥接。

本变更目标是在 **不修改 mpv/libmpv.so** 的前提下，补齐 mpv→GPU libass 的 embedded 字幕桥接，使 `vo=mediacodec_embed` 下内封字幕可见，并与现有字幕架构保持一致。

## Goals / Non-Goals

**Goals:**

1. mpv 内核在 `vo=mediacodec_embed` 时可通过 GPU libass overlay 渲染内封 ASS/SSA 字幕。
2. 复用现有 `EmbeddedSubtitleSink`/`AssGpuRenderer`/`LibassGpuSubtitleSession`，不引入新的跨模块依赖边，变更集中在 `player_component`。
3. 保证字幕偏移“提前/延迟”语义与 UI 一致（offset > 0 为提前），且 offset > 0 时不出现明显缺行/断档。
4. seek / 切换字幕轨道 / 关闭字幕 / 偏移变化等场景下，字幕状态可正确复位，不出现重复叠加或残留。

**Non-Goals:**

- 不支持图片字幕（PGS/VobSub 等）通过该桥接进入 GPU libass（libass 本身不适配该类字幕）。
- 不引入/恢复 CPU libass 渲染后端（项目已移除）。
- 不在本次变更中让 `vo=gpu/gpu-next` 也走 overlay（避免与 mpv 自带字幕/OSD 双重渲染）。
- 不修改 mpv-android/mpv 源码或重新编译 `libmpv.so`（保持“桥接层实现”）。

## Decisions

### 1) 字幕数据源：监听 mpv property（`sub-text/ass-full` + `sub-ass-extradata` + `sid`）

选择 mpv property 作为数据源的原因：

- `sub-text/ass-full` 返回 ASS `Dialogue:` 行（含 start/end/style/text），足以构造 libass streaming 事件。
- `sub-ass-extradata`（若可用）可提供 ASS codec private（样式/PlayRes），用于初始化 libass track。
- `sid` 可用于识别“关闭字幕/切轨”并及时清屏与复位。

实现方式：

- 在 `player_component/src/main/cpp/mpv_bridge.cpp` 的 `observeProperties()` 中对上述属性调用 `mpv_observe_property(...)`。
- 在 `MPV_EVENT_PROPERTY_CHANGE` 分支中，将属性变化派发为 Kotlin 侧 `MpvNativeBridge.Event` 的新增事件类型。

### 2) 事件格式：将 `Dialogue:` 转换为 libass streaming chunk（Matroska event fields）

GPU 管线 native 侧使用 `ass_process_chunk(track, data, size, timecode_ms, duration_ms)`，其中 `data` 需要 Matroska ASS/SSA 的 event fields（不含 `Dialogue:` 前缀）。

`sub-text/ass-full` 的典型输出（来自 mpv `sd_ass.c`）：

`Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text`

转换策略：

- 解析 `Start/End` 为毫秒 `timecodeMs` 与 `durationMs=end-start`。
- 将其余字段组装为 Matroska event fields：
  - `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`
- `ReadOrder` 采用“稳定去重 ID”：
  - 以 `Layer+Start+End+Style+Name+Margins+Effect+Text` 生成 eventKey；
  - 维护 `eventKey -> readOrder` 的映射，保证同一事件重复上报时 readOrder 不变；
  - 依赖 libass 默认的 readorder duplicate checking 消除重复事件。

### 3) 偏移语义与“提前不缺字幕”：用 mpv `sub-delay` 做事件前置（lookahead）

关键约束：

- 现有 GPU 渲染路径在渲染时使用 `videoPtsMs + SubtitlePreferenceUpdater.currentOffset()`（offset > 0 为提前）。
- 但 `sub-text/ass-full` 默认只反映“当前显示”的字幕，不会主动提供未来事件；若仅依赖该 property 上报，offset > 0 时可能出现“未来事件尚未投递→字幕缺失”。

决策：

- mpv 内核在 `setSubtitleOffset(offsetMs)` 时，将 offset 语义映射为 mpv 的 `sub-delay`：
  - mpv `sub-delay` 的正负与 UI 语义相反，因此使用 `sub-delay = (-offsetMs)/1000.0` 秒。
- 这样在 offset > 0（提前）时，mpv 会更早认为“未来字幕已到显示区间”，从而提前让 `sub-text/ass-full` 进入新事件（等价 lookahead），保证事件能提前投递到 GPU track。
- GPU 渲染仍保持现有 `videoPtsMs + offset` 的时间线定义，不改动跨内核的渲染逻辑。

### 4) 启用条件：仅 `vo=mediacodec_embed` 返回可用

为避免 mpv 自带字幕/OSD 与 overlay 重复渲染：

- `MpvVideoPlayer.canStartGpuSubtitlePipeline()` 仅当 `PlayerConfig.getMpvVideoOutput()` 解析为 `mediacodec_embed` 时返回 `true`。

### 5) 复位策略：seek / 切轨 / 关字幕 / 偏移变化触发 flush/reset

为了避免重复事件与残留显示：

- seek：在 `MpvVideoPlayer.seekTo()` 中对 embedded sink 执行 `onFlush()`，并清空 `eventKey->readOrder` 映射。
- 切轨/关字幕：监听 `sid` 变化，发生变更后 flush/reset，并等待新的 `sub-ass-extradata`/`sub-text/ass-full` 进入。
- 偏移变化：在 `setSubtitleOffset()` 设置 mpv `sub-delay` 后立即 flush/reset，避免旧 offset 下投递的事件污染新时间线。

### 6) 线程模型：解析与投递在后台线程，渲染仍在既有渲染线程

- mpv native event loop 通过 JNI 回调进入 Kotlin（当前 `MpvNativeBridge` 会将事件切到主线程派发）。
- 为降低主线程负担，mpv subtitle bridge 采用独立 `HandlerThread`：
  - 主线程仅做事件转发（或轻量去抖）；
  - 解析 `Dialogue:`、去重、调用 `EmbeddedSubtitleSink.onFormat/onSample/onFlush` 在后台线程执行；
  - `AssGpuRenderer` 的 JNI/GL 调用仍由其内部渲染线程串行执行（满足既有 pipeline 的线程约束）。

## Risks / Trade-offs

- **[Risk]** mpv property 事件在不同媒体/字幕类型下不稳定（extradata 缺失、ass-full 格式差异）  
  **Mitigation**：解析器容错；extradata 缺失时允许退化；错误仅记录并回退到“无 embedded 字幕”而非崩溃。

- **[Risk]** `sub-text/ass-full` 重复上报导致重复事件叠加  
  **Mitigation**：稳定 readOrder + 事件去重映射；在 seek/切轨/偏移变化时 reset 映射。

- **[Risk]** offset>0 提前场景事件尚未投递导致断档  
  **Mitigation**：使用 mpv `sub-delay` 的反向映射作为 lookahead，使 mpv 提前进入未来字幕区间，驱动 property 上报未来事件。

- **[Risk]** flush/reset 过于频繁导致短暂空窗  
  **Mitigation**：仅在“时间轴跳变”（seek/切轨/偏移变化）触发；常规播放仅做增量投递。

## Migration Plan

- 无数据迁移。
- 行为变更仅在 mpv 且 `vo=mediacodec_embed` 生效；其它内核/vo 不受影响。
- 回滚策略：可通过恢复 `canStartGpuSubtitlePipeline=false` 或关闭 `mediacodec_embed` 配置回到现状。

## Open Questions

1. mpv 内核下“外挂字幕”的产品语义：用户期望外挂 ASS/SSA 走“GPU overlay 直读文件”（现有能力），还是走 mpv 的 `sub-add`（内核管理）？两者同时启用可能导致重复渲染，需要明确优先级。
2. 是否需要把该桥接扩展到 `vo=gpu/gpu-next` 的“强制 overlay 模式”（通过禁用 mpv 自带字幕/OSD 实现）？本提案默认不做，以避免破坏既有行为。
3. 需要支持的字幕类型边界：仅 ASS/SSA（含 mpv 转换后的 ASS 输出）是否足够？若需要覆盖更多类型，可能需要引入额外的 subtitle source（如抽取/解析原始流）。

