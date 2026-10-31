# 串流、外部打开与磁力功能退役

## 范围

用户确认 TV 不需要接收其他 App 的视频打开请求，磁力搜索也不保留。本次移除手动 URL/请求头输入、外部播放 Activity 与视频 Intent 过滤器、磁力/种子在线播放、磁力资源搜索及迅雷 SDK。

保留本地/设备存储、SMB、WebDAV、FTP、PC、网盘、投屏接收、Media3/mpv、弹幕/字幕、TMDB 和通用播放缓存。既有来源 ID、正常历史、绑定与设置不得重置。最初实施未操作实体电视或提交 Git；用户随后明确要求提交本次改动并安装最新 Release，实际部署见末节。

## 实施与验证

1. 升级兼容：先补退役来源清理和加密播放恢复槽位隔离测试，再实现 Room 19→20 及旧描述符清理。验证保留来源/历史完整、ID 高水位和唯一索引不变。
2. 功能移除：删除入口、实现、专属模型/资源、API/配置、迅雷工件和构建依赖。调整保留功能的测试夹具，验证源码残留与依赖/播放边界。
3. 回归与记录：构建 Debug/Release 和测试 APK，执行 JVM/lint、变更文件格式检查；使用任务 AVD 验证纯 DPAD 启动、媒体库/来源、设置持久化/返回、Media3/mpv。核对 APK 无外部播放入口、迅雷库；同步当前文档与 memory-bank。

## 结果与升级兼容（2026-10-31）

功能移除已落地，最终构建、JVM、lint、变更文件 ktlint 和治理检查通过。设备回归仍有一项目录焦点恢复失败，不能宣称全部 TV 回归或实体电视验收通过。

- 删除两类内置来源、输入/搜索页面、外部打开 Activity、专属存储/传输/下载实现、配置/API、模型/DAO、图标与文案，以及 `repository:thunder` AAR 和依赖。番剧信息搜索、追番、弹幕搜索与字幕匹配继续保留；独立的迅雷字幕 HTTP 匹配接口不依赖已删除的 SDK。
- Room 19→20 清理串流/磁力来源及其播放历史，删除磁力搜索/筛选表，重建历史表去除 `torrent_path`、`torrent_index`。本地来源 ID 1、历史入口 ID 4 不重编号；正常来源/历史、文件级弹幕/字幕/音轨字段、唯一索引与自增高水位保留。
- 加密恢复状态按 pending/snapshot 分槽清理旧 external/torrent 和退役媒体类型；有效的另一槽继续保留。正常历史描述符仍需经过数据库的来源/文件身份校验。
- 依赖快照现在为 22 模块、91 条直接依赖；相对原快照仅删除迅雷模块和依赖边。19/20 schema 对比确认，除两张退役表及历史两列外，所有其他实体定义逐项一致，包括 TMDB 元数据和绑定表。

## 构建与 JVM

最终命令在包含本任务未提交修改的工作区执行；并行的文件浏览 C 设计文档/原型改动保留，未实施其 Android 页面。

```text
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleRelease
  :app:assembleDebugAndroidTest :player_component:assembleDebugAndroidTest
  :storage_component:assembleDebugAndroidTest testDebugUnitTest lint
  verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck
  --offline --console=plain
  -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle
  -I .tmp/retired-features-style.gradle
```

上述换行仅为阅读，实际按同一命令执行。两个本地 init 复用已缓存测试依赖和 Robolectric；格式 init 只检查本次变更的 Kotlin 文件，不代表全仓 ktlint 基线已清零。

- 最后一轮 `.tmp/retired-features-final3.log` 尾部为 **BUILD SUCCESSFUL in 2m**，1891 个任务（81 执行）。Debug、Release 和三份测试 APK 均已生成。
- JVM XML 汇总：476 项，**474 通过、0 失败/错误、2 项外部 FTP/SMB 环境用例跳过**。汇总及 schema 对比：`.tmp/retired-features-jvm-counts.json`。
- Room 迁移门禁覆盖退役记录清理、正常行逐列保留、两表/两列移除、自增 99→100、唯一索引及历史版本升级。恢复测试覆盖无效槽与有效槽隔离；播放器/传输回归使用保留的本地文件和投屏描述符。
- 第一轮完整命令已以 BUILD SUCCESSFUL 结束（`.tmp/retired-features-final.log`）；随后测试夹具补正的一轮因诊断断言换行触发 ktlint 而 **BUILD FAILED**（`final2.log`），格式修正后才得到上述最终结果。较早的重复 JSON 注解、残留括号/注解和 Windows 文件路径夹具问题已修正；中断的 `build2.log` 没有完整通过结论。

## 模拟器回归与未通过项

使用项目内独立新建的 API 34 AVD `emulator-5560`，1440×810 / 240 dpi、SwiftShader 与 ARM64 转译。测试通过 ADB 直接调用 AndroidJUnitRunner，未执行本轮 Gradle connected/UTP，不把直接 runner 等同于 connected 测试。

