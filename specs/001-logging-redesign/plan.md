# Implementation Plan: 001-logging-redesign 日志系统重构与治理

**Branch**: `001-logging-redesign` | **Date**: 2025-12-05 | **Spec**: [`/specs/001-logging-redesign/spec.md`](/specs/001-logging-redesign/spec.md)
**Input**: Feature specification from `/specs/001-logging-redesign/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

本特性针对当前基于 `DDLog` + `AppLogger` 的日志方案进行系统性重构，目标是：

- 提供统一的日志门面与策略模型（如 `LogEvent` / `LogPolicy`），通过全局日志级别与调试日志开关可配置地控制日志输出，满足 FR-001~FR-006 所描述的可控性与可观测性要求。
- 将日志采集与查看解耦，默认仅通过 logcat 输出有限信息；只有在统一入口显式开启“调试日志”时，才异步写入本地 `debug.log` / `debug_old.log`，并通过简单轮转与固定日志目录支持问题排查（开发者直接从日志目录获取文件，满足 FR-007、FR-014~FR-016）。
- 在 Android 端引入结构化日志格式与噪声治理（采样 / 限流）能力，在保证默认体验无感（SC-002、SC-003）的前提下，为典型调试场景提供高信噪比诊断能力（SC-001、SC-004）。

## Technical Context

**Language/Version**: Kotlin 1.9.25 + Java 8（Android）  
**Primary Dependencies**: Android `Log` / logcat 管道、`common_component` 中的 `DDLog` 与重构后的日志门面 / 写入实现、MMKV 配置存储；Phase 1 不再引入新的第三方日志库，相关实践仅作为设计参考  
**Storage**: 应用内部存储目录下的日志文件（仅 `debug.log` / `debug_old.log`，双文件轮转，每个文件默认上限约 5MB，总体约 10MB），以及 MMKV 中持久化的日志策略配置  
**Testing**: JUnit JVM 单元测试（策略与格式化）、可选 Robolectric 测试（目录解析与基础文件操作）、Android Instrumentation 测试（真实设备上的写入、轮转、磁盘不足行为）  
**Target Platform**: Android 移动端应用，minSdk 21、targetSdk 35  
**Project Type**: mobile（多模块 Android 应用：`app` + 各 `*_component` 功能模块）  
**Performance Goals**: 默认日志策略下，对冷启动耗时的增量 < 5%，关键交互卡顿率与关闭日志基线无统计学显著差异；开启调试日志时，`debug.log` / `debug_old.log` 总大小默认控制在约 10MB 以内，并可通过配置扩展  
**Constraints**: 不引入任何线上日志或远程上传能力（FR-011）；默认未开启 DEBUG 时不写入本地持久化日志，仅使用 logcat（FR-013~FR-014）；仅允许 `debug.log` / `debug_old.log` 双文件轮转且在存储不足时必须立即停止写入（FR-015~FR-016）  
**Scale/Scope**: 影响全局日志调用（当前 `DDLog` 使用点约 500+ 处）、`common_component` 日志实现、日志配置 UI，以及日志文件生成与获取方式；Phase 1 仅保证项目内 Kotlin/Java 调用统一门面，同时在门面层预留针对第三方库 / native 日志的可选桥接接口

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Gate-1: 宪章文件有效性**

- 观察：`/workspace/DanDanPlayForAndroid/.specify/memory/constitution.md` 目前仍为模板，占位符为 `[PRINCIPLE_*]` 等，未声明具体强制性原则。  
- 结论：在本特性范围内视为“未定义宪章”，不额外引入新的技术或流程约束；仍需遵守仓库级约定（AGENTS.md）与功能规范。  
- 状态：PASS（记录为治理待完善项，但不阻塞本次规划）。

**Gate-2: 日志用途与数据边界**

- 约束来源：FR-011 以及仓库安全与配置指引。  
- 检查：本规划仅重构本地日志体系，不设计或实现任何线上日志、远程上传或埋点通道；是否记录敏感字段由调用方自行控制，符合“本项目不提供线上日志能力”的前提。  
- 状态：PASS（无额外合规风险）。

**Gate-3: 复杂度与可维护性**

- 约束来源：宪章示例中的“简化优先 / 易测试”精神。  
- 检查：计划通过单一日志门面 + 策略模型 + 单线程后台写入执行器实现，不新增子项目或跨进程组件，复杂度集中在 `common_component` 的 `log` 包。  
- 状态：PASS，当前无需在 “Complexity Tracking” 中登记额外违规项；如 Phase 1 后发现需引入新子模块或 DSL，将重新审视并补充。

> Phase 1 设计完成后复查：当前数据模型、契约与 Quickstart 中的方案仍符合上述 Gate-1~3 的约束，未新增需要登记的复杂度违规项。

## 日志级别划分准则（针对现有日志的重分级）

> 本小节用于指导现有 `DDLog` / 直接 `Log` / 其他 Logger 调用在迁移到统一门面时如何重新划分级别，避免继续产生高噪声日志。后续在 `tasks.md` 中会按模块梳理调用点并统一使用全局级别策略。

统一采用 `DEBUG / INFO / WARN / ERROR` 四个级别，并遵守以下场景划分：

- **DEBUG（调试细节）**  
  - 高频、细粒度、对最终用户无直接价值的调试信息：  
    - 视图焦点变化、按键事件、列表滚动位置、临时 UI 状态切换等。  
    - 周期性心跳、轮询、缓存命中/未命中等细节。  
    - 低风险网络请求的请求体/响应体摘要（需要时可在调试会话中开启）。  
  - 开销较大的日志（如大对象序列化）必须只在 DEBUG 且当前策略允许时输出。  
  - 默认策略下不输出，也不写入本地文件，只在显式开启“调试日志”时生效。

- **INFO（关键业务流程）**  
  - 标记用户可感知的关键流程节点，用于重建正常链路：  
    - 启动流程：应用启动、关键组件完成初始化、播放器准备就绪等。  
    - 重要操作：开始播放/停止播放、添加/删除下载任务、账号登录/退出等。  
    - 重要配置变更：切换媒体内核、开启/关闭字幕 GPU 渲染、打开/关闭调试日志等。  
  - INFO 应尽量少且有“里程碑”性质，不记录高频细节，不用于异常/错误。  
  - 默认策略下会输出到 logcat，并在开启调试日志时写入 `debug.log`。

- **WARN（异常但可恢复）**  
  - 发生了异常或不期望情况，但系统可通过降级/重试继续工作：  
    - 单次网络请求失败但存在重试；某些可选资源加载失败但有后备方案。  
    - 配置不合法但系统使用默认值继续运行。  
    - 播放过程中单帧/短时卡顿，但整体播放未中断。  
  - WARN 需要携带足够的结构化上下文（模块、scene、错误码、重试次数等）。  
  - 默认策略下会输出，便于快速发现异常模式，但数量应远少于 DEBUG/INFO。

- **ERROR（故障与用户可感知失败）**  
  - 导致功能目标明显失败或用户可感知的严重问题：  
    - 播放失败、解码器初始化失败、关键接口调用连续失败且放弃。  
    - 本地数据读写失败导致页面无法正常展示。  
    - 日志系统自身的严重错误（例如写盘连续失败触发熔断）。  
  - ERROR 必须包含：模块、操作类型、错误码/异常类型、关键上下文（如 url、文件路径摘要、会话/请求 ID 等），以便单条日志即可定位问题大概范围。  
  - 默认策略下始终输出，并在允许写文件时优先写入。

**迁移原则（对现有日志的级别重分配）：**

- 现有使用 INFO/WARN 但属于“纯 UI 噪声”或高频无关日志，应统一降级到 DEBUG，或在新策略中按模块/场景采样（采样规则为内部策略，不通过用户配置单独调节模块级别）。  
- 用 ERROR 记录“可恢复小问题”的日志（如单次重试失败）应降级为 WARN，并确保真正失败时有对应的 ERROR。  
- 大块文本/堆栈只在必要时输出，避免在 INFO/WARN 中长篇堆栈；对于 DEBUG 级别的长日志，应结合调试开关与采样控制输出频率。  
- 对于缺少模块维度的旧日志，在迁移到新门面时必须补充 `LogModule` 与关键上下文字段，避免继续使用“纯文本 Tag + 混合 message”的方式。

## Project Structure

### Documentation (this feature)

```text
specs/001-logging-redesign/
├── spec.md              # 特性规范（输入）
├── plan.md              # 本文件（/speckit.plan 输出）
├── research.md          # Phase 0：调研与决策记录
├── data-model.md        # Phase 1：数据与状态模型
├── quickstart.md        # Phase 1：使用与接入指引
├── contracts/           # Phase 1：API / 交互契约（OpenAPI / GraphQL / 本地接口）
└── tasks.md             # Phase 2：实现任务拆解（/speckit.tasks 输出，当前不生成）
```

### Source Code (repository root)

```text
app/
└── src/
    ├── main/java/com/xyoye/dandanplay/      # 应用入口与全局初始化（含日志系统初始化与配置入口聚合）
    └── main/res/                            # 日志配置页等 UI（日志文件由开发者从固定日志目录直接获取）

