## Why

当前项目已经为 Media3 与 mpv（`vo=mediacodec_embed`）接入了统一的自定义 libass GPU 字幕渲染链路，但代码审查暴露出三个会直接影响稳定性与可用性的问题：GPU 渲染失败后会进入一个无法真正恢复或降级的死胡同状态、libass 模式下的字幕垂直偏移配置没有实际生效、mpv 的 ASS 全量字幕去重状态会在长时间播放时持续增长。随着 libass 已经成为默认字幕后端，这些问题已经不再是边缘细节，而是会影响日常播放体验与长时间会话稳定性的核心问题。

## What Changes

- 修复自定义 libass GPU 字幕管线的 fallback / recovery 语义，避免进入“逻辑上已 fallback、实际上没有可工作替代渲染器”的失效状态。
- 将字幕垂直偏移配置真正接入 libass 渲染链路，使 Media3 与 mpv 共享管线在 libass 模式下都能响应该设置。
- 为 mpv 的 `ass-full` 解析与去重状态增加边界控制与时间线重置策略，避免长时间播放导致状态无界增长。
- 统一相关遥测、日志与状态流转语义，使 GPU 渲染失败、surface 重建、seek/切轨后的恢复行为可观测且符合实际运行结果。
- 保持 `mediacodec_embed` 下“mpv 无法渲染内嵌位图字幕”的既有限制不变，本次仅修复自定义 libass 管线内应当可靠工作的行为。

## Capabilities

### New Capabilities
- `libass-subtitle-rendering`: 规定自定义 libass 字幕后端在 Media3 与 mpv 下的渲染恢复、偏移控制、嵌入式 ASS 数据接入与长时间播放稳定性要求。

### Modified Capabilities

## Impact

- **Affected code**: `player_component` 中的字幕后端、GPU 渲染桥接、Media3 SSA 解码接入、mpv 事件桥接与 ASS 解析器、播放器设置与字幕控制器。
- **Affected systems**: Media3 自定义文本渲染链、mpv `mediacodec_embed` 自绘字幕链、字幕遥测与 fallback 状态管理。
- **Dependencies**: 继续使用现有 `libass.so` / `libmpv.so` 与项目内 JNI 桥接，不新增外部运行时依赖。
