# Phase 0 调研与决策：001-logging-redesign 日志系统重构与治理

本文件汇总本特性在实施前的关键技术澄清点、依赖与集成调研任务，以及基于调研结果达成的设计决策。所有需要澄清的条目在此得到解析，并在后续更新回 `plan.md` 的 Technical Context 中。

## Research Tasks

### 已澄清事项

1. 是否需要额外引入第三方日志库（如 Timber / tinylog），还是完全基于现有 `DDLog` / `AppLogger` 自研统一日志门面。  
   - Task: 「Research 是否在 Android 本地调试场景下引入通用日志库，还是基于现有组件构建项目内统一日志门面与策略系统。」  
   - Status: 已在 Decision 1 中确定采用自研门面，不新增三方依赖。
2. 日志 I/O 与低存储空间行为主要通过哪一类测试覆盖（纯 JVM 模拟还是设备端集成测试）。  
   - Task: 「Research 针对日志文件写入、轮转与磁盘不足等行为，如何在 JUnit / Robolectric / Instrumentation 各层划分测试职责。」  
   - Status: 已在 Decision 2 中明确分层测试方案。
3. 单次调试会话日志文件大小控制目标（例如 `debug.log` + `debug_old.log` 总大小上限）。  
   - Task: 「Research Android 本地调试日志常见的文件大小上限配置（对比 MXLogger、jlog 等实践），并结合本项目使用场景给出推荐上限。」  
   - Status: 已在 Decision 3 中确定「双文件各约 5MB」方案。
4. 是否需要为第三方库或 native 层日志提供统一透传能力。  
   - Task: 「Research 在多模块 Android 工程中，将三方库 / native 层日志统一汇聚到自定义日志门面的常见模式与代价。」  
   - Status: 已在 Decision 4 中明确 Phase 1 仅预留桥接接口，不强制接管。

### Dependencies → Best Practices

- Android `Log` / logcat  
  - Task: 「Find best practices for using Android `Log`/logcat for debug logging without impacting performance (如使用统一 Tag、按级别裁剪输出、用 `Log.isLoggable` 守卫昂贵日志等)。」

- 本地文件 I/O（内部存储调试日志）  
  - Task: 「Find best practices for writing structured debug logs to internal storage on Android, with background threads and rolling files。」

- 配置与状态存储（MMKV）  
  - Task: 「Find best practices for storing feature flags / logging policies in MMKV for Android apps。」

- 测试框架（JUnit / Robolectric / Instrumentation）  
  - Task: 「Find patterns for testing logging frameworks in Android: what to unit test vs instrumentation test。」

### Integrations → Patterns

- 与现有 `DDLog` / `AppLogger` 的兼容与迁移  
  - Task: 「Find patterns for migrating from an existing logging facade to a new configuration-driven logger in a multi-module Android app without breaking call sites。」

- 与日志文件获取 / 分享方式的集成  
  - Task: 「Research patterns for packaging log files (当前仅 `debug.log` / `debug_old.log`) into a shareable bundle and exposing it via a settings/debug screen。」

- 与潜在第三方 / native 日志的透传  
  - Task: 「Find patterns for exposing a lightweight bridge API that third-party/native code can call into, while keeping the core logger independent of those dependencies。」

## Decisions

### 1. 日志门面与依赖选择

- Decision: 本次重构 **不引入新的第三方日志库**，继续使用 Android `Log`/logcat 作为底层输出通道，在 `common_component` 中基于现有 `DDLog` / `AppLogger` 重构出统一的日志门面与策略系统（如 `LogEvent`、`LogPolicy`、`LogWriter` 等），由门面负责：级别裁剪、模块开关、策略匹配与结构化格式化，再由底层通道分别写入 logcat 与本地文件。  
- Rationale:  
  - 项目已存在 `DDLog` + `AppLogger` 封装，大量调用点依赖该接口，引入新库将增加迁移成本和心智负担。  
  - 典型第三方库（如 Timber / tinylog / MXLogger）提供的远程上传、多通道聚合等特性并非本特性需求，且可能与「不交付线上日志能力」的约束冲突。  
  - 自研门面可以更精细地贴合 FR-011~FR-016，如只允许 `debug.log` / `debug_old.log` 双文件轮转、在未开启 DEBUG 时完全禁止本地写入等，这些约束在通用库中往往需要额外适配。  
  - 通过自研门面依旧可以借鉴社区最佳实践（如统一 Tag、格式化、抽象接口便于测试），在控制复杂度的同时保证可维护性。  
