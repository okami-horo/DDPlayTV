# 模块依赖治理规范（v2）

核对日期：2026-10-31。本文维护当前依赖规则及其决策依据；直接依赖清单和关系图由 [依赖快照](module_dependencies_snapshot.md) 自动生成，不在本文重复维护。

规则实现位于 [ModuleDependencyGovernance.kt](../../buildSrc/src/main/java/governance/ModuleDependencyGovernance.kt)。规则调整必须同步修改文档与实现。

## 0. 范围与口径

- [settings.gradle.kts](../../settings.gradle.kts) 显式纳入 22 个模块：1 个 app、5 个业务模块、8 个 core、1 个 data、7 个内置依赖包装模块。
- Gradle 还会创建嵌套模块的父项目 :repository。治理实现认识该容器，但不把它计入 22 个显式模块。
- 校验直接声明的 Gradle project 依赖，包括生产与测试配置；不展开传递依赖、不检查外部库版本，也不代替源码边界和行为验证。
- core_notification_component、core_service_component、remote_component、Han1meViewer 不是当前目录或构建模块。不得根据旧文档为它们添加依赖例外。

## 1. 模块职责

| 层 | 模块 | 职责 |
| --- | --- | --- |
| 组合根 | :app | 启动入口、全局装配、Android 平台宿主；通过契约触发各域能力 |
| 业务 | :anime_component、:local_component、:user_component、:storage_component、:player_component | 番剧、本地媒体、用户设置、存储浏览、播放业务与 UI |
| UI 基础 | :core_ui_component | 页面基类、焦点、通用控件与主题 |
| 基础设施 | :core_storage_component、:core_network_component、:core_database_component | 协议/来源解析、网络、持久化实现 |
| 元数据 | :core_metadata_component | 文件名解析、TMDB 匹配、资料关联与目录扫描编排；不持有播放器或页面 |
| 运行时 | :core_system_component、:core_log_component | 启动与系统整合、日志及异常上报 |
| 契约与数据 | :core_contract_component、:data_component | 跨模块接口/路由/稳定播放描述、共享模型 |
| 内置依赖 | :repository:* | AAR 包装；不承担业务规则 |

当前 app 的生产依赖已收敛为业务模块及 system/log/ui/contract/data。用户会话恢复和云弹幕屏蔽词同步通过契约服务触发，数据库手动迁移由所属模块的 Startup initializer 负责。core_ui_component 已不依赖 storage/network/database 实现层；这两项是已经完成的边界，不再列为待实施重构。

## 2. 硬规则

1. 依赖保持单向、无环；业务模块之间不得直接依赖。
2. core、基础设施、契约、数据及 repository 模块不得反向依赖 app 或业务模块。
3. UI 基础不得依赖基础设施实现；需要共享 StorageFile 等类型时直接依赖 contract。
4. 使用方显式声明真正需要的模块；不得仅为获取传递类型而引入实现模块。
5. 默认使用 implementation；只有公开 API 确实暴露下游类型、且命中允许列表时，才可使用 api(project(...))。

这些是项目设计约束。当前自动门禁执行已知模块、直接依赖矩阵和 project API 暴露列表；不要把门禁通过理解为已完成全部源码依赖或运行时循环分析。

## 3. 维护流程

新增模块或直接依赖时：

1. 先说明能力归属，判断是否应通过 contract/data 协作。
2. 同步更新第 4 节、相关 DR 与 buildSrc 中的允许列表；API 暴露变化必须说明原因。
3. 执行 ./gradlew verifyModuleDependencies --console=plain，查看输出尾部的 BUILD SUCCESSFUL 或 BUILD FAILED。
4. 使用 Python 3 运行 scripts/module_deps_snapshot.py --write，更新 Markdown 和 JSON 快照，并检查生成差异。
5. 按改动范围运行构建、测试和其他 [治理检查](architecture_governance_guardrails.md)。

生成器仅静态识别源码中形如 implementation(project(":module")) 的完整声明行；条件分支、变量或插件注入可能超出其能力。争议以 Gradle 配置、规则实现及实际构建证据为准，不手工把生成快照改成理想目标图。

## 4. 允许矩阵

### 4.1 读取方式

允许列表表达“可以声明”，不表示所有允许边都已存在。现有声明、配置名称和生产关系图见 [自动快照](module_dependencies_snapshot.md)。

### 4.2 公共记号

下表的“普通业务基础依赖”指 :core_ui_component、:core_system_component、:core_log_component、:core_network_component、:core_database_component、:core_storage_component、:core_contract_component、:data_component。

