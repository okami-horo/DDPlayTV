# Implementation Plan: 百度网盘存储库在线播放

**Branch**: `[001-baidu-pan-storage]` | **Date**: 2026-01-13 | **Spec**: `/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/spec.md`  
**Input**: Feature specification from `/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/spec.md`

**Note**: 本模板由 `/speckit.plan` 生成与维护；如需校验前置条件，可参考 `.specify/scripts/bash/check-prerequisites.sh`。

## Summary

在应用现有“存储源”体系中新增“百度网盘”类型：通过百度开放平台 OAuth 设备码模式（二维码扫码确认）完成授权后，可从网盘根目录（`/`）开始浏览目录/文件，并对受支持的视频文件一键进入播放器开始播放。播放链路优先走 `filemetas -> dlink` 直链 + Range，并对 mpv/VLC 场景复用现有 `LocalProxy/HttpPlayServer` 注入必要 headers（如 `User-Agent: pan.baidu.com`）以保证多播放内核一致可播。授权态以 `refresh_token` 持久化为主、自动刷新 `access_token`；严格处理 refresh_token 单次可用与并发刷新问题，失败则引导重新扫码授权。

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: Kotlin 1.9.25（JVM target 1.8），Android Gradle Plugin 8.7.2  
**Primary Dependencies**: AndroidX、Kotlin Coroutines、Retrofit+OkHttp、Moshi、Room、MMKV、Media3、NanoHTTPD（本地代理）、ARouter  
**Storage**: Room（`media_library` 等表）+ MMKV（偏好/登录态存储）+ 本地缓存文件（字幕/弹幕/临时清单等）  
**Testing**: `./gradlew testDebugUnitTest`（JVM 单测）、`./gradlew connectedDebugAndroidTest`（设备/模拟器）；主要使用 JUnit4/AndroidX Test  
**Target Platform**: Android（minSdk 21 / targetSdk 35），手机 + TV（需考虑遥控器/焦点）  
**Project Type**: Android 多模块（MVVM）；`:storage_component` 提供 UI，`:core_storage_component` 提供存储实现  
**Performance Goals**: 与 `spec.md` 成功指标一致：扫码授权到可浏览 ≤2min；点击视频到开始播放 ≤5s；目录列表交互保持流畅（避免单次加载阻塞主线程）  
**Constraints**: 遵守模块依赖治理（禁止 feature ↔ feature 依赖）；仅使用百度开放平台 OAuth/OpenAPI（不做 cookie/BDUSS）；最小权限 `scope`；`refresh_token` 单次可用且刷新失败会失效（需串行刷新与持久化原子性）；dlink 需要特定 `User-Agent` 且存在 302/有效期  
**Scale/Scope**: MVP 聚焦“浏览 + 播放”，支持多账号（多个存储源）；目录可大、需分页/懒加载；不做上传/删除/分享等管理能力

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
specs/001-baidu-pan-storage/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
app/
storage_component/
core_contract_component/
core_log_component/
core_network_component/
core_storage_component/
core_system_component/
core_ui_component/
data_component/
buildSrc/

# 代码位置示例：
# - <module>/src/main/java/...
# - <module>/src/main/res/...
```

**结构说明（必须）**：

- 涉及模块（预计改动点）：
  - `:storage_component`：新增“百度网盘”存储源的新增/编辑 UI、二维码扫码授权对话框（或抽象为通用扫码授权组件）
  - `:core_storage_component`：新增 `BaiduPanStorage` 与 `BaiduPanStorageFile`；新增 Baidu Pan OpenAPI 仓库层（Repository）与播放直链缓存策略；复用 `LocalProxy/HttpPlayServer`
  - `:core_network_component`：新增 Baidu Pan OpenAPI Retrofit Service（仅声明接口，不承载业务逻辑）
  - `:data_component`：新增 `MediaType.BAIDU_PAN_STORAGE`、图标资源；新增 Baidu OpenAPI 响应/请求模型（data class）
  - `:core_system_component`：注入 `client_id/client_secret`（BuildConfig + local.properties/CI secrets），提供统一读取入口（避免在业务层散落读取逻辑）
  - `:core_contract_component`：仅在需要新增跨模块契约类型/接口时使用（优先保持现有 Storage 契约不变）
- 依赖关系：不新增 feature ↔ feature Gradle 依赖；仅在既有依赖链条内扩展（`storage_component -> core_storage_component -> core_network_component/core_system_component/data_component`）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无（当前设计不引入宪章约束的破例项）。
