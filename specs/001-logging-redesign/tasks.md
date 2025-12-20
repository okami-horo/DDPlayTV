---

description: "001-logging-redesign 日志系统重构与治理的实现任务清单"
---

# Tasks: 001-logging-redesign 日志系统重构与治理

**Input**: `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/` 下的 plan.md、spec.md、data-model.md、contracts/logging-openapi.yaml、research.md、quickstart.md  
**Prerequisites**: 已完成设计文档评审，本清单用于指导实现与测试拆分

**Tests**: 本特性推荐按计划逐步补充单元测试与 Instrumentation 测试；以下各阶段仅在明确需要时给出测试任务。

**Organization**: 所有实现任务按阶段与用户故事（US1~US3）组织，保证每个用户故事都可以独立实现与验证。

## 任务格式说明

每一行任务均遵循如下格式（必须严格遵守）：

`- [ ] T001 [P] [US1] 描述（包含绝对文件路径）`

- `- [ ]`：Markdown 复选框  
- `T***`：从 `T001` 开始的递增任务 ID  
- `[P]`：可并行执行的任务（修改不同文件且无直接依赖时标记）  
- `[US*]`：用户故事标签，仅在用户故事阶段使用（如 `[US1]`、`[US2]`、`[US3]`）  
- 描述：必须给出至少一个绝对路径，例如 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/...`

---

## Phase 1：Setup（通用准备）

**Purpose**: 为新的日志系统准备必要的配置与测试骨架，不改变现有行为。

- [X] T001 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/DevelopConfig.kt` 中新增日志相关默认配置常量（例如默认日志策略名称、是否允许写入调试日志、单文件大小上限占位字段），仅赋默认值不改动现有逻辑。
- [X] T002 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogModelPackageMarker.kt` 中创建空的包占位文件，并注释说明所有日志数据模型类集中存放在 `com.xyoye.common_component.log.model` 包。
- [X] T003 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LoggingTestBase.kt` 中创建日志测试基类（包含公共常量与空的示例测试方法），供后续日志相关 JUnit 测试复用。

---

## Phase 2：Foundational（阻塞性前置实现）

**Purpose**: 实现所有用户故事共享的核心日志模型、文件 I/O 能力与基础 UI 骨架，完成后才能开始各用户故事。

### 2.1 日志数据模型与运行时状态

- [X] T004 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogLevel.kt` 中根据 `data-model.md` 定义 `LogLevel` 枚举（DEBUG/INFO/WARN/ERROR），并用注释标明与 FR-013 的约束关系。
- [X] T005 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogModule.kt` 中实现 `LogModule` 枚举或注册表（包含 core/player/download/user/anime/local/storage/network/subtitle 等模块），并提供根据包名或调用方传入值推导模块的辅助方法。
- [X] T006 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogTag.kt` 中实现 `LogTag` 数据类，包含模块引用与标签字符串长度上限校验逻辑。
- [X] T007 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogEvent.kt` 中实现 `LogEvent` 数据类（时间戳、级别、模块、tag、message、context、throwable、threadName、sequenceId），并添加基础入参校验（如 message 非空、context 键值长度限制）。
- [X] T008 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogPolicy.kt` 中实现 `LogPolicy` 与 `SamplingRule` 数据类，包含默认策略工厂方法（例如 `defaultReleasePolicy`、`debugSessionPolicy`），并体现 enableDebugFile 字段的含义。
- [X] T009 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogRuntimeState.kt` 中实现 `LogRuntimeState` 与 `PolicySource` 及调试开关状态枚举，覆盖 `data-model.md` 中的字段与状态迁移注释。
- [X] T010 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogFileAndPackage.kt` 中实现 `LogFileMeta`、`LogPackage`、`LogExportRequest`、`LogExportResult` 数据类，字段设计与 `contracts/logging-openapi.yaml` 中的 schema 保持一致，用于支持未来本地工具或脚本打包日志文件，本次实现不要求在应用内提供导出入口。

### 2.2 日志门面、文件管理与写入管线

- [X] T011 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFormatter.kt` 中实现日志序列化工具，将 `LogEvent` 转换为结构化文本（例如 JSON 行或 key=value 列表），并预留按级别/模块裁剪字段的扩展点。
- [X] T012 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFileManager.kt` 中实现内部存储日志目录解析与 `log.txt`/`log_old.txt` 双文件管理，满足 FR-015 对文件命名与轮转（冷启动合并历史日志）的约束。
- [X] T013 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogWriter.kt` 中实现单线程异步写入执行器，根据当前 `LogPolicy` 与 `LogRuntimeState` 决定是否写入 logcat、本地文件或直接丢弃日志事件。
- [X] T014 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFacade.kt` 中定义统一日志门面接口（d/i/w/e 等方法，必须显式接收 `LogModule` 与可选 `LogTag`、结构化 `context`），供业务代码与 `DDLog` 调用。
- [X] T015 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogSystem.kt` 中实现日志系统单例，负责初始化（init）、从存储加载策略（loadPolicyFromStorage）、维护 `LogRuntimeState` 并委托 `LogWriter` 完成最终输出。
- [X] T016 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/utils/DDLog.kt` 中将现有 `DDLog` 实现改为委托给 `LogFacade`/`LogSystem`，为缺失模块信息的调用提供合理默认 `LogModule` 映射，并保持对旧调用点的行为兼容。
- [X] T017 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/AppLogger.kt` 中重构历史文件日志实现，使其仅作为 `LogFileManager`/`LogWriter` 的实现细节或迁移层，避免被业务代码直接依赖。

