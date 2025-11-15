<<<<<<< HEAD
# Repository Guidelines

## Project Structure & Module Organization
The app follows a modular MVVM layout: `app/` hosts the launcher shell, shared UI glue, and manifest; feature slices live in sibling directories (`anime_component/`, `player_component/`, `local_component/`, `storage_component/`, `stream_component/`, `user_component/`, `download_component/`). Foundation logic is centralized in `common_component/` (base classes, utilities) and `data_component/` (entities, repositories). Build tooling resides in `buildSrc/` and custom assets/scripts live under `document/`, `scripts/`, and `repository/`. Keep media or prompts within `Img/` or `prompts/` instead of polluting module folders.

## Build, Test, and Development Commands
Use Gradle from repo root:
- `./gradlew assembleDebug` – fast developer build with logging enabled.
- `./gradlew assembleRelease` – optimized, signed release artifacts.
- `./gradlew clean build` – full rebuild to validate cross-module wiring.
- `./gradlew dependencyUpdates` – report outdated libraries defined in `build.gradle.kts`.
- `./gradlew testDebugUnitTest` and `./gradlew connectedDebugAndroidTest` – run JVM unit tests and device/emulator instrumentation respectively.

## Coding Style & Naming Conventions
Stick to Kotlin 1.7.x with 4-space indentation, explicit visibility, and trailing commas disabled. View models live under `.../presentation` or `.../viewmodel` packages, fragments/activities use DataBinding layouts named `fragment_<feature>.xml` or `activity_<feature>.xml`. ARouter paths follow `/module/Feature`. Prefer extension functions for shared logic (place them in `common_component`). Lint via `./gradlew lint` before sending patches and let ktlint/Detekt settings inside `buildSrc` drive formatting rather than ad-hoc style tweaks.

## Testing Guidelines
Place JVM tests in `*/src/test/java` and instrumentation suites in `*/src/androidTest/java`; name files `<Class>Test.kt` or `<Feature>InstrumentedTest.kt` so Gradle discovers them. Cover parsing, player helpers, and data-layer conversions with unit tests, and reserve playback/integration flows for instrumentation backed by an emulator with media files in `storage_component`. Failing tests should block the PR, so run `testDebugUnitTest` locally and attach emulator logs when `connectedDebugAndroidTest` fails.

## Commit & Pull Request Guidelines
Recent history uses the `<type>: <summary>` pattern (`fix: ...`, `refactor: ...`); keep summaries under ~60 characters and describe scope (e.g., `player_component`). Squash noisy WIP commits before pushing. PRs must include: purpose, affected modules, test evidence (command + result), and UI screenshots when touching layouts. Link GitHub issues and note any required configuration toggles (`IS_APPLICATION_RUN`, `IS_DEBUG_MODE`).

## Security & Configuration Tips
Sensitive tokens belong in `local.properties` or Gradle properties; never hard-code keys. Toggle `IS_DEBUG_MODE` and `IS_APPLICATION_RUN` in `gradle.properties` when enabling verbose logs or single-module runs, then rebuild so the flags propagate. Follow `BUGLY_CONFIG.md` for crash reporting credentials, and remember the `user_component` ships with remote APIs disabled—avoid re-enabling interfaces without coordinator approval to keep builds distributable.

## Active Technologies
- Kotlin 1.7.x with Java interoperability for existing Exo helper classes (001-migrate-media3)
- Existing download cache plus `storage_component` test assets; no new external storage vendors planned (001-migrate-media3)
- Kotlin 1.7.21, AGP 7.3.1; JNI bindings to libass 0.17.3 (prebuilt) + ExoPlayer 2.18.x; libass 0.17.3; Android SDK 21–33; MMKV (settings); ARouter (navigation) (001-add-libass-backend)
- N/A (settings via MMKV; no schema changes) (001-add-libass-backend)

## Recent Changes
- 001-migrate-media3: Added Kotlin 1.7.x with Java interoperability for existing Exo helper classes
=======
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

DanDanPlayForAndroid 是一个安卓本地视频播放器应用，支持视频+弹幕播放。项目使用 Kotlin + MVVM + 组件化架构，基于 Android Gradle Plugin 7.3.1 和 Kotlin 1.7.10。

## 构建和开发命令

### 基本构建命令
```bash
# 构建调试版本
./gradlew assembleDebug

# 构建发布版本
./gradlew assembleRelease

# 清理项目
./gradlew clean

# 重新构建项目
./gradlew clean build

# 检查依赖库更新
./gradlew dependencyUpdates
```

