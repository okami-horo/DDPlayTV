# Quickstart：001-logging-redesign 日志系统重构

本指南面向在本项目中编写或维护代码的开发者，说明如何在新的日志体系下记录日志、配置策略以及获取/分析本地日志文件。本文假设读者对 Kotlin / Android 开发已有基本了解。

> 注意：本特性仅重构 **本地** 日志体系，不引入任何线上日志或远程上传能力。

## 1. 基本概念回顾

- **LogLevel**：日志级别，当前支持 `DEBUG` / `INFO` / `WARN` / `ERROR`。默认策略只输出 `INFO` 和 `ERROR`，`DEBUG` 需要通过配置显式开启。  
- **LogModule**：日志所属模块，用于在日志中打标签并按模块过滤（仅用于查看/分析），例如 `PLAYER`、`ANIME`、`LOCAL`、`STORAGE`、`USER` 等。  
- **LogPolicy**：日志策略，描述全局默认级别、是否写入本地文件等。  
- **LogEvent**：单条日志事件，包含时间、模块、级别、消息与结构化上下文字段。  
- **LogPackage**：一次导出的日志包，一般包含当前会话 `debug.log` 与上一会话 `debug_old.log`，外加必要的版本与设备信息。

在实现层面，这些实体将集中定义在 `common_component.log.model` 包中，并通过统一的日志门面对外暴露。

## 2. 初始化与入口配置

### 2.1 Application 中初始化日志系统

在 `app` 模块的 `Application` 初始化逻辑中，应在其它依赖日志的组件之前初始化日志系统（伪代码）：

```kotlin
class DanDanPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化日志文件目录与运行时状态
        LogSystem.init(
            context = this,
            defaultPolicy = LogPolicyDefaults.defaultReleasePolicy()
        )

        // 2. 从 MMKV 读取上次保存的策略（如有），与默认策略合并
        LogSystem.loadPolicyFromStorage()

        // 3. 根据当前策略决定是否开启调试日志会话
        LogSystem.ensureDebugSession()
    }
}
```

实现时可以基于现有的 `AppLogger.init(context)` 与 `DDLog` 进行封装与迁移，但对外推荐仅通过新的 `LogSystem` / `LogFacade` 接口使用。

### 2.2 统一的「日志配置」入口

- 在设置或调试相关页面中提供「日志配置」入口，例如：  
  `设置 -> 调试工具 -> 日志配置`。  
- 该入口应允许用户：  
  - 查看当前策略（全局默认级别、是否写入本地文件）。  
  - 调整全局日志级别（例如从 INFO 调整为 WARN 或 DEBUG）。  
  - 开启/关闭调试日志写入（控制是否生成 `debug.log` / `debug_old.log`）。  
- 用户的选择通过 ViewModel 写入 `LogPolicy` 并持久化到 MMKV，日志系统监听策略变更并即时生效。

## 3. 在代码中记录日志

### 3.1 推荐的日志接口（新代码）

新代码推荐使用统一的日志门面，例如：

```kotlin
LogFacade.i(
    module = LogModule.PLAYER,
    tag = "PlayerEngine",
    message = "start play: $url",
    context = mapOf(
        "scene" to "user_click",
        "source" to "history"
    )
)
```

或在 Kotlin 中封装扩展函数：

```kotlin
fun PlayerViewModel.logDebug(message: String, context: Map<String, String> = emptyMap()) {
    LogFacade.d(
        module = LogModule.PLAYER,
        tag = "PlayerViewModel",
        message = message,
        context = context
    )
}
```

关键点：

- 每条日志都应携带正确的 `LogModule`，便于在查看日志文件或 logcat 时按模块过滤（标签用途，不再单独配置模块级别）。  
- 重要操作或异常建议补充结构化上下文字段（如 `scene`、`errorCode`、`sessionId` 等）。  
- 对于开销较大的日志（拼字符串、序列化对象等），应由门面或调用方使用一个简单的条件判断（例如基于当前全局级别判断是否需要输出）来避免不必要的计算。

### 3.2 兼容旧的 `DDLog` 调用

现有代码大量使用 `DDLog.i/w/e`，在重构阶段可以通过以下方式过渡：

- 在 `DDLog` 内部改为调用新的日志门面，并为旧的 API 配置合理的默认模块（例如默认归为 `LogModule.CORE` 或根据包名推断模块）。  
- 新增 API 时优先使用新的 `LogFacade` 接口，并逐步将关键路径上的旧调用替换为显式传入 `LogModule` 的形式。  
- 在完成全局迁移后，可以考虑对旧 API 标记 `@Deprecated` 并最终移除。

## 4. 启用调试日志与获取日志文件

### 4.1 启用调试日志（写入 debug.log）

在调试页面中，通过勾选「启用调试日志」或类似开关，并选择全局日志级别，建立如下关系：

1. UI 基于开关状态和全局级别构造新的 `LogPolicy`：  
   - 打开调试时：`enableDebugFile = true`，并允许用户将全局最小级别调整为 DEBUG（如有需要）。  
   - 关闭调试时：`enableDebugFile = false`，全局级别通常恢复为默认策略（例如 INFO）。  
2. 将策略写入 MMKV，并通知 `LogSystem` 更新运行时策略。  
3. `LogSystem` 根据策略开启或更新调试会话，在内部开始或停止向 `debug.log` 写入符合策略的日志行。

关闭开关时：

