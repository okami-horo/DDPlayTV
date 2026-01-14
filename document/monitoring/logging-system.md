# 日志系统维护指南

本文面向开发与排查，说明如何开启调试日志、日志文件落盘位置，以及从设备获取日志的标准步骤。

对应实现（建议以代码为准）：
- `core_log_component/src/main/java/com/xyoye/common_component/log/LogFileManager.kt`
- `core_log_component/src/main/java/com/xyoye/common_component/log/LogPaths.kt`

## 配置入口
- 应用内入口：设置 → 开发者设置 → 日志（按项目实际页面命名可能略有差异）
- 调试日志写入：默认关闭；开启后会创建/更新本地调试日志文件；关闭后仍会输出基础 logcat，但不写入本地文件

## 日志文件位置

优先路径（默认）：
- `Download/DDPlayTV/logs/`
  - Android 10+ 通过 MediaStore 写入 Download，常见物理路径：`/sdcard/Download/DDPlayTV/logs/`（或 `/storage/emulated/0/Download/DDPlayTV/logs/`）

回退路径（极少数外部目录不可用）：
- 内部目录：`/data/data/com.okamihoro.ddplaytv/files/logs/`

文件：
- `log.txt`：当前会话
- `log_old.txt`：上一会话/滚动历史
- 单文件上限约 5MB（见 `DevelopLogConfigDefaults.DEFAULT_LOG_FILE_SIZE_LIMIT_BYTES`），两文件合计约 10MB；冷启动会自动轮转并合并历史

## 获取与排查步骤（建议）
1. **开启调试日志写入**：在「开发者设置 → 日志」中开启调试日志写入，并选择需要的最小日志级别。
2. **检查文件是否生成**（优先检查 Download 路径）：
   ```bash
   adb shell ls /sdcard/Download/DDPlayTV/logs
   adb shell du -h /sdcard/Download/DDPlayTV/logs
   ```
3. **拉取日志用于离线分析**：
   ```bash
   adb pull /sdcard/Download/DDPlayTV/logs ./logs_backup
   ```
4. **实时 logcat 过滤**（避免读取全量 logcat）：
   ```bash
   adb logcat | grep -E \"LogFacade|LogFileManager|module=\"
   ```
