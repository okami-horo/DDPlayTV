# 数据模型：001-logging-redesign 日志系统重构与治理

本文件定义本次日志系统重构涉及的核心实体、字段、关系以及关键状态机，用于指导后续实现与测试设计。实体命名使用英文，以便在 Kotlin/Java 代码中直接映射到类或数据类。

## 1. 基础枚举与值对象

### 1.1 `LogLevel`

表示日志级别，遵循 FR-013 至少包含以下枚举值：

- `DEBUG`  
- `INFO`  
- `WARN`  
- `ERROR`

约束与校验：

- 仅允许上述有限集合，禁止自定义任意级别字符串。  
- 默认策略下仅输出 `INFO` 和 `ERROR` 级别到 logcat；只有在统一配置入口显式开启“调试日志”后，才输出 `DEBUG`（FR-013）。  
- 对于未来扩展（如 `TRACE`、`FATAL`），需在配置与 UI 层显式暴露并更新文档。

### 1.2 `LogModule`

表示日志所属的功能模块，用于支持「按模块开关」与过滤。

字段：

- `code: String`：模块唯一标识，如 `core`, `player`, `download`, `user`, `anime`, `local`, `storage`, `network`, `subtitle` 等。  
- `displayName: String`：在配置 UI 中展示的中文名称，如「播放器」「下载」「用户」「本地媒体」。  
- `category: String?`：可选分类，用于将多个模块归入同一大类（如「播放链路」「数据层」「系统集成」）。

约束与校验：

- 所有日志必须归属到已注册的 `LogModule` 集合，禁止使用自由文本 Tag；未识别模块统一映射到 `LogModule.UNKNOWN`。  
- 模块列表由 `common_component` 维护统一枚举或注册表，业务模块不得自行新增字符串模块名。

### 1.3 `LogTag`

用于细分同一模块内部的子区域或上下文，通常映射到类或功能域。

字段：

- `value: String`：短标签，如 `MainActivity`, `PlayerEngine`, `SubtitleRenderer`。  
- `module: LogModule`：所属模块。

约束：

- `value` 长度需有上限（例如 32 字符），防止日志行过宽。  
- 组合键 `(module.code, value)` 应在一台设备内保持稳定，以便过滤与统计。

## 2. 核心运行时实体

### 2.1 `LogEvent`

表示一次具体的日志记录，对应 spec 中的「日志事件」。

主要字段：

- `timestamp: Long`：毫秒时间戳。  
- `level: LogLevel`：日志级别。  
- `module: LogModule`：所属模块。  
- `tag: LogTag?`：可选子标签。  
- `message: String`：人类可读的主消息文本。  
- `context: Map<String, String>`：结构化上下文字段，如 `scene`, `sessionId`, `requestId`, `errorCode`, `userAction` 等。  
- `throwable: Throwable?`：可选异常信息（在落盘时转换为堆栈字符串）。  
- `threadName: String`：产生日志的线程名，用于问题排查。  
- `sequenceId: Long`：单机递增序号，便于跨文件重建顺序。

关系与行为：

- `LogEvent` 在生成后会交给策略引擎评估是否输出到 logcat、写入文件或直接丢弃。  
- 在序列化到文件时，建议使用结构化格式（如 JSON 行或规范的 `key=value` 列表），以便后续离线解析和过滤。

校验规则：

- `message` 不可为空；长度应控制在合理范围（如 2KB 内），过长信息可拆分为多条事件。  
- `context` 的 key/value 应为短文本（例如 key <= 32、value <= 256），避免单条日志过于庞大。  
- `timestamp` 不可早于进程启动时间太多（可允许一定误差），否则视为异常时间源。

推荐上下文字段（用于提升诊断一致性，建议采用 lowerCamelCase）：

