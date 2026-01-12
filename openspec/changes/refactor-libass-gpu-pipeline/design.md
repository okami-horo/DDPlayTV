## Context
当前 GPU libass 管线的主要调用链路如下：

- `DanDanVideoPlayer.configureSubtitleRenderer()`
  - `LibassRendererBackend.bind()`
    - `LibassGpuSubtitleSession.start()`
      - `SubtitleSurfaceOverlay`：创建 overlay Surface 并回调 surface lifecycle
      - `LibassEmbeddedSubtitleSink`：接入内嵌 ASS/SSA 样本
      - `SubtitleFrameDriver`（Exo 回调）或 `Choreographer`（兜底）驱动每帧 `renderFrame`
        - `AssGpuRenderer.renderFrame()`
          - `AssGpuNativeBridge.nativeRender(...)`
            - `player_component/src/main/cpp/ass_gpu_bridge.cpp`（EGL/GL + `ass_render_frame`）

现状已具备：surface 生命周期处理、fallback/recovery 结构、telemetry 采集与 load-shedding 策略。但在工程一致性与热路径开销方面仍可优化。

## Goals / Non-Goals
- Goals
  - 将 GPU 渲染调用收敛到稳定、可解释的线程模型（避免 UI 线程参与、避免 EGL Context 线程抖动）。
  - 去除渲染热路径中不必要的 JNI 分配与对象创建。
  - 明确“契约/实现/日志/数据模型”的模块归属，保证依赖治理与代码可读性。
  - 保持功能行为一致（用户可见功能不回退、不新增不可控复杂度）。

- Non-Goals
  - 不在本变更中引入高风险的渲染算法级优化（例如图元合并、纹理 atlas、shader 大改）。
  - 不扩展到非 Exo 内核（mpv 仍视为不支持 GPU subtitle pipeline）。

## Key Decisions
1. **单一渲染线程（Kotlin 调度）**
   - 以 session 生命周期创建/销毁单一渲染线程（例如 `HandlerThread`/单线程 executor）。
   - 所有 native bridge 调用（attach/detach/load/append/render/flush/release）均通过该线程串行执行。
   - `SubtitleFrameDriver` 与 `Choreographer` 仅负责产出“渲染请求”，不直接触发 JNI/GL。

2. **JNI 指标回传降分配**
   - 将 `nativeRender` 从“返回 `jlongArray`”改为“填充复用缓冲（例如传入 `LongArray(4)`）或按需查询 last metrics”。
   - Telemetry 关闭时允许 native 走快速路径：只返回是否绘制成功，避免不必要的时间测量与回传。

3. **Session 统一生命周期与 CoroutineScope**
   - `LibassGpuSubtitleSession` 作为唯一 owner 持有 `CoroutineScope`，并向内部组件注入，避免每个组件各自 `SupervisorJob()+Dispatchers.IO`。
   - 统一取消/释放顺序：先停驱动 → 停渲染线程队列 → 清理 embedded sink → detach overlay → release native。

4. **契约归属迁移到契约层模块**
   - 将 `SubtitlePipelineApi` 与相关 DTO 从实现模块迁移到 `core_contract_component`（或等价契约模块）。
   - 目标是让“契约定义”不依赖具体渲染实现，且保持依赖治理单向性；实现继续放在 `player_component`。

5. **原生 GL 热路径梳理（低风险）**
   - 减少循环内的重复 GL 状态设置：例如 `glUseProgram` 外提、纹理参数仅在新建纹理时设置、VBO 更新使用更轻量方式等。
   - 保持现有纹理池复用策略，避免引入纹理 atlas 等高风险改动。

## Proposed Structure (概念图)
```
LibassRendererBackend
  -> LibassGpuSubtitleSession (owns scope + render thread)
     -> FrameDriver (Exo/Choreo) -> RenderRequest (pts/vsync)
     -> SurfaceLifecycle -> SurfaceState -> RenderThread.enqueue(attach/detach)
     -> EmbeddedSink -> RenderThread.enqueue(append/flush)
     -> RenderThread -> AssGpuNativeBridge -> ass_gpu_bridge.cpp
     -> TelemetryCollector (optional, sampled/batched)
```

## Migration Plan
- Kotlin 侧保持 `LibassRendererBackend` 与 `SubtitleRenderer` 接口不变，内部替换实现与包结构。
- 对外暴露的设置项（字幕后端选择、透明度）行为保持一致。
- 原生接口变更通过同时更新 Kotlin JNI 声明与 C++ 实现完成，避免 ABI 不一致。

## Risks / Trade-offs
- **线程调度引入延迟**：渲染请求经队列调度可能引入微小延迟；通过单线程 + 合理的“最新帧覆盖/丢弃”策略抵消。
- **JNI 签名变更风险**：需要同步更新 Kotlin 与 C++，并确保 Proguard/R8 不影响；需要严格验证。
- **回退路径复杂度**：渲染线程化后，fallback/recovery 的状态切换要避免竞态；建议引入明确状态机或集中式串行处理。

