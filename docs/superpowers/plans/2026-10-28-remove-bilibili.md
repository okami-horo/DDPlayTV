# 移除 Bilibili 功能执行记录

## 范围与决策

用户已确认全端移除 Bilibili，包括媒体库、账号登录、视频与直播播放、风控验证、历史同步，以及给本地视频匹配和下载 B 站弹幕。相关功能由独立项目 `E:\Project\PiliPlus` 接手。

保留普通本地与网络存储、投屏、弹弹play 弹幕匹配、本地弹幕文件解析和字幕。DanmakuFlameMaster 的 XML 解析器与许可证属于保留的本地弹幕能力。TV DPAD 重写仍须先确认设计，本次不接入新焦点库。

本次只读参考了 PiliPlus 的 `lib/pages/tv/library/repository.dart` 与 `lib/pages/tv/video/tv_video_playback_session.dart`，用于确认功能归属；没有移植代码，也没有修改 PiliPlus。

## 执行与验收

- [x] 删除集成模块、专有模型、入口、配置、资源及 Gradle 依赖，更新依赖治理。
- [x] 删除仅由该集成使用的播放器扩展、风控和直播弹幕链路，保留普通播放会话、队列和内核切换。
- [x] Room 升至 17，清理旧 B 站媒体库及关联播放历史；验证其他媒体库、播放进度和本地弹幕绑定保留。
- [x] 兼容旧播放恢复数据，验证普通播放恢复与资源释放。
- [x] 执行编译、单元测试、lint、模块治理与相关设备冒烟，记录真实结果。
- [x] 同步现行文档与 memory-bank，复查差异和残留。

## 验证记录

- 2026-10-28：默认 Wrapper 启动受本机 JDK/Gradle 路径影响，尚未进入有效构建；已改用仓库的 `scripts/dev-env/gradlew-local.bat` 和项目私有缓存。
- 初次有效构建发现交接持久化和原生切集处的两处残留引用，修复后编译通过。默认 Robolectric 下载受限，改用已有离线运行库；弹幕匹配测试原先写入 Windows 工作区外的 `/tmp`，改为测试托管的工作区临时目录后通过。早期失败日志保留在 `.tmp/build-bilibili-removal*.log`。
- 最终完整命令：`scripts/dev-env/gradlew-local.bat :app:assembleDebug testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries :app:assembleDebugAndroidTest :player_component:assembleDebugAndroidTest -I .tmp/handoff14-local.gradle --offline --console=plain`。日志尾部为 **BUILD SUCCESSFUL in 5m 54s**（`.tmp/bilibili-final-verification.log`）。共发现 378 个 JVM 用例，376 通过、0 失败、2 跳过；跳过项为需要外部测试服务的 FTP/SMB 协议集成，未宣称本次验证了它们。计数见 `.tmp/bilibili-test-counts.json`。
- 后续撤回无关格式化，修正设备测试的窗口等待；APK 再构建为 **BUILD SUCCESSFUL in 39s**，最终测试 APK 为 **BUILD SUCCESSFUL in 9s**，最终 `:app:lint` 为 **BUILD SUCCESSFUL in 1m 40s**。日志分别为 `.tmp/bilibili-final-apk.log`、`.tmp/bilibili-reentry-test-apk.log`、`.tmp/bilibili-final-lint.log`。未声称全仓 ktlint 通过。
- 升级兼容：Room 16→17 回归比较迁移前后其他库／历史的完整行，包含进度与本地弹幕、字幕、音轨绑定；生成的 17.json 与 16.json 除版本外结构一致。旧交接数据测试分别验证仅清除 B 站 pending 或 snapshot，另一个有效槽位保留。
- 设备为 API 34 `emulator-5558`，执行前确认 `device`；显式安装 ARM64 APK，通过 `adb shell am instrument` 执行。首轮 10 项中 9 项通过：启动凭证清理与其他设置保留、五项主要页面检查，以及 Media3/VLC/mpv 三个真实内核的立即加密进度保存。重入用例因删除验证页面后缺少窗口接收按键的等待而超时；日志确认 singleTask 重入暂停播放，增加窗口焦点／Intent 到达条件后单独通过（`.tmp/bilibili-device-reentry-window.log`），未修改生产播放行为。
- TV 模式（`mCurUiMode=0x14`）下，设置 DPAD 导航、弹窗返回焦点、首页导航／内容边界与播放重入／返回退出共 **4 项通过**（`.tmp/bilibili-tv-device.log`），无跳过。真实 Keystore/MMKV 的恢复、固定过期、损坏数据处理及退出清理 **3 项通过**（`.tmp/bilibili-keystore-device.log`）。这些是明确 APK 安装后的 ADB 仪器测试结果，不是 `connectedDebugAndroidTest` 通过记录。
- 纯 DPAD 人工冒烟：TV 启动 → 媒体库 → 我的 → 播放器设置 → BACK；返回后的 UI 树确认 `player_setting_ll` 为可见、enabled、focused 的原入口。登录页返回也恢复原条目。1920×1080 / 160 dpi 临时显示配置下检查个人页截图，入口完整、B 站入口已退出（`.tmp/bilibili-tv-personal-landscape.png`、`.tmp/bilibili-tv-settings-return.xml`）。
- 设备范围：使用手机 AVD 临时注入 television/leanback 特征验证应用 TV 分支，不代表实体电视兼容性。测试前已记录系统状态：SystemUI 当时已禁用、锁屏禁用、屏幕超时 2147483647；保留这份实际基线，不沿用旧账本中“已恢复 SystemUI”的假设。本次临时特征和显示覆盖已撤回；重启读回确认 UI mode `0x11`、1080×2340 / 440 dpi、非 root adbd，原禁用设置保持一致，临时特征文件不存在（`.tmp/bilibili-avd-restored.log`）。
- 静态复查：当前构建包含 20 个模块；模块快照与 Gradle 配置一致。生产源码中的 Bilibili 字面量只剩兼容清理和许可证；本地 XML 弹幕解析器继续保留。已核查 Markdown 本地链接与 `git diff --check`。