- `scene`：业务场景或操作入口，如 `launch`, `playback`, `download_detail`。  
- `errorCode`：可枚举的错误码或状态码，便于快速过滤。  
- `sessionId` / `requestId`：会话或请求链路标识，用于串联多条日志。  
- `action` / `source`：用户触发动作及来源（如 `action=click`, `source=history`).  
- `userId` / `profile`：匿名化的用户/账户标识（如需），避免直接记录敏感信息。  
- 其他字段保持简短、可聚合：优先使用 ID/摘要而非长文本；对于 DEBUG 高频日志，非核心字段超过约 6 个时应按重要性取舍。

### 2.2 `LogPolicy`

对应 spec 中的「日志策略」，用于驱动运行时行为。

主要字段：

- `name: String`：策略名称，如 `default-release`, `debug-session`。  
- `defaultLevel: LogLevel`：全局最小输出级别（例如默认 `INFO`），所有模块共享该级别。  
- `enableDebugFile: Boolean`：是否启用本地调试日志写入（影响是否生成/追加 `debug.log`）。  
- `samplingRules: List<SamplingRule>`：每个模块或事件类别的采样/限流配置（内部策略，不通过用户配置单独设置模块级别）。  
- `exportable: Boolean`：是否允许将该策略下生成的日志导出为日志包。

`SamplingRule` 示例字段：

- `targetModule: LogModule`  
- `minLevel: LogLevel`  
- `sampleRate: Double`（0.0~1.0）  
- `maxEventsPerMinute: Int?`

约束与校验：

- 默认策略必须满足 FR-013：仅输出 `INFO` / `ERROR`，`DEBUG` 默认关闭且不写文件（通过全局 `defaultLevel` 与 `enableDebugFile` 组合实现）。  
- 当前版本中不支持通过策略为单个模块配置独立级别，所有模块共享同一全局级别，`LogModule` 仅用于打标签与过滤。  
- 当 `enableDebugFile = false` 时，不得产生任何本地持久化文件写入（FR-014）。  
- 策略变更后应即时生效，对新产生的 `LogEvent` 进行过滤；无需回溯历史事件。

### 2.3 `LogRuntimeState`

表示当前运行时的日志状态，用于调试 UI 与导出。

字段：

- `activePolicy: LogPolicy`：当前生效策略。  
- `policySource: PolicySource`：策略来源（默认配置 / 用户临时覆盖 / 远程开关等，本项目仅使用前两类）。  
- `debugSessionEnabled: Boolean`：当前是否处于调试会话（开启本地文件写入）。  
- `lastPolicyUpdateTime: Long`：最近一次策略变更时间。  
- `recentErrors: List<LogEvent>`：最近若干严重错误事件，用于在 UI 上快速展示。

`PolicySource` 示例：

- `DEFAULT`：应用内预置的默认策略。  
- `USER_OVERRIDE`：用户在统一配置入口临时修改。  
- `REMOTE`：预留，当前项目不使用。

## 3. 本地文件与导出相关实体

### 3.1 `LogFileMeta`

表示单个本地日志文件的元信息（`debug.log` / `debug_old.log`）。

字段：

- `fileName: String`：文件名，仅允许 `debug.log` 或 `debug_old.log`。  
- `path: String`：绝对路径。  
- `sizeBytes: Long`：文件大小。  
- `lastModified: Long`：最近修改时间。  
- `sessionStartTime: Long?`：对应会话起始时间（可选，便于排查）。  
- `readable: Boolean`：当前是否可读。

约束（对应 FR-015~FR-016）：

- 仅允许存在 `debug.log` 与 `debug_old.log` 两个文件；任何其他日志文件应视为历史遗留，需在迁移逻辑中清理或忽略。  
- 在新会话冷启动时：  
  - 如 `debug.log` 存在，应根据策略将其内容合并到 `debug_old.log`（必要时裁剪到上限）。  
  - 随后创建/清空新的 `debug.log` 作为当前会话文件。  
- 在检测到磁盘空间不足或连续写入失败时，应将对应标记写入运行时状态并停止后续写入。

### 3.2 `LogPackage`