### 2.3 应用初始化与日志配置入口骨架

- [X] T018 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/IApplication.kt` 中调用 `LogSystem.init` 与策略加载方法（如 `loadPolicyFromStorage`），确保在其他组件使用日志前完成日志系统初始化。
- [X] T019 [P] 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/ui/debug/LoggingConfigActivity.kt` 中创建日志配置 Activity 骨架，仅包含基础的 Activity 壳与标题栏。
- [X] T020 [P] 在 `/workspace/DanDanPlayForAndroid/app/src/main/res/layout/activity_logging_config.xml` 中定义日志配置页面的基础布局（模块列表占位、级别选择控件占位、调试日志开关占位），先不实现具体交互。
- [X] T021 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/ui/main/MainActivity.kt` 中增加「日志配置」入口（菜单项或按钮），通过 ARouter 路由或显式 Intent 打开 `LoggingConfigActivity`。
- [X] T022 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogPaths.kt` 中集中定义日志目录子路径等常量（例如 logs 子目录名），并在 `LogFileManager` 中引用该常量，避免日志路径散落在多处实现中。
- [X] T023 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogFileManagerPathTest.kt` 中编写单元测试，验证日志目录路径符合约定（如位于应用内部存储下的固定 logs 子目录），方便开发者通过 adb / 文件管理器直接定位日志文件。

### 2.4 基础测试覆盖

- [X] T024 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogPolicyTest.kt` 中编写单元测试，覆盖默认策略（全局级别）、调试日志开关以及 `enableDebugFile = false` 时禁止写入本地文件的决策路径。
- [X] T025 [P] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogFormatterTest.kt` 中编写单元测试，验证 `LogEvent` 序列化格式、结构化上下文字段键值长度限制与异常堆栈展开逻辑。
- [X] T026 在 `/workspace/DanDanPlayForAndroid/common_component/src/androidTest/java/com/xyoye/common_component/log/LogFileRotationInstrumentedTest.kt` 中编写 Instrumentation 测试，验证应用冷启动时 `log.txt` 内容合并到 `log_old.txt`、并重建当前会话 `log.txt` 的行为符合 FR-015。
- [X] T027 在 `/workspace/DanDanPlayForAndroid/common_component/src/androidTest/java/com/xyoye/common_component/log/LogDiskErrorInstrumentedTest.kt` 中编写 Instrumentation 测试，模拟磁盘空间不足或连续写入失败，验证系统进入 `DISABLED_DUE_TO_ERROR` 状态并停止后续文件写入（符合 FR-016），仍保留 logcat 输出。

**Checkpoint（Phase 2 完成条件）**：  
核心数据模型、日志门面与文件写入骨架稳定，应用能在不改变现有行为的前提下通过上述测试；之后可按用户故事拆分独立增量。

---

## Phase 3：User Story 1 - 统一可控的日志开关（Priority: P1）🎯 MVP

**Goal**: 通过统一入口配置全局日志级别与调试日志开关，在不改变结构化或导出能力的前提下，集中控制整应用的日志输出噪声。  
**Independent Test**: 仅开启统一日志配置与开关，无需实现导出与采样时，通过调整全局日志级别（例如从 INFO 提升到 DEBUG 或降低到 WARN）即可在几分钟内定位典型问题。

### Tests for User Story 1（按需补充）

- [X] T028 [P] [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogConfigTableTest.kt` 中编写单元测试，验证 MMKV 中的日志策略存取逻辑与 `LogPolicy`/`LogRuntimeState` 的映射正确（包括全局级别与调试日志开关）。  
- [X] T029 [P] [US1] 在 `/workspace/DanDanPlayForAndroid/app/src/androidTest/java/com/xyoye/dandanplay/ui/debug/LoggingConfigActivityTest.kt` 中编写 UI 测试，模拟调整全局日志级别与开启/关闭调试日志开关，并断言 `LogRuntimeState` 中的 `activePolicy` 与调试会话状态同步更新。