- 再次更新策略，将 `enableDebugFile` 置为 `false`；  
- 日志系统立即停止对本地文件的写入，仅保留 logcat 输出，必要时也可以将全局级别恢复为默认值。

### 4.2 从设备获取日志文件

本次重构不提供应用内的「导出日志」入口，开发者或支持 / 测试人员可以直接从本地日志目录获取 `debug.log` / `debug_old.log` 进行分析。

典型做法示例：

1. **确定日志目录**  
   - 在实现中由 `LogFileManager` 统一定义日志目录（例如 `context.filesDir` 下的 `logs/` 子目录），只在该目录下创建 `debug.log` 与 `debug_old.log`。

2. **通过 adb 获取日志文件**  

   ```bash
   # 示例：从设备内部存储拉取日志目录
   adb shell ls /data/data/<package-name>/files/logs
   adb pull /data/data/<package-name>/files/logs ./logs_backup
   ```

   将 `<package-name>` 替换为实际应用包名（如 `com.xyoye.dandanplay`）。

3. **线下打包与分析**  
   - 如有需要，可在开发机上将 `debug.log` / `debug_old.log` 压缩为 zip 后通过 IM / 邮件等方式在团队内部流转。  
   - 日志行格式由 `LogFormatter` 保证包含时间、模块、级别和关键上下文字段，便于脚本或工具进一步筛选。

### 4.3 阅读 debug.log / debug_old.log 的要点

- 日志行格式示例：  
  `time=2025-01-02T03:04:05.678Z level=ERROR module=player tag=player:Renderer thread=RenderThread seq=42 ctx_scene=playback ctx_errorCode=E001 ctx_sessionId=sess-9 ctx_requestId=req-88 context={detail=line1 line2,extraA=valueA} throwable=java.lang.IllegalStateException: boom msg="render failed"`
- 字段含义速查：  
  - `time` / `level` / `module`：时间戳（UTC）、级别、模块标签，用于排序与过滤。  
  - `tag` / `thread` / `seq`：细分来源与线程信息，`seq` 可跨文件串联顺序。  
  - `ctx_*`：高信噪上下文字段，固定包含 `scene` / `errorCode` / `sessionId` / `requestId`（如存在）；在 DEBUG 级别下仍会保留这些字段。  
  - `context={...}`：其余上下文字段按键名排序输出；DEBUG 级别默认最多保留 6 个非核心字段，若有裁剪会出现 `ctx_dropped=<N>`。  
  - `throwable=` / `msg=`：异常摘要与主消息，均经过换行清洗与长度裁剪，便于单行解析。
- 过滤建议：  
  - 定位特定场景：`grep "ctx_scene=playback" debug.log`。  
  - 聚焦错误链路：结合 `errorCode` + `seq`，例如 `grep "ctx_errorCode=E001" debug.log | sort -t '=' -k7`.  
  - 判断是否有噪声裁剪：搜索 `ctx_dropped` 判断是否需要在复现时提高日志级别或缩小操作范围。

## 5. 性能与空间占用验证（User Story 3）

- **默认策略（SC-003 验证）**  
  - 确认应用启动后处于 `LogPolicy.defaultReleasePolicy()`，即全局级别 INFO/ERROR 且 `enableDebugFile=false`。  
  - 使用 adb 检查日志目录应为空：  
    ```bash
    adb shell ls /data/data/com.xyoye.dandanplay/files/logs
    ```  
    如果目录不存在或仅包含占位文件即可视为通过，默认策略下不应生成持久化日志。
- **高日志量策略（性能试验用）**  
  - 在日志配置页选择 DEBUG 级别并开启调试日志，或在测试代码中直接调用 `LogSystem.updateLoggingPolicy(LogPolicy.highVolumePolicy())`。  
  - 该策略默认写入 `debug.log` / `debug_old.log`，单文件上限约 5MB，总体约 10MB，由 `LogFileManager` 的 5MB*2 限制保证。
- **验证步骤（SC-002）**  
  1) 在默认策略与高日志量策略下分别运行典型高频场景（列表滚动、播放、下载）各 30 分钟。  
  2) 记录冷启动耗时与主要交互的卡顿率，对比默认策略与关闭日志的基线增量是否 <5%。  
  3) 使用 adb 查看文件大小，确认高日志量策略下两份文件总计约 10MB：  
     ```bash
     adb shell du -h /data/data/com.xyoye.dandanplay/files/logs
     ```  
  4) 如触发磁盘不足导致写入停止，应看到 `debug.log` 停止增长且日志系统保持 logcat 输出。

## 6. 实现与测试建议

- 在 `common_component` 中为日志系统编写单元测试，覆盖：  
  - `LogPolicy` 对不同模块与级别的决策逻辑；  
  - 结构化字段序列化格式；  
  - 日志文件命名与双文件轮转规则。  
- 在 `app` 模块中编写 Instrumentation 测试，验证：  
  - 在开启与关闭调试日志时是否正确创建 / 清理 `debug.log` / `debug_old.log`；  
  - 在模拟磁盘空间不足或写入失败时，是否能及时停止写入且应用保持可用；  
  - 日志配置页面与本地日志文件生成行为是否满足用户故事中的验收场景（不依赖应用内导出入口）。

按照本 Quickstart 的约定完成实现后，应满足规范中的 FR-001~FR-016 以及 SC-001~SC-004 的可测条件。
