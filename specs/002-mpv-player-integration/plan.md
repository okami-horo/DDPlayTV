# Implementation Plan: 集成 mpv 播放引擎

**Branch**: `002-mpv-player-integration` | **Date**: 2025-12-12 | **Spec**: `specs/002-mpv-player-integration/spec.md`
**Input**: Feature specification from `/specs/002-mpv-player-integration/spec.md`

## Summary

在现有 DanDanPlayForAndroid 播放器架构中新增第三种播放内核 **mpv**。接入点对齐 VLC：mpv 负责音视频解码/渲染与字幕渲染（libass），应用侧弹幕覆盖层保持不变；用户可在设置中切换 mpv/现有内核，播放失败时提供清晰提示与一键回退。

## Technical Context

**Language/Version**: Kotlin 1.7.21（Android）+ Java 8 + NDK/JNI（现有项目已包含 native 组件）  
**Primary Dependencies**: AndroidX Media3/Exo（现有）、libVLC（现有）、应用内 GPU libass 字幕后端（现有，仅对 Media3 生效）、MMKV 配置（现有）、ARouter（现有）；新增 `libmpv`（Android `.so` 或 AAR 形式）  
**Storage**: 无新增存储；播放引擎选择/偏好继续落在 MMKV（`PlayerConfigTable`/`PlayerInitializer`）  
**Testing**: Gradle + JUnit4（单测）+ Instrumentation tests（现有 smoke tests），将补充 mpv 播放/seek/轨道/字幕相关烟测  
**Target Platform**: Android SDK 21–33；ABI 仅 `armeabi-v7a/arm64-v8a`  
**Project Type**: 多模块 Android App（MVVM），播放器位于 `player_component`  
**Performance Goals**: 首帧平均 <3s，常见设备 60fps 级流畅播放，首次播放成功率 ≥95%  
**Constraints**:  
- 许可证/发行合规必须先评估（mpv/libmpv 默认 GPLv2+，需决定是否采用 LGPL-only 构建或插件化发布）；Phase 1 仅保证核心播放 + 字幕正确渲染  
- mpv 渲染推荐 `mpv_render_context(OpenGL)`（`vo=gpu`/`gpu-next`，`gpu-context=android`），需保证弹幕覆盖层可叠加  
**Scale/Scope**: 仅新增 mpv 内核与专用渲染视图，少量枚举/配置/UI 分发调整，不改动业务模块边界

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`constitution.md` 当前为模板占位，无额外强制门槛；本特性不引入新的工程结构复杂度，**Gate: PASS**。

## Project Structure

### Documentation (this feature)

```text
specs/002-mpv-player-integration/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出
└── tasks.md             # Phase 2 输出（/speckit.tasks 生成）
```

### Source Code (repository root)

```text
app/                          # 设置 UI、入口分发
common_component/             # PlayerConfigTable 等配置
data_component/               # PlayerType 枚举
player_component/
├── src/main/java/com/xyoye/player/kernel/inter/          # AbstractVideoPlayer 等统一接口
├── src/main/java/com/xyoye/player/kernel/impl/media3/    # 现有 Media3 内核
├── src/main/java/com/xyoye/player/kernel/impl/vlc/       # 现有 VLC 内核
├── src/main/java/com/xyoye/player/kernel/impl/mpv/       # 新增 mpv 内核（Phase 1）
└── src/main/java/com/xyoye/player/surface/              # RenderMpvView + MpvViewFactory（Phase 1）
```

**Structure Decision**: 复用现有“内核抽象 + 工厂分发 + 渲染视图工厂”架构，在 `player_component` 内新增 mpv 实现；跨模块仅做枚举/配置/设置项的最小扩展。

## Complexity Tracking

无宪法门槛违规，无需填写。