对应 spec 中的「日志包 / 采集快照」，用于导出与分享。

字段：

- `id: String`：日志包 ID（如基于时间戳与随机数生成）。  
- `createdAt: Long`：创建时间。  
- `files: List<LogFileMeta>`：包含的日志文件（通常是 `debug.log` 与 `debug_old.log` 的一个子集或完整集合）。  
- `appVersion: String`：应用版本号。  
- `buildType: String`：构建类型（debug/release）。  
- `deviceInfo: Map<String, String>`：设备信息（机型、系统版本等）。  
- `environment: String`：环境标记，如 `DEV` / `TEST` / `PROD`（对于本开源项目可简单标记）。  
- `notes: String?`：可选备注，例如「用户反馈：播放卡顿」。

约束：

- 日志包仅在用户明确发起导出时生成，导出过程失败不得影响日志采集（FR-004）。  
- 在默认策略下不自动生成任何持久化日志文件，因此也不会自动生成日志包；只有当用户显式开启调试日志并导出时才会产生。

### 3.3 `LogExportRequest` 与 `LogExportResult`

用于描述一次导出操作。

`LogExportRequest` 字段：

- `includeOldLog: Boolean`：是否包含 `debug_old.log`。  
- `compress: Boolean`：是否压缩为单一压缩文件（例如 zip）。  
- `stripDebugLinesBelowLevel: LogLevel?`：导出时可选的级别过滤（例如只导出 `WARN` 及以上）。

`LogExportResult` 字段：

- `success: Boolean`  
- `package: LogPackage?`：成功时生成的日志包信息。  
- `errorMessage: String?`：失败原因（如无可用日志文件、磁盘空间不足等）。

## 4. 状态与生命周期

### 4.1 调试日志开关状态机

状态：

- `OFF`：默认状态，仅输出到 logcat，不写本地文件。  
- `ON_CURRENT_SESSION`：当前会话开启调试日志，写入 `debug.log`，并根据策略决定是否使用 `debug_old.log`。  
- `DISABLED_DUE_TO_ERROR`：由于磁盘不足或连续写入错误，自动熔断本地写入。

状态迁移规则（简化）：

- `OFF` → `ON_CURRENT_SESSION`：用户在统一配置入口开启调试日志开关。  
- `ON_CURRENT_SESSION` → `OFF`：用户关闭开关或应用主动根据策略结束调试会话（例如长时间未使用）。  
- `ON_CURRENT_SESSION` → `DISABLED_DUE_TO_ERROR`：检测到存储不足或连续写入失败。  
- `DISABLED_DUE_TO_ERROR` → `OFF`：用户关闭调试开关或应用重新启动后恢复默认状态（视具体设计决定是否自动恢复）。

### 4.2 策略变更流程

1. 用户在统一配置入口选择目标模块与级别，并选择是否开启调试日志写入。  
2. UI 层构造新的 `LogPolicy`，写入 MMKV。  
3. 日志门面监听到策略变更事件，更新 `LogRuntimeState.activePolicy`。  
4. 对后续产生的 `LogEvent`，策略引擎根据新的 `LogPolicy` 决定是否输出到 logcat / 写入 `debug.log` / 丢弃。  
5. 在导出时根据当前或指定策略对文件内容进行过滤与打包。

## 5. 与实现的映射建议

- `LogLevel`、`LogModule`、`LogTag`、`LogEvent`、`LogPolicy`、`LogRuntimeState`、`LogFileMeta`、`LogPackage` 等实体建议使用 Kotlin `data class` 或 `enum class` 实现，集中放在 `common_component.log.model` 包下。  
- 文件相关实体与逻辑（例如从 `File` 推导 `LogFileMeta`）应通过单一「文件适配层」封装，便于在测试中注入虚拟文件系统。  
- 与 UI 相关的简化视图（如在配置页展示的 `UiLogModule`、`UiLogPolicy`）可在 presentation 层单独定义，通过 mapper 在数据模型与 UI 模型之间转换。
