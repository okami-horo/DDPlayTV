# 项目上下文（DanDanPlayForAndroid）

## 项目目标（Purpose）

《弹弹play 概念版》是弹弹play 系列在安卓平台的实现，定位为“本地/局域网媒体播放器 + 弹幕 + 字幕 + 动漫资讯”。核心目标：提供稳定流畅的播放体验，并围绕“视频-弹幕-字幕”形成完整的匹配、下载与渲染链路。

主要能力（非穷举）：

- 播放器：多内核（Media3/Exo、VLC；部分场景含 mpv/libass 原生能力），适配常见视频格式
- 存储/来源：本地媒体库 + 局域网/远程存储（SMB/FTP/WebDav 等）浏览与播放
- 弹幕：自动匹配、搜索/下载、样式调整、关键字/正则屏蔽
- 字幕：自动匹配、搜索/下载、外挂字幕、libass 渲染后端（部分内核）
- 动漫：番剧搜索、筛选、详情、追番、历史等

## 技术栈（Tech Stack）

### 语言与构建

- Kotlin（Gradle 插件：`1.9.25`），JVM target `1.8`
- Android Gradle Plugin：`8.7.2`，Gradle 多模块工程
- SDK 版本（以 `buildSrc/src/main/java/Versions.kt` 为准）：`compileSdk=35`、`minSdk=21`、`targetSdk=35`
- 代码生成：kapt（ARouter/Room/Moshi 等）、部分自研注解（MMKV 配置表）

### AndroidX / 基建

- 架构：MVVM（ViewModel/LiveData 等），模块化（组件化）
- Jetpack：Lifecycle、Room、Paging、Startup、Preference、RecyclerView/ConstraintLayout 等
- 协程：Kotlin Coroutines
- 图片：Coil（含视频封面）
- Key-Value：MMKV

### 网络 / 数据

- Retrofit + OkHttp
- Moshi（JSON 序列化/反序列化）
- Room（本地数据库）

### 路由 / 解耦

- ARouter（跨模块页面/服务路由）
- `:core_contract_component` 承载跨模块契约（类型/接口/路由表等）

### 播放 / 多媒体

- Media3（版本统一由 `gradle.properties` 的 `media3Version` 控制）
- VLC（`libvlc-all`）
- 原生能力：`player_component` 含 CMake/NDK 编译与 `.so` 管理（mpv/libass 等；并对 release/beta 做 strip）

## 项目约定（Project Conventions）

### 代码风格（Code Style）

以仓库根目录 `.editorconfig` 与 Gradle ktlint 配置为准：

- 缩进：space 4
- Kotlin 风格：`ktlint_official`
- Kotlin trailing comma：关闭（`ij_kotlin_allow_trailing_comma = false`）
- ktlint 规则：对部分规则做了关闭（如包名、通配符 import、函数命名、最大行长等），请保持与现有代码一致

常用命令：

- `./gradlew ktlintCheck`
- `./gradlew lint`（或 `lintDebug`）

### 架构与模块（Architecture Patterns）

#### 模块划分（以 `settings.gradle.kts` 为准）

- 入口壳：`:app`
- 业务功能：`:anime_component`、`:local_component`、`:user_component`、`:storage_component`、`:player_component`
- 核心/基础：`:data_component`、`:core_contract_component`、`:core_system_component`、`:core_log_component`、`:core_network_component`、`:core_database_component`、`:core_storage_component`、`:core_ui_component`、`:bilibili_component`
- 内置依赖封装：`:repository:*`（danmaku/immersion_bar/panel_switch/seven_zip/thunder/video_cache）

注意：仓库里可能存在未纳入构建的目录（例如 `remote_component/`、`Han1meViewer/` 等）。做架构/依赖判断时，以 `settings.gradle.kts` 与各模块 `build.gradle.kts` 的 `project(...)` 为事实来源。

#### 分层与依赖治理（关键约束）

- 单向依赖、无环：跨模块协作优先走 `:core_contract_component` 的契约/接口
- 业务模块之间禁止直接互相依赖（通过契约 + 路由/服务化交互）
- `core_*` 以可复用基建为主，避免反向依赖业务模块
- 依赖治理与校验：
  - 规则文档：`document/architecture/module_dependency_governance.md`
  - 快照：`document/architecture/module_dependencies_snapshot.md`
  - 校验任务：`./gradlew verifyModuleDependencies`、`./gradlew verifyArchitectureGovernance`

### UI 与资源命名

- 全模块开启 DataBinding（见 `buildSrc/src/main/java/setup/*`），布局命名沿用模块既有约定：`activity_*.xml` / `fragment_*.xml` 等
- TV/遥控器体验：涉及焦点/可见性/顺序调整时，需确保 DPAD 可达与焦点反馈完整

### 测试策略（Testing Strategy）

- JVM 单测：`*/src/test/java`；仪器测试：`*/src/androidTest/java`
- 推荐命令：`./gradlew testDebugUnitTest`、`./gradlew connectedDebugAndroidTest`
- 增量原则：优先为解析/转换/工具类等稳定逻辑补充单测；播放/存储等集成流程优先用仪器测试覆盖

### Git 工作流（Git Workflow）

- 提交信息：沿用 `<type>: <summary>`（如 `fix: ...`、`refactor: ...`），summary 建议 ≤ 60 字符并体现模块范围
- PR 说明建议包含：目的、影响模块、验证方式（命令 + 结果）、涉及 UI 时附截图

## 领域上下文（Domain Context）

- “弹幕”（Danmaku）：与视频时间轴对齐的滚动评论；支持自动匹配、搜索下载、渲染与屏蔽策略
- “字幕”：外挂字幕/在线检索；部分播放链路支持 libass 渲染后端（提高 ASS 表现）
- “存储”（Storage）：本地文件与远程协议（SMB/FTP/WebDav 等）的统一抽象与浏览/播放入口
- “番剧/资源”：与弹弹play 开放平台接口相关的番剧、剧集、资源搜索与详情

## 重要约束（Important Constraints）

- 密钥与开源安全：不要硬编码 token/secret；优先通过 `local.properties`、Gradle properties 或 CI 环境变量注入（见 `core_system_component/build.gradle.kts` 的 BuildConfig 注入逻辑）
- ABI 与原生库：播放器包含原生 `.so`（mpv/libass 等），release/beta 需要保证 strip 与打包逻辑一致（见 `player_component/build.gradle.kts`）
- Media3 版本与开关：`gradle.properties` 包含 `media3Version` 与 `media3_enabled` 等开关；`app/build.gradle.kts` 也会将部分开关写入 BuildConfig
- 架构一致性优先：遇到分层/依赖冲突时，优先统一适配到既有治理规则，而不是“最小侵入式”绕过

## 外部依赖（External Dependencies）

- 弹弹play 开放平台：`https://api.dandanplay.net/swagger/ui/index`
- B 站相关能力（在 `:bilibili_component` 内封装）
- 腾讯 Bugly（崩溃/上报链路）；配置说明见 `BUGLY_CONFIG.md`
