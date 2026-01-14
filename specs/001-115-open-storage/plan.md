# Implementation Plan: 115 Open 存储库在线播放

**Branch**: `[001-115-open-storage]` | **Date**: 2026-01-14 | **Spec**: `specs/001-115-open-storage/spec.md`  
**Input**: Feature specification from `specs/001-115-open-storage/spec.md`

**Note**: 本模板由 `/speckit.plan` 生成与维护；如需校验前置条件，可参考 `.specify/scripts/bash/check-prerequisites.sh`。

## Summary

在应用现有“存储源”体系中新增“115 Open”类型：用户通过在新增/编辑页手动输入 `access_token` 与 `refresh_token` 完成连接（不引导账号密码登录）。连接成功后可从 115 根目录开始浏览目录/文件，并对受支持的视频文件一键进入播放器开始播放；刷新/排序/搜索等定位能力与“百度网盘存储源”保持一致。播放链路使用 `downurl` 获取直链，并统一注入 `User-Agent`；对 mpv/VLC 场景复用现有 `LocalProxy/HttpPlayServer` 以降低 115 高频随机 Range 触发风控导致 403 的风险，保证多播放内核一致可播。授权态以 `refresh_token` 持久化为主，按 115-sdk-go 语义在鉴权失效时自动刷新 `access_token` 并重试；失败则提示用户更新 token。

## Technical Context

**Language/Version**: Kotlin 1.9.25（JVM target 1.8），Android Gradle Plugin 8.7.2  
**Primary Dependencies**: AndroidX、Kotlin Coroutines、Retrofit+OkHttp、Moshi、Room、MMKV、Media3、NanoHTTPD（本地代理）、ARouter  
**Storage**: Room（`media_library` 等表）+ MMKV（偏好/授权态隔离存储）+ 本地缓存文件（字幕/弹幕/临时清单等）  
**Testing**: `./gradlew testDebugUnitTest`（JVM 单测）、`./gradlew connectedDebugAndroidTest`（设备/模拟器）；主要使用 JUnit4/AndroidX Test  
**Target Platform**: Android（手机 + TV；需考虑遥控器/焦点）  
**Project Type**: Android 多模块（MVVM）；`:storage_component` 提供 UI，`:core_storage_component` 提供存储实现  
**Performance Goals**: 对齐 `spec.md` 成功指标：添加存储源到可浏览 ≤1min（SC-001）；打开已添加存储源首屏列表 ≤3s（SC-002）；点击视频到开始播放成功率 ≥90%（SC-003）  
**Constraints**: 遵守模块依赖治理（禁止 feature ↔ feature 依赖）；鉴权方式仅为“用户手动填 token + 自动刷新”（不做账号密码/Cookie）；token 在 UI/日志中必须脱敏（FR-012/FR-016）；proapi 的鉴权失败以 JSON `state=false + code` 表达（需按 115-sdk-go 语义自动刷新并重试一次）；播放直链需要 `User-Agent` 且可能触发 115 风控（建议对 mpv/VLC 默认走 `HttpPlayServer`）  
**Scale/Scope**: MVP 聚焦“浏览 + 播放 + 搜索”；支持多账号（多个存储源）；目录可能很大需分页/懒加载；不实现上传/删除/移动等文件管理能力

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- 模块职责与归属清晰；避免把不相关能力硬拼到同一模块
- 不新增 feature ↔ feature 的 Gradle 依赖（跨业务交互走 `:core_contract_component` 契约/路由/Service）
- 新增/调整共享类型优先下沉到 `:core_contract_component` 或 `:data_component`
- 不引入“为了拿 transitive 类型”的依赖；默认使用 `implementation(project(...))`
- 若引入 `api(project(...))`，必须给出对外 API 暴露理由，并评估影响面
- 若涉及依赖变更：`./gradlew verifyModuleDependencies`（建议 `./gradlew verifyArchitectureGovernance`）通过，且输出末尾为 `BUILD SUCCESSFUL`
- 若更新依赖治理规则/白名单：同步更新 `document/architecture/module_dependency_governance.md` + `buildSrc` + 快照（如需要）

## Project Structure

### Documentation (this feature)

```text
specs/001-115-open-storage/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── 115-open-openapi.md  # 已整理的 115 Open API 资料（实现参考）
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
app/
storage_component/
core_contract_component/
core_log_component/
core_network_component/
core_storage_component/
core_ui_component/
data_component/
buildSrc/

# 代码位置示例：
# - <module>/src/main/java/...
# - <module>/src/main/res/...
```

**结构说明（必须）**：

- 涉及模块（预计改动点）：
  - `:storage_component`：新增“115 Open”存储源的新增/编辑 UI（token 输入、脱敏展示、测试连接、更新/移除）；入口与百度网盘保持一致
  - `:core_storage_component`：新增 `Open115Storage` 与 `Open115StorageFile`；新增 115 Open Repository + token 刷新管理（按 storageKey 隔离、串行刷新、原子持久化）；复用 `LocalProxy/HttpPlayServer`
  - `:core_network_component`：新增 115 Open Retrofit Service（proapi/passportapi 的接口声明，不承载业务逻辑）
  - `:data_component`：新增 `MediaType.OPEN_115_STORAGE`（名称待定）、图标资源；新增 115 Open API 模型（data class）
  - `:core_contract_component`：仅当需要新增跨模块契约类型/接口时使用（优先保持现有 Storage 契约不变）
- 依赖关系：不新增 feature ↔ feature Gradle 依赖；仅在既有依赖链条内扩展（`storage_component -> core_storage_component -> core_network_component/data_component`）。

## Complexity Tracking

无（当前设计不引入宪章约束的破例项）。
