# Phase 0 研究结论：mpv 播放引擎集成

本节基于 `TODOs/mpv.md` 的最佳实践与现有工程结构梳理，用于锁定 Phase 1 的可落地技术路线。

## 关键决策

### 决策 1：mpv 作为第三种播放内核接入（对齐 VLC 行为）

- **Decision**：在 `player_component` 新增 `MpvVideoPlayer : AbstractVideoPlayer` 与 `MpvPlayerFactory`，由 `PlayerFactory` 依据 `PlayerType` 分发；不替换现有 Media3/VLC。
- **Rationale**：现有架构已通过 `AbstractVideoPlayer + PlayerFactory` 支持多内核；VLC 作为“自渲染字幕的第三方内核”已有成熟接入模式，mpv 复用该模式风险最低。
- **Alternatives considered**：  
  - 直接替换 Media3 为 mpv：会影响大量业务与字幕/弹幕链路，回归风险高。  
  - 在 Media3 内部嵌入 mpv 解码：工程复杂度与维护成本过高。

### 决策 2：Phase 1 字幕策略采用 mpv/libass 内核渲染

- **Decision**：mpv 选中时保持内核字幕默认开启，内嵌与外挂字幕均由 mpv 渲染；外挂字幕通过 `addTrack(TrackType.SUBTITLE)` → `sub-add`/等价命令交给 mpv 加载；同时禁用应用侧 `SubtitleRendererBackend` 初始化，避免双字幕。
- **Rationale**：mpv 自带 libass 在格式兼容性、时间轴与渲染稳定性上优于应用侧当前链路；且与 VLC 体验一致，满足“字幕正确显示”的首阶段目标。
- **Alternatives considered**：  
  - 继续使用应用侧字幕渲染：需要打通 mpv 的文本/ASS 输出回调，且可能与 mpv 内核字幕冲突。  
  - 仅用应用侧渲染外挂字幕：会导致内嵌字幕/轨道切换行为不一致。

### 决策 3：渲染层使用 mpv_render_context(OpenGL) + 专用 RenderMpvView

- **Decision**：在 `player_component/.../surface/` 新增 `RenderMpvView : InterSurfaceView`（内部基于 `GLSurfaceView`/`GLTextureView`），并在 `SurfaceFactory.getFactory()` 中按 `playerType == TYPE_MPV_PLAYER` 返回 `MpvViewFactory()`，忽略 `surfaceType`。
- **Rationale**：mpv Android 官方推荐 `libmpv + mpv_render_context(OpenGL)`（`vo=gpu`/`gpu-next`）；使用 Texture/GLTextureView 更易与弹幕覆盖层共存，避免 SurfaceView 的 Z-order/透明问题。
- **Alternatives considered**：  
  - 纯 SurfaceView：可能导致弹幕无法覆盖或需要复杂的 Z-order 处理。  
  - mpv 自带 Activity/Surface 管理：不符合现有渲染抽象，难以复用 UI 控制层。

### 决策 4：控制/轨道/事件语义对齐现有 PlayerConstant

- **Decision**：mpv event loop（`mpv_wait_event`）在后台线程运行，将事件映射到 `VideoPlayerEventListener` 的 `onPrepared/onInfo/onCompletion/onError/onVideoSizeChange`；轨道能力用 `track-list/aid/sid/vid` 实现 `getTracks()/selectTrack()/deselectTrack()`，外挂音轨用 `audio-add` 映射 `addTrack()`。
- **Rationale**：播放器 UI/业务依赖统一的事件与轨道接口；语义对齐可最大化复用现有控制层与播放列表逻辑。
- **Alternatives considered**：  
  - 直接暴露 mpv 原生事件给 UI：会造成 UI 侧大量分支与不可控状态差异。  
  - 只实现最小控制、不实现轨道：与 FR-005/FR-003 不符。

### 决策 5：网络播放保持现有 Header/鉴权策略

- **Decision**：在 `setDataSource(path, headers)` 中将 headers 转换为 mpv 的 `http-header-fields`（或等价属性/命令）后再 `loadfile`。
- **Rationale**：现有网络源依赖 Referer/鉴权 Header；必须在 mpv 中复现，否则会造成“原有可播资源不可播”。
- **Alternatives considered**：  
  - 由上层自行拼接 URL：不可覆盖全部鉴权场景且侵入业务层。  
  - 忽略 headers：与 FR-006 直接冲突。

### 决策 6：打包与许可证的工程侧取向

- **Decision**：Phase 1 工程以引入 armv7/arm64 的 `libmpv` 为前提（放入 `player_component/libs/**` 或 AAR 依赖），同时将“许可证/发行合规评估通过”作为发布前硬门槛；优先选择 LGPL-only 构建或可选插件化方案。
- **Rationale**：mpv/ffmpeg 可能引入 GPL 传染风险，必须在产品/法务确认后才能随主包发布；但工程实现可先完成接入与可切换能力。
- **Alternatives considered**：  
  - 直接使用 GPL 版本随主包发布：存在发行合规风险。  
  - 完全不引入 mpv：无法满足兼容性提升诉求。