### Implementation for User Story 1

- [X] T030 [P] [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/LogConfigTable.kt` 中创建日志配置 MMKV 表定义（使用 `@MMKVKotlinClass`），持久化当前日志策略与调试开关状态。
- [X] T031 [P] [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogPolicyRepository.kt` 中实现日志策略仓库，封装对 `LogConfigTable` 的读写并提供策略变更监听接口。
- [X] T032 [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogSystem.kt` 中接入 `LogPolicyRepository`，在 `init` 与策略变更时更新 `LogRuntimeState`，确保应用启动后生效的是合并后的策略。
- [X] T033 [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogSystem.kt` 中实现 `getLoggingPolicy`/`updateLoggingPolicy` 方法，使其与 `contracts/logging-openapi.yaml` 中 `/logging/policy` 的 GET/PUT 契约一致。
- [X] T034 [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogSystem.kt` 中实现 `startDebugSession`/`stopDebugSession` 方法（映射 `/logging/debug-session` POST/DELETE），根据请求更新调试日志开关与会话状态机。
- [X] T035 [P] [US1] 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/ui/debug/LoggingConfigViewModel.kt` 中实现日志配置 ViewModel，包装 `LogSystem` 的策略/会话接口并暴露给 UI 层。
- [X] T036 [US1] 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/ui/debug/LoggingConfigActivity.kt` 中绑定 `LoggingConfigViewModel` 与 `activity_logging_config.xml`，完成全局级别选择控件与调试日志开关的交互逻辑（模块列表仅用于展示标签信息时可选）。  
- [X] T037 [US1] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogWriter.kt` 中补充基于 `LogPolicy.defaultLevel` 的过滤逻辑，确保按照全局级别与调试开关控制日志输出时，实际结果符合 User Story 1 的期望。

**Checkpoint**：仅基于 logcat 与统一配置入口，即可验证全局级别与调试开关是否工作，无需依赖日志导出或采样能力。

---

## Phase 4：User Story 2 - 高信噪比的诊断日志（Priority: P2）

**Goal**: 在不改变线上上传方案的前提下，通过结构化本地日志和噪声治理，为支持/测试提供高信噪比、易于离线分析的诊断日志文件（开发者直接从日志目录获取）。  
**Independent Test**: 仅依赖本地日志目录中的 `log.txt` / `log_old.txt`，对同一问题分别使用改造前后日志进行分析，应显著降低噪声并缩短问题定位时间。

### Tests for User Story 2（按需补充）

- [X] T038 [P] [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogFormatterHighSignalTest.kt` 中编写单元测试，验证 `LogFormatter` 生成的日志行按时间顺序、模块和级别正确输出，并包含关键上下文字段（如 scene、errorCode、sessionId 等）。
- [X] T039 [P] [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/androidTest/java/com/xyoye/common_component/log/LogFileQualityInstrumentedTest.kt` 中编写 Instrumentation 测试，模拟典型问题场景，运行后直接从日志目录读取 `log.txt` / `log_old.txt`，检查是否能够用少量日志行还原问题链路且无明显重复噪声。

### Implementation for User Story 2

- [X] T040 [P] [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFormatter.kt` 中根据 User Story 2 的要求优化日志行格式（包含时间戳、模块标签、级别和关键上下文字段），并确保单条日志长度可控且便于脚本解析。
- [X] T041 [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogWriter.kt` 中补充对 `threadName`、`sequenceId` 与结构化 `context` 字段的写入，将这些信息通过 `LogFormatter` 序列化到日志行中，方便离线重建问题链路。
- [X] T042 [P] [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFileManager.kt` 中实现扫描日志目录并构造当前可用 `LogFileMeta` 列表的方法，供测试代码与离线分析工具使用（不依赖应用内导出入口）。
- [X] T043 [P] [US2] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFormatter.kt` 中预留或实现对明显重复/低价值 DEBUG 日志的简单过滤或聚合策略，以减少文件中的无效噪声。
- [X] T044 [US2] 在 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/quickstart.md` 中补充「如何阅读 log.txt / log_old.txt」小节，给出典型日志行示例及推荐的关键字段（scene、errorCode、sessionId 等）。
- [X] T045 [US2] 在 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/data-model.md` 中对 `LogEvent.context` 和相关实体补充推荐字段列表与使用规范，确保不同模块在记录结构化上下文时风格一致。
- [X] T046 [US2] 在 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/research.md` 或 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/plan.md` 中补充一份「日志质量对比实验」方案，用于指导支持/测试在同一问题样本上对比旧日志与新日志的分析耗时和噪声占比。

**Checkpoint**：开发者或支持/测试人员仅需从本地日志目录获取 `log.txt` / `log_old.txt` 等文件，即可在单份日志中还原问题链路，且文件中的无效或重复噪声明显减少。

---

## Phase 5：User Story 3 - 低开销的日志采集与导出（Priority: P3）

**Goal**: 在保证诊断能力的前提下，将日志系统对性能与存储空间的影响控制在可接受范围，尤其在高日志量策略下不引入可感知卡顿或过度空间占用。  
**Independent Test**: 在「高日志量」实验构建与默认策略下分别进行性能与空间占用对比，验证启动耗时、交互卡顿率与日志文件大小是否满足 SC-002、SC-003 要求。

### Tests for User Story 3（按需补充）

- [X] T047 [P] [US3] 在 `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/log/LogSamplingRuleTest.kt` 中编写单元测试，验证 `SamplingRule` 的采样率与每分钟限流逻辑，对不同模块与级别组合进行覆盖。
- [X] T048 [P] [US3] 在 `/workspace/DanDanPlayForAndroid/app/src/androidTest/java/com/xyoye/dandanplay/log/LoggingPerformanceInstrumentedTest.kt` 中编写性能向 Instrumentation 测试，对比默认策略与高日志量策略下冷启动耗时与核心交互卡顿率。

### Implementation for User Story 3

- [X] T049 [P] [US3] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogSampler.kt` 中实现基于 `SamplingRule` 的采样与限流决策器，维护每模块事件计数与时间窗口。
- [X] T050 [US3] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogWriter.kt` 中集成 `LogSampler`，在真正输出日志前执行采样/限流判断，以减少高频路径上的日志开销。
- [X] T051 [US3] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/LogFileManager.kt` 中实现磁盘空间检查与连续写入失败计数逻辑，触发 `LogRuntimeState` 进入 `DISABLED_DUE_TO_ERROR` 并停止写入本地文件。
- [X] T052 [US3] 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/model/LogPolicy.kt` 中调整默认策略（如 `defaultReleasePolicy` 与高日志量策略），确保默认策略仅输出 logcat 且不写本地文件，高日志量策略下 `log.txt` + `log_old.txt` 总大小约为 10MB。
- [X] T053 [US3] 在 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/quickstart.md` 中补充性能与空间占用测试指引（包括如何开启高日志量策略、如何收集对比数据与评估 SC-002/SC-003），保证 User Story 3 可独立验证。

**Checkpoint**：在默认策略下，应用无本地持久化日志文件且性能与基线等价；在高日志量策略下也无明显卡顿，日志文件大小控制在预期范围内。

---

## Final Phase：Polish & Cross-Cutting Concerns

**Purpose**: 清理旧实现、完善跨模块接入与文档，确保日志系统易于长期维护。

- [X] T054 [P] 在 `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/` 目录下梳理现有 `DDLog` 调用，将其替换为显式传入 `LogModule` 的 `LogFacade`/`LogSystem` 调用，并删除重复或无意义的 Tag 字符串。
- [X] T055 [P] 在 `/workspace/DanDanPlayForAndroid/player_component/src/main/java/` 目录下迁移 `DDLog` 调用到新的日志门面，为播放器相关代码统一使用 `LogModule.PLAYER`，并调整日志级别以符合新的分级准则。
- [X] T056 [P] 在 `/workspace/DanDanPlayForAndroid/anime_component/src/main/java/` 与 `/workspace/DanDanPlayForAndroid/local_component/src/main/java/` 目录下迁移 `DDLog` 调用到新的日志门面，为各业务模块选择合适的 `LogModule` 枚举值。
- [X] T057 [P] 在 `/workspace/DanDanPlayForAndroid/storage_component/src/main/java/` 与 `/workspace/DanDanPlayForAndroid/user_component/src/main/java/` 目录下迁移 `DDLog` 调用到新的日志门面，并校正模块名与日志级别，避免 DEBUG 日志在默认策略下输出。
- [X] T058 在 `/workspace/DanDanPlayForAndroid/` 仓库范围内搜索直接使用 `android.util.Log` 的调用，将其封装或替换为统一的 `LogFacade`/`LogSystem` 调用，确保业务代码不直接依赖底层日志实现。
- [X] T059 [P] 在 `/workspace/DanDanPlayForAndroid/document/monitoring/logging-system.md` 中撰写日志系统维护文档（中文），介绍配置入口、日志文件位置，以及通过 adb / 文件管理器获取日志文件的典型排查步骤，并链接到 `quickstart.md`。
- [X] T060 在 `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/AppLogger.kt` 中清理不再使用的历史日志 API（如基于时间戳命名多文件的实现），对仍保留的兼容方法标记 `@Deprecated` 并在注释中指向新的日志门面。
- [ ] T061 在 `/workspace/DanDanPlayForAndroid/specs/001-logging-redesign/quickstart.md` 描述的流程基础上手动回归日志配置与性能场景，以及从日志目录获取日志文件的操作流程，将发现的问题和限制记录回该文件，确保文档与实现保持一致。

---

## Dependencies & Execution Order（依赖关系与执行顺序）

### 阶段依赖

- **Setup（Phase 1）**：无前置依赖，可立即开始；为后续日志实现准备配置与测试骨架。  
- **Foundational（Phase 2）**：依赖 Phase 1 完成；实现核心数据模型、日志门面与文件写入逻辑，是所有用户故事的前置条件。  
- **User Story 1（Phase 3, P1）**：依赖 Phase 2 完成；在完成 US1 后即可交付最小可用的统一日志配置与开关能力（MVP）。  
- **User Story 2（Phase 4, P2）**：依赖 Phase 2 完成；在 API 层与 US1 解耦，可与 US1 并行开发，主要关注日志格式与噪声治理。  
- **User Story 3（Phase 5, P3）**：依赖 Phase 2 完成；可在 US1/US2 完成后独立验证，也可以在接口稳定后与 US2 部分并行。  
- **Polish（Final Phase）**：依赖所有目标用户故事完成后执行，用于清理旧实现与补充文档。

### 用户故事依赖图

- **US1（统一开关，P1）**：仅依赖基础模型与门面（Phase 2），不依赖 US2/US3，可最先完成并作为 MVP。  
- **US2（高信噪比诊断，P2）**：依赖 `LogEvent`/`LogFileMeta` 与基础文件写入能力（Phase 2），通过日志格式与噪声治理提高诊断效率，与 US1 的配置入口解耦，可独立测试。  
- **US3（低开销采集，P3）**：依赖 `LogPolicy`/`SamplingRule` 与 `LogWriter` 的管线实现，可在 US1、US2 完成后针对高日志量场景做性能优化与验证，不改变已有功能接口。

每个用户故事阶段都包含独立的测试任务和实现任务，按照上述依赖关系执行时，任意阶段完成后都可以在不等待后续故事的情况下单独回归测试。

---

## Parallel Execution Examples（并行执行示例）

### User Story 1（P1）

- 并行实现：  
  - T030/T031（`LogConfigTable` 与 `LogPolicyRepository`，不同文件）可以并行开发；  
  - T035（ViewModel）与 T036（Activity 绑定）可在接口约定后并行，其中 T036 依赖 T035 的 ViewModel 定义。  
- 并行测试：  
  - T028（MMKV 与策略映射单测）与 T029（配置页 UI 测试）可在实现完成后分别由后端/客户端同学并行编写与执行。

### User Story 2（P2）

- 并行实现：  
  - T040（日志格式优化）、T041（写入附加元信息）与 T042（日志文件扫描）位于不同文件，可在约定好日志行结构后并行实现；  
  - T044/T045/T046 主要涉及文档与规范补充，可在实现完成后并行推进。  
- 并行测试：  
  - T038（高信噪比日志格式单测）与 T039（日志文件质量 Instrumentation 测试）可以在实现完成后并行补充。

### User Story 3（P3）

- 并行实现：  
  - T049（采样决策器）与 T051（磁盘空间守卫）位于不同文件，可在确定状态字段后并行；T050 将两者接入 `LogWriter`，需等待它们完成。  
- 并行测试：  
  - T047（采样规则单测）与 T048（性能测试）可以在不同环境中独立执行，一个关注功能正确性，一个关注性能与资源占用。

---

## Implementation Strategy（实现策略）

### MVP 优先（仅交付 User Story 1）

1. 完成 Phase 1 与 Phase 2，搭建统一日志模型、门面与写入骨架。  
2. 完成 Phase 3（US1）中的实现与测试任务（T028~T037），实现统一日志配置与全局级别/调试开关控制。  
3. 通过 US1 的独立验收场景与相关测试确认：在不启用本地持久化日志的前提下，配置入口可以稳定控制日志输出。  
4. 在此基础上即可进行一次内部演示或试发布，作为 MVP 里程碑。

### 增量交付（依次增加诊断与性能保障能力）

1. 在 MVP 基础上执行 Phase 4（US2），补充结构化日志与高信噪比诊断能力（基于本地日志文件而非应用内导出入口），完成后验证 SC-001 与相关验收场景。  
2. 进一步执行 Phase 5（US3），实现采样/限流与磁盘空间守卫，结合性能测试任务确保 SC-002/SC-003 达成。  
3. 最后执行 Final Phase 任务，迁移旧日志调用、移除遗留实现并完善文档，使整个日志系统可长期维护。

### 团队并行策略

在多人协作时，可采用如下分工：

1. 共同完成 Phase 1 与 Phase 2（尤其是数据模型与日志门面），确保基础设施稳定。  
2. 之后按用户故事拆分：  
   - 开发者 A：负责 US1（配置与开关），聚焦 `LogSystem` 与配置 UI；  
   - 开发者 B：负责 US2（高信噪比诊断日志），聚焦 `LogFormatter`、`LogWriter`、`LogFileManager` 与相关文档；  
   - 开发者 C：负责 US3（性能与资源控制），聚焦 `LogSampler`、`LogFileManager` 与性能测试。  
3. 整体完成后再由一名维护者执行 Final Phase 的迁移与文档任务，统一风格并补齐交付物。