- 应用选定套件 27 项：**25 通过、1 失败、1 跳过**（`.tmp/retired-features-device-app-final.log`）。通过范围包括 Media3/mpv 的存储/历史/番剧入口、90%/95% 续播、内核重试与全局设置、手动/原生续集、播放状态命令、投屏接收、seek 加密恢复、重入/重建，以及纯 DPAD 播放面板、来源选择和设置持久化/返回。
- 播放器真实 Keystore/MMKV 三项均通过（`.tmp/retired-features-device-keystore.log`）。合计 **28 项通过、1 项失败、1 项跳过**；逐项结果在 `.tmp/retired-features-device-counts.json`。跳过项依赖真实 SMB/FTP 联调环境，未提供本轮环境。
- **未通过：** `TvDpadRewriteInstrumentedTest#storageRestoresDirectoryItemAndSupportsTheSecondaryAction` 在目录页重建后，断言恢复到第二个文件时失败（源码第 150 行）。选定套件和独立复跑均复现，窗口有焦点，当前焦点为文件行 ConstraintLayout。证据：`retired-features-device-directory2.log`、`retired-focus-probe.log`。本次没有修改该目录焦点实现；尚未建立改动前基线或确认根因，不能称为已知旧问题或已经修复。保留给已明确暂缓的[文件浏览 C 后续任务](2026-10-31-file-browser-preview.md)复查，本次不扩大为目录页面实现。
- 首轮回归的本地测试夹具缺少媒体库前置记录，且新 AVD 的系统首次全屏提示接管了按键焦点。补齐夹具、等待前台窗口并确认系统提示后，播放状态/下一集两个用例独立复跑均通过，再运行上述选定套件。系统提示证据：`.tmp/retired-projection-ui.xml`；复跑：`retired-features-device-focused3.log`。未改动播放断言的预期结果。
- 已检查来源菜单、FTP 编辑入口与目录截图：`.tmp/retired-source-chooser.png`、`retired-source-ftp.png`、`retired-files.png`。这些截图是保留功能的状态证据，不是文件浏览 C、完整布局或实体 TV 的视觉验收。

任务 AVD 已关闭。上述回归阶段未操作实体电视；后续按用户要求完成 Release 安装与工件回读，见末节。真实网络来源、实体 TV 行为与完整顺序播放验收仍保留各自边界。

## APK 与源码检查

- ARM64 Release：`app/build/outputs/apk/release/ddplaytv_v4.2.2_arm64-v8a-release.apk`，**77,483,299 字节**。同版本移除前工件为 83,904,164 字节，减少 **6,420,865 字节，约 6.12 MiB / 7.65%**；这是实际 APK 差值。
- SHA-256：`28D188CD209745BB06200859B71B4DAC75D3BCE215E1B9A3F85A9BFEB4CA5E59`。`apksigner verify` 确认 v2 签名通过；沿用项目当前签名配置。
- DEX 无 PlayerIntentActivity、SearchMagnetFragment、TorrentStorage、ThunderManager 与 xunlei SDK 类，native 条目无迅雷库；视频 VIEW 过滤器已移除。Manifest 中保留的 VIEW 为浏览器可见性 queries，以及受 `BLUETOOTH_PRIVILEGED` 保护的 Media3 音频蓝牙验证 Activity。
- 旧数据库迁移与日志隐私脱敏中的 magnet 字符串继续保留，分别用于升级兼容和通用隐私保护。完整检查产物：`.tmp/retired-features-apk.json`、`retired-features-manifest.txt`、`retired-features-signature.txt`、`retired-features-sourcecheck.txt`。
- `git diff --check` 通过。任务环境的失败启动进程已核对并停止，重复锁错误日志仅保留有界尾部。

## 用户授权的提交与电视部署（2026-10-31）

- 用户明确要求提交 Git，并安装最新 Release 到电视。提交范围为本次功能退役、兼容处理、测试与验证记录；播放器控件设计/原型等其他任务改动保留在工作区。
- 连接 `192.168.31.70:5555`，确认型号 `VIDAA_TV`、支持 `arm64-v8a`。初次连接报本机套接字权限错误 10013；在设备列表为空时重启 ADB 服务后恢复连接。
- 使用 `adb -s 192.168.31.70:5555 install -r app/build/outputs/apk/release/ddplaytv_v4.2.2_arm64-v8a-release.apk`，返回 `Success`。未卸载或清除应用数据。
- 回读包版本为 `4.2.2`（versionCode `69`），`lastUpdateTime=2026-10-31 15:36:23`。通过 `pm path` 定位已安装的 `base.apk`，设备端 SHA-256 与上文已验证的本地 Release 完全一致。
- 本次只执行安装及包信息/工件回读，没有启动应用或进行电视导航/播放测试；目录焦点未通过项与其他验收边界继续保留。结构化回读记录：`.tmp/retired-features-tv-deployment.json`。