- Alternatives considered:  
  - **直接引入 Timber 作为主日志门面**：优点是 API 简洁、社区成熟；缺点是无法直接满足双文件轮转与「默认不写本地文件」等刚性需求，需要二次封装，实际上与自研门面复杂度接近。  
  - **引入 tinylog / MXLogger 等文件日志库**：优点是内置异步写入和轮转策略；缺点是配置模型与本规范差异较大（例如多文件、多级别碎片化），且通常默认启用文件写入，与 FR-014 的默认策略相悖。  
  - **继续使用当前 `AppLogger` 设计，仅做小幅调整**：当前实现基于时间戳命名多文件并做 N 份备份，难以收敛为只有 `debug.log` / `debug_old.log` 的双文件模型，同时默认始终写入文件，不满足默认不写入的要求，因此被否决。

### 2. 日志 I/O 与低存储空间行为的测试策略

- Decision: 将测试策略划分为三层：  
  1) **JVM 单元测试（common_component/src/test/java）**：覆盖日志策略决策（level、module、采样/限流）、结构化字段拼装与格式化、日志文件路径/命名规则等纯函数或可注入依赖的逻辑。  
  2) **Robolectric 测试（可选）**：在 JVM 环境中验证与 Android API 的轻量集成，如 `Context` 下日志目录解析、基础文件创建与清理逻辑。  
  3) **Android Instrumentation 测试（src/androidTest/java）**：验证真实设备或模拟器上的文件写入、双文件轮转、磁盘空间不足或写入失败时的降级行为（立即停止写入、保留 logcat 输出），以及在不依赖应用内导出入口的前提下从日志目录获取并分享日志文件的流程。  
- Rationale:  
  - 将策略与格式化逻辑剥离出去，可以在 JUnit 层快速迭代并保障覆盖率，避免过多依赖慢速的设备测试。  
  - 磁盘不足、I/O 异常等行为与具体设备实现紧密相关，仅在 JVM 中模拟异常很难保证与真实环境一致，通过 Instrumentation 测试更能验证实际行为。  
  - 这种分层策略符合「在最便宜的层尽早发现问题」的原则，同时与仓库现有的测试布局（test / androidTest）保持一致。  
- Alternatives considered:  
  - **全部通过 Instrumentation 测试完成验证**：虽然最贴近真实环境，但会显著拉长反馈周期，且难以针对大量策略组合做穷举测试，不利于维护。  
  - **仅使用 JVM 单元测试，通过模拟文件系统与异常覆盖 I/O 行为**：可以覆盖部分逻辑，但难以准确模拟 Android 在磁盘不足、权限变化等场景下的行为，风险较高。  
  - **仅依赖人工手动验证日志文件**：无法形成可回归的测试网，违背项目测试守则，已明确否决。

### 3. 调试日志文件大小上限（`debug.log` / `debug_old.log`）