### 4.3 直接依赖规则

#### 4.3.1 生产依赖

| 来源 | 允许目标 |
| --- | --- |
| :app | 5 个业务模块，以及 system、log、ui、contract、data；不直接依赖 network、database、storage 实现 |
| :anime_component | 普通业务基础依赖 |
| :local_component、:user_component、:storage_component | 普通业务基础依赖，以及 :core_metadata_component |
| :player_component | 普通业务基础依赖，以及 :repository:danmaku、:repository:panel_switch、:repository:video_cache、:repository:mpv |
| :core_ui_component | contract、system、log、data，以及 :repository:immersion_bar |
| :core_storage_component | contract、network、database、system、log、data，以及 :repository:seven_zip |
| :core_metadata_component | contract、data、network、database、storage、system、log |
| :core_network_component | system、log、data |
| :core_database_component | system、data |
| :core_system_component | contract、log、data |
| :core_log_component、:core_contract_component | data |
| :repository:mpv | 仅 :repository:mpv_native；Java 包装层使用 compileOnly + runtimeOnly 隔离上游 Kotlin 元数据 |
| :data_component、:repository、其余 :repository:* | 无本工程 project 依赖 |

表中省略前缀的 core 名称均指对应 :core_*_component，data 指 :data_component。

#### 4.3.2 测试额外依赖

测试配置继承生产允许目标，并可额外使用：

- :core_network_component → :core_contract_component。
- :app → :core_storage_component、:core_database_component、:core_metadata_component，用于仪器测试的来源、历史与本地媒体夹具，以及真实元数据和图片加载验证。

测试例外不授予生产配置同样的依赖权限。

#### 4.3.3 project API 暴露列表

| 暴露方 | 允许 api(project(...)) 的目标 |
| --- | --- |
| :core_contract_component | :data_component |
| :core_ui_component | :data_component、:repository:immersion_bar |
| :core_network_component、:core_database_component、:core_log_component | :data_component |

这是门禁允许范围；当前实际声明仅 :core_contract_component → :data_component 使用 api，见快照。UI 的 ImmersionBar 依赖已是 implementation，不得再把旧 API 泄漏问题描述为现状。

## 5. 本地与 CI 验证

- 单项门禁：./gradlew verifyModuleDependencies。
- 完整本地集合：./gradlew verifyArchitectureGovernance，内容与限制见 [治理检查说明](architecture_governance_guardrails.md)。
- [.github/workflows/sonarcloud.yml](../../.github/workflows/sonarcloud.yml) 已显式执行模块依赖、播放边界、Debug 单元测试、lint、JaCoCo 报告与 Debug 构建。
- 该 workflow 没有调用完整聚合任务，也未显式执行 verifyLegacyPagerApis 或 ktlintCheck；不能把其中部分检查通过等同于全量门禁通过。
- 2026-10-28 已有模块/播放检查通过的本地证据；全仓旧 ktlint/Pager 基线仍有已知失败。历史测试结果及范围以 [播放交接计划](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md) 的记录为准，本次文档整理不产生新的功能验证结果。

## 6. 决策记录

### DR-0004：元数据编排独立于存储和播放器

新增 core_metadata_component 供 local/storage/user 共用作品识别、人工关联和扫描任务。它向下调用 storage/network/database；core_storage 不反向依赖 metadata，app 和 player 不增加 metadata 实现依赖。资料关联保存独立文件身份，播放仍通过既有 contract 描述符解析；模块通过不代表 TMDB 或协议服务已完成实机验收。详见 [TMDB 实施记录](../../docs/superpowers/plans/2026-10-31-tmdb-media-library.md)。

### DR-0001：system 是应用运行时，允许 system → log

core_system_component 承担 Application、启动编排和系统整合，BaseApplication 需要尽早初始化日志/异常上报，因此允许依赖 core_log_component。日志模块不反向依赖 system。此前讨论的“另建 core_app 模块、让 system 变成纯系统工具”没有采用，不是待实施的默认目标；只有出现具体职责变化时才重新评估。

### DR-0003：限制 project API 暴露

跨模块类型优先由 contract/data 提供，避免实现层充当传递类型出口。保留第 4.3.3 节的兼容允许列表，但允许不等于必须使用 api。已经完成的收敛包括：UI 移除 storage 实现依赖、storage 不再通过 api 暴露 repository 模块、UI 的 ImmersionBar 改为 implementation。新的例外必须基于真实公开 API 的类型需求，不能仅为减少 import 或依赖声明而放宽。
