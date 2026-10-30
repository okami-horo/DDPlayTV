# 播放内核与旧配置兼容

更新日期：2026-10-30。默认内核统一使用全局设置，存储源不再保存内核覆盖。VLC 已从所有设备端移除。

## 当前内核

| 内核 | 持久化编号 | 用途 |
| --- | --- | --- |
| Media3 | `2` | 默认播放器；解码策略见 [Media3 解码说明](media3-decoding.md) |
| mpv | `4` | 可选播放器；输出模式与参数见 [mpv 配置](../mpv_conf_supported_options.md) |

在“设置 → 播放器”中统一选择内核，所有存储来源及播放入口采用同一全局选择。修改立即保存，下一次新建播放源时采用；当前播放和已准备的源保持实际内核，Activity 重建继续复用活动会话。编号保持不变。

mpv 播放失败后可“使用 Media3 重试”，只改变当前会话的实际内核，不改写全局设置。全局配置和运行时故障回退分别由持久化设置与 `PlaybackProfile` 管理。

## 升级兼容

- 旧全局编号 `3` 与其他未知值经 `PlayerType.valueOf(Int)` 回退到 Media3；打开播放器或播放器设置时写回 `2`。新安装默认使用 Media3。
- Room 17→18 移除旧媒体库内核覆盖和 PC 单库分组字段；旧覆盖值不写入全局设置。连接参数、主键、自增高水位、协议选项、播放历史和加密凭据引用保留。
- PC 分组改为“设置 → 应用 → PC 端按番剧分组”，默认按文件，适用于全部 PC 来源，重新进入后生效。无 Hash 视频在两种模式下使用相同的规范化文件路径身份；旧历史和快照仅在来源、视频 ID 及旧身份可验证时兼容恢复。
- 播放恢复快照保存来源描述，不保存播放器枚举；进程恢复时重新读取当前全局内核，不修改快照格式。
- VLC 专属硬解、音频兼容、代理和 TLS 设置读写已删除；既有 MMKV 中的相关旧键不再使用。共享代理与 Media3/mpv 的设置保持各自原有行为。

对应实现：[PlayerType](../../data_component/src/main/java/com/xyoye/data_component/enums/PlayerType.kt)、[默认配置](../../core_system_component/src/main/java/com/xyoye/common_component/config/PlayerConfigTable.kt)、[全局播放配置](../../core_storage_component/src/main/java/com/xyoye/common_component/source/factory/StorageVideoSourceFactory.kt)、[数据库迁移](../../core_database_component/src/main/java/com/xyoye/common_component/database/DatabaseManager.kt)。设计与本轮验证见[全局配置收敛记录](../../docs/superpowers/plans/2026-10-30-global-settings.md)。

## 移除范围与验证

已删除 libVLC Gradle 依赖、内核工厂与实现、专用渲染器、音频探测、事件日志、截图适配、设置入口与孤立图标，以及各存储来源的 VLC 代理分支。Media3/mpv 的播放、字幕、截图和传输所有权保持原有路径。

历史账本中的 VLC seek 超时记录继续保留，现已随功能退役退出验收范围；这不表示该历史故障已修复。外部 VLC/libass 实现比较仍可阅读，不能视为本项目仍支持 VLC。

2026-10-29 本地验证：

- `scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries -I .tmp/handoff14-local.gradle --offline --console=plain`：**BUILD SUCCESSFUL in 8m 21s**，日志 `.tmp/vlc-removal-validation.log`。389 项 JVM 用例中 387 通过、0 失败、2 项外部 FTP/SMB 服务用例跳过。
- Debug 与 Release ARM64 APK 检查均无 libVLC 文件、`org/videolan/libvlc` 或 `VlcVideoPlayer` dex 引用，保留 `libmpv.so`。记录：`.tmp/vlc-removal-counts.json`。
- API 34 `emulator-5558`，TV UI mode `0x14`，1920×1080/240 dpi、SwiftShader、ARM 转译；安装本次 Debug/test APK 后，**OK (10 tests)**（31.152 秒，无跳过），日志 `.tmp/vlc-removal-device.log`。覆盖旧全局值回退与写回、Media3/mpv 实际内核及 seek 持久化、加密请求重入/重建/返回、内核重试保留播放源/进度/偏好、设置持久化和分类 DPAD、媒体库旧覆盖及三个剩余选项的左右导航/选择/返回，以及播放器面板按键隔离。
- 设备类：`PlaybackHandoffInstrumentedTest`、`PlayerSettingDpadNavigationTest`、`LibraryPlayerSelectionTest`；另含 `PlaybackEntriesInstrumentedTest#kernelRetryKeepsActiveSourcePositionAndGlobalPreference` 和 `TvDpadRewriteInstrumentedTest#playerPanelArrowsDoNotSeekAndBackReturnsToTheCallingSetting`。使用 `adb shell am instrument -w -e class ... com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner` 执行。
- 纯 DPAD 从 TV 主页进入“我的 → 播放器设置 → 播放器类型”，选项仅有 Media3/mpv；已检查焦点高亮与返回操作。截图 `.tmp/vlc-removal-kernel-choice.png`，对应 UI 树 `.tmp/vlc-removal-kernel-choice.xml`。
- 恢复手机 UI mode `0x11`、1080×2340/440 dpi、非 root adbd 后，旧配置的设置重建与实际播放/seek 两项回归 **OK (2 tests)**（20.541 秒），日志 `.tmp/vlc-removal-mobile.log`。临时 TV feature 文件已移除；恢复读回见 `.tmp/vlc-removal-avd-restored.log`，crash buffer 无记录。任务启动的只读模拟器随后关闭。
- 初始编号 `3` 的回退测试在旧实现上失败（`.tmp/vlc-removal-red.log`）。后续曾因 Robolectric 离线缓存/沙箱网络限制无法构建，补齐依赖后恢复；新媒体库测试起初缺少 Android `MediaStore` 环境，增加 Robolectric runner 后通过。没有放宽业务断言。初次模拟器启动遇到沙箱锁文件权限问题，停止该进程后以获准的只读模式启动成功。

以上为本机验证，不代表实体电视、全部真实网络存储或完整字幕格式矩阵已验收；原有未完成场景继续见[播放验证遗留项](playback-validation-followup.md)。
