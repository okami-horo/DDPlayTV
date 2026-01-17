# subtitle-libass-gpu-pipeline Specification

## Purpose
TBD - created by archiving change refactor-libass-gpu-pipeline. Update Purpose after archive.
## Requirements
### Requirement: GPU libass 渲染线程模型收敛
系统 SHALL 将 GPU libass 渲染相关的 JNI/GL 调用集中在单一渲染线程中执行，以避免 UI 线程阻塞与 EGL Context 线程抖动。

#### Scenario: 播放中渲染不阻塞 UI
- **WHEN** 用户使用 ExoPlayer 且字幕渲染后端为 `LIBASS` 播放包含 ASS/SSA 字幕的视频
- **THEN** GPU 渲染（含 JNI/GL）不在主线程直接执行
- **AND** UI 线程保持可交互（暂停/拖动/返回等操作可及时响应）

#### Scenario: 暂停/Seek 的一次性刷新也保持线程一致
- **WHEN** 播放暂停或发生 seek/discontinuity 需要触发一次性字幕刷新
- **THEN** 系统仍通过同一渲染线程完成渲染与合成（不切换到其他线程直调 JNI/GL）

### Requirement: JNI 渲染指标回传不产生每帧分配
系统 SHALL 在每帧渲染路径中避免创建新的 Java 数组/对象用于回传渲染指标，并支持 Telemetry 关闭时走更轻量的快速路径。

#### Scenario: Telemetry 关闭时走快速路径
- **WHEN** 当前会话关闭 Telemetry
- **THEN** 每帧渲染仅返回“是否绘制成功”等必要信息
- **AND** 不创建用于回传指标的新数组（例如不再返回新的 `jlongArray`）

### Requirement: 契约与实现的模块归属清晰
系统 SHALL 将 subtitle pipeline 契约（`SubtitlePipelineApi` 及其 request/response/command）定义在契约层模块中（建议 `core_contract_component`），并将实现保留在 `player_component`，以满足依赖治理的单向性。

#### Scenario: 依赖治理校验通过
- **WHEN** 在本地或 CI 执行 `./gradlew verifyModuleDependencies`
- **THEN** 构建输出 `BUILD SUCCESSFUL`
- **AND** 不出现与 subtitle pipeline 契约/实现归属相关的违规依赖边

### Requirement: GPU 管线失败可回退且可恢复
系统 SHALL 在 GPU 管线初始化失败、surface 丢失或渲染不可用时触发回退，并在条件恢复后允许尝试恢复 GPU 管线。

#### Scenario: 初始化失败触发回退提示
- **WHEN** GPU 管线在 attachSurface/init 阶段失败
- **THEN** 系统触发可观测的回退事件（含失败原因）
- **AND** 用户可一键切回传统字幕后端（例如 `LEGACY_CANVAS`）

#### Scenario: Surface 恢复后可尝试恢复 GPU
- **WHEN** GPU 管线因 surface 丢失回退后，surface 重新可用
- **THEN** 系统允许在不干扰播放的前提下尝试恢复 GPU 管线