### 项目配置
项目有两个重要的配置开关，需要在 `gradle.properties` 文件中设置：
- `IS_DEBUG_MODE`: 控制日志开关，修改后需要 rebuild project
- `IS_APPLICATION_RUN`: 设置为 true 时，模块以应用类型编译（用于单独编译模块），修改后需要 rebuild project

## 模块化架构

项目采用组件化架构，包含以下模块：

### 核心模块
- **app**: 项目入口，包含启动页及主框架
- **common_component**: 基础模块，包括基类、通用组件、工具类等
- **data_component**: 数据模块，包含 Bean 类、数据库 Entity 类、枚举类等

### 功能模块
- **anime_component**: 动画模块，首页、搜索、季番、番剧详情等
- **player_component**: 播放器模块，支持双内核（IJK、EXO）
- **local_component**: 本地数据模块，包含本地视频、弹幕下载、字幕下载
- **storage_component**: 存储模块
- **user_component**: 用户模块，包含用户信息、登录注册、应用设置等（出于安全考虑已关闭相关接口）

### 第三方库模块
- **stream_component**: 网络数据模块，包含 SMB、FTP、WebDav、串流等
- **download_component**: 下载模块，包括 Torrent 下载、磁链解析

## 技术栈

### 核心框架
- **Language**: Kotlin 1.7.21
- **Architecture**: MVVM + 组件化
- **UI**: Android DataBinding
- **路由**: ARouter 1.5.2
- **异步处理**: Kotlin Coroutines 1.6.4

### 主要依赖
- **数据库**: Room 2.4.3
- **网络**: Retrofit 2.9.0 + Moshi 1.14.0
- **播放器**: ExoPlayer 2.18.1, IJK Player, VLC 4.0.0-eap9
- **图片加载**: Coil 2.2.2
- **存储**: MMKV 1.2.14
- **错误上报**: Bugly 4.1.9
- **UI**: Material Design, Banner, RecyclerView

### 网络和存储
- **FTP**: Apache Commons Net 3.9.0
- **SMB**: smbj 0.10.0
- **WebDAV**: sardine-1.0.2.jar
- **HTTP**: NanoHTTPD 2.3.1

## 代码约定

### Bugly 错误上报约定
所有 Bugly 错误上报统一使用 `CrashReport.postCatchedException(...)` 或 `CrashReport.postException(...)`，其他 CrashReport 上报方法可能无法成功上报到云端。

### 项目结构约定
- 使用 MVVM 架构模式
- 组件化开发，模块间通过 ARouter 进行通信
- 使用 DataBinding 进行 UI 绑定
- 使用 Room 进行数据库操作
- 使用 Kotlin Coroutines 处理异步操作

### 自定义工具
1. **MVVM 插件**: plugin 目录下有 MVVMTemplate-xx.jar，用于快速生成符合项目的 MVVM 文件
2. **MMKV 注解**: 通过自定义注解快速生成 MMKV 调用方法，使用实例见 common 模块下 config 目录

## 版本信息
- **当前版本**: 4.1.2 (versionCode: 59)
- **最低 SDK**: 21
- **目标 SDK**: 29
- **编译 SDK**: 33
- **支持架构**: armeabi-v7a, arm64-v8a

## 开发注意事项

### 编译要求
- Java 8 兼容性
- 需要 MultiDex 支持
- 支持 ABI 分包构建

### Surface 有效性处理
在使用 `PixelCopy.request()` 时，需要注意 Surface 有效性检查：
1. 在调用 `PixelCopy.request()` 前必须检查 `surface.isValid()`
2. 确保 Surface 引用没有被回收或替换
3. 处理竞态条件，即使在检查后 Surface 仍可能变为无效
4. 对 `IllegalArgumentException` 进行异常捕获和上报

参考：`player_component/utils/PlayRecorder.kt:169`

### 模块依赖关系
所有功能模块都依赖 common_component 基础模块，app 模块作为入口集成所有功能模块。

### 调试和发布
项目配置了签名文件和多渠道打包，支持调试版和发布版构建。

### 约定
使用ErrorReportHelper工具类进行错误处理
  ，确保错误信息能够统一上报到Bugly进行监控和分析。
>>>>>>> origin/001-enhance-ass-subtitle-render
