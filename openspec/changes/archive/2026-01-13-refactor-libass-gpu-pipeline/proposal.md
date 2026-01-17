# Change: 重构 libass GPU 字幕渲染管线（性能/架构/模块归属）

## Why
当前自定义 libass GPU 集成已经跑通端到端能力，但在“每帧开销、线程模型一致性、模块/包归属清晰度”方面仍存在较大改进空间：

- **性能热点**：渲染主路径存在可避免的 JNI 分配与对象创建（例如每帧创建 `jlongArray` 回传指标），容易在复杂 ASS 场景引入额外 GC 压力与抖动。
- **线程模型不够收敛**：渲染调用可能来自 Exo 回调或 `Choreographer`，导致 EGL Context 线程抖动、主线程负载上升、排查困难。
- **工程结构可读性与可维护性**：GPU 管线相关契约（API/DTO）与实现混杂在不直观的位置，包名与模块职责边界不够清晰，后续扩展（例如新增 overlay/telemetry UI）容易扩大耦合面。

本变更希望在不改变用户可见功能边界的前提下，对“影响性能与架构的关键点”做一次系统性重构。

## What Changes
- **渲染线程收敛**：将 GPU 渲染相关 JNI/GL 调用统一调度到单一渲染线程，避免主线程直接参与 GPU 合成与 EGL Context 线程抖动。
- **JNI 热路径降分配**：重构 `nativeRender` 的指标回传方式，避免每帧创建新的 Java 数组；Telemetry 关闭时走更轻量的快速路径。
- **生命周期与并发模型统一**：以 `LibassGpuSubtitleSession` 为边界集中管理 `CoroutineScope`/线程/资源释放，避免多个组件各自创建 scope 导致取消不一致与潜在泄漏。
- **模块与包归属对齐**：将 GPU 管线契约（`SubtitlePipelineApi` 及 request/response/command）迁移到契约层模块（建议 `core_contract_component`）并调整包结构；实现仍留在 `player_component`。
- **原生渲染热区梳理**：在 `ass_gpu_bridge.cpp` 中减少可避免的 GL 状态抖动与重复设置，保证在复杂 ASS 图元数量较多时仍具备稳定帧时间。

## Impact
- Affected specs: `subtitle-libass-gpu-pipeline`（新增能力规范，描述 GPU 管线的线程模型、性能约束与回退行为）。
- Affected code (implementation stage):
  - Kotlin：`player_component`（`LibassGpuSubtitleSession`、GPU 管线组件、EmbeddedSink、SurfaceOverlay 接入点）
  - Native：`player_component/src/main/cpp/ass_gpu_bridge.cpp`（JNI/metrics/GL hot path）
  - Contracts：`core_contract_component`（迁移/新增 subtitle pipeline 契约）

## Non-Goals
- 不改变 libass 渲染语义与字幕样式表现（仅重构管线与性能/工程结构）。
- 不扩大 GPU 管线适配范围（例如 mpv 内核仍不启用该管线）。
- 不在本变更中新增复杂渲染特性（例如图元合并/纹理 atlas 等高风险优化）。

## Open Questions
- Telemetry 默认开关与采样策略是否需要更细粒度（例如仅在开发者模式/调试会话开启）。
- 开发者设置“当前会话状态”的展示需求是否要同步补齐（若需要，展示入口与交互方案应在实现阶段确认）。

## References
- `document/release-notes/001-libass-gpu-render.md`
- 关键代码入口（现状）：
  - `player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt`
  - `player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt`
  - `player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/AssGpuRenderer.kt`
  - `player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/AssGpuNativeBridge.kt`
  - `player_component/src/main/cpp/ass_gpu_bridge.cpp`

