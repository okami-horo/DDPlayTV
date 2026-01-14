# 日志系统维护指南

本文面向维护与排查同学，概述新的日志系统如何开启、日志文件存放位置，以及从设备获取日志的标准步骤。详细使用说明请参考 `specs/001-logging-redesign/quickstart.md`。

## 配置入口
- 应用内入口：设置→「开发者设置」中的「日志」分组，可直接选择日志级别并开启/关闭调试日志写入（无单独的日志配置页面）。
- 调试日志开关：开启后会创建/更新本地调试日志文件；关闭后仅保留默认策略（logcat 级别 INFO+/WARN/ERROR），不写入本地文件。

## 日志文件位置
- 目录（默认）：`/sdcard/Android/data/com.okamihoro.ddplaytv/files/logs/`  
  - 实际实现中由 `LogPaths.LOG_DIR_NAME` 与 `PathHelper.getCachePath()` 共同决定，即「应用外部缓存根目录」下的 `logs` 子目录。
  - 如外部缓存目录不可用（极少数设备/环境），会自动回退到内部目录：`/data/data/com.okamihoro.ddplaytv/files/logs/`。
- 文件：`log.txt`（当前会话）、`log_old.txt`（上一会话），单文件上限约 5MB，总体约 10MB，冷启动会自动轮转并合并历史。

## 获取与排查步骤
1. **确认开关状态**：在「开发者设置 > 日志」中开启调试日志，并选择需要的最小日志级别（默认 INFO）。磁盘错误熔断后需重新开关调试会话。
2. **检查文件是否生成**：
   ```bash
   # 默认路径：外部缓存目录
   adb shell ls /sdcard/Android/data/com.okamihoro.ddplaytv/files/logs
   adb shell du -h /sdcard/Android/data/com.okamihoro.ddplaytv/files/logs

   # 如外部目录无日志，可在极端情况下检查内部回退目录（通常无需）：
   adb shell ls /data/data/com.okamihoro.ddplaytv/files/logs
   ```
3. **拉取日志用于离线分析**：
   ```bash
   adb pull /sdcard/Android/data/com.okamihoro.ddplaytv/files/logs ./logs_backup
   ```
4. **常用过滤**：日志按 `time level module tag ctx_*` 等字段输出，可在桌面使用 `grep`/脚本按 `module`、`ctx_scene`、`ctx_errorCode`、`seq` 过滤；logcat 实时查看时建议使用：
   ```bash
   adb logcat | grep -E "LogSystem|LogWriter|module="
   ```

更多场景示例（性能、高日志量实验、字段解释）请查阅 `specs/001-logging-redesign/quickstart.md`。