common_component/
└── src/main/java/com/xyoye/common_component/
    ├── log/                                 # 新的日志门面、策略引擎、文件写入与日志目录管理实现
    ├── config/                              # DevelopConfig 等配置项，包含日志开关默认值
    └── utils/                               # `DDLog` 调用层兼容封装

anime_component/
player_component/
local_component/
storage_component/
user_component/
└── src/main/java/...                        # 各业务模块仅依赖统一日志门面，不直接使用 android.util.Log

common_component/src/test/java/              # 日志策略与格式化相关的 JVM 单元测试
common_component/src/androidTest/java/       # 日志写入与空间不足行为的集成测试（按需补充）
app/src/androidTest/java/                    # 日志配置界面的 UI 测试
```

**Structure Decision**: 本特性作为全局基础能力，核心实现集中在 `common_component` 的 `log` 包中，通过 `DDLog` 兼容现有调用点；日志配置能力位于 `app` 模块的设置 / 调试页面，日志“导出”依赖开发者从固定日志目录直接获取 `debug.log` / `debug_old.log` 等文件，各业务模块只依赖统一日志门面，不再直接访问 `android.util.Log`。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |

## Phase 2 Planning（Foundational 实现概览）

> 详细的任务清单将由 `/speckit.tasks` 命令生成到 `specs/001-logging-redesign/tasks.md` 中。  
> 本小节仅定义 Phase 2（阻塞性前置工作）的范围和优先级，用于指导后续任务拆解。

**Phase 2: Foundational（所有用户故事的共同前置条件）**

- 在 `common_component` 中落地基础日志模型与门面骨架：  
  - 定义 `LogLevel`、`LogModule`、`LogEvent`、`LogPolicy`、`LogRuntimeState` 等数据类与枚举。  
  - 提供最小可用的 `LogFacade` 接口，将现有 `DDLog` 内部重定向到新门面。  
  - 保留现有行为（默认 enable 状态）不变，以避免在基础设施完成前引入行为回归。
- 在 `common_component.log` 中实现双文件管理与 I/O 基础设施：  
  - 提供 `LogFileMeta` 与文件管理器接口，约束只生成 `debug.log` / `debug_old.log` 两个文件。  
  - 使用单线程执行器完成异步写入与简单轮转逻辑，但暂不启用调试开关控制。  
  - 预留磁盘不足与写入失败的错误上报路径，后续由策略层决定如何熔断。
- 在 `app` 模块中创建日志配置入口的初始骨架：  
  - 新建「日志配置」页面（或在现有设置页面中增加一个分组），仅展示当前策略的只读视图。  
  - 为后续用户故事中的交互与 UI 细节预留 ViewModel 与导航路径。
- 在 `common_component` 与 `app` 中配置最小测试基础设施：  
  - 为日志模型与策略编写首批 JVM 单元测试（验证基本决策与序列化逻辑）。  
  - 为文件创建与轮转流程设计至少一个 Instrumentation 测试占位（可先标记为 TODO）。  
  - 将新的日志模块纳入现有 CI 流程（与仓库既有测试命令保持一致）。

**Checkpoint（Phase 2 完成条件）**

- 新的日志数据模型与门面可在不改变既有行为的前提下通过单元测试。  
- 双文件日志写入与轮转骨架可在受控场景下创建并更新 `debug.log` / `debug_old.log`，并将文件写入到统一的本地日志目录。  
- 应用内存在可访问的「日志配置」入口，虽交互简化但可贯通基础配置流程；日志文件可通过 adb / 文件管理器在固定目录中直接获取。  
- 之后即可按用户故事（统一开关、高信噪比诊断、低开销采集）拆分独立实现任务，并写入 `tasks.md`。