- Decision: 采用 **双文件各 5MB、总计约 10MB** 的默认上限策略，即单个 `debug.log` 或 `debug_old.log` 超过约 5MB 时触发裁剪或轮转，总体控制在 ~10MB 级别；并在实现中为未来通过配置调整该上限预留扩展点。  
- Rationale:  
  - 参考 MXLogger 等日志库的默认配置（通常为 5MB~10MB 级别）以及手机端典型存储情况，该级别的日志体量足以覆盖一次调试会话或多数问题链路，同时对普通用户的存储压力可控。  
  - 结合本项目假设：默认情况下不会生成本地持久化日志文件（仅在显式开启调试日志时写入），且启用调试的多为开发/测试场景，将日志体量控制在 10MB 左右可以在「可诊断性」与「不占用过多空间」之间取得平衡。  
  - 相比现有 `AppLogger` 通过时间戳命名多个文件并保留 5 份备份的方案，双 5MB 文件模型更符合 FR-015 对文件命名与数量的严格约束。  
- Alternatives considered:  
  - **延续 2MB 单文件上限**：可以进一步降低空间占用，但在复杂问题场景下可能无法保留足够的历史上下文，影响诊断；考虑到调试场景对空间的耐受度，2MB 较为保守。  
  - **提高到 20MB+ 级别**：可以承载更多历史日志，但在部分低端设备或长时间调试场景下可能产生明显的空间占用，与「不影响普通用户存储」的目标冲突。  
  - **完全不限制日志文件大小，仅依赖用户手动清理**：不符合「默认应对普通用户无感」的设计原则，且容易出现日志文件持续膨胀的风险，已拒绝。

### 4. 第三方库 / native 层日志的统一透传

- Decision: Phase 1 仅保证 **本项目代码（Kotlin/Java 层）通过统一日志门面输出**，不强制对所有第三方库或 native 层日志做自动接管；同时在门面层预留简洁的桥接 API（例如可以接收预格式化字符串或简单级别映射），方便后续按需在关键库上逐步接入。  
- Rationale:  
  - 当前 spec 的核心目标集中在本地日志体系的统一与可控性，而非对所有外部依赖的日志进行“全盘接管”；对三方库强行注入或 hook 反而可能引入额外风险。  
  - native 层与部分三方库往往已经有成熟的日志实现（如 ExoPlayer、libass 等），在 Phase 1 就完全重写或重定向这些日志成本过高、收益有限。  
  - 通过为门面提供可选桥接接口（如 `logThirdParty(module, level, message)`），可以在不增加当前复杂度的前提下，为后续逐步提升统一性提供路径。  
- Alternatives considered:  
  - **在 Phase 1 中强制所有三方库 / native 日志通过统一门面输出**：需要对大量依赖进行适配或 hook，风险与工作量远超当前特性范围，与「先解决核心痛点」的原则不符。  
  - **完全忽略三方库 / native 日志，不提供任何桥接能力**：虽然实现最简单，但会在未来想要统一关键依赖日志时缺乏官方扩展点，因此选择预留但暂不强制使用。

## 日志质量对比实验（支持 SC-001）

- 目的：验证重构后的本地日志在同一问题样本上是否降低噪声、提升定位效率。  
- 场景选择：至少覆盖「播放失败」与「下载重试」两类可复现场景，确保包含 INFO/WARN/ERROR 混合输出。  
- 实验步骤：  
  1. 使用旧日志路径（关闭调试日志或使用原始 AppLogger 输出）复现问题，保存 logcat 摘要与时间轴。  
  2. 切换到新日志体系，开启调试日志（默认级别 INFO/ERROR，再按需升到 DEBUG），从固定日志目录拉取 `debug.log` / `debug_old.log`。  
  3. 对比同一时间窗口的日志行数、`ctx_dropped` 出现次数、关键字段覆盖率（scene/errorCode/sessionId/requestId）。  
  4. 记录从收到日志到定位根因的耗时与需要阅读的日志行数，观察是否减少重复/无意义 DEBUG 行。  
- 评估指标：  
  - 噪声占比：新增格式下与旧日志相比的日志行数、重复段落比例。  
  - 可诊断性：是否能依赖 `seq` + `ctx_*` 在单份日志内重建链路；是否需要额外请求设备截图/说明。  
  - 结论记录：将实验结果与改进建议回填到 `quickstart.md` 或 `plan.md`，作为后续优化输入。
